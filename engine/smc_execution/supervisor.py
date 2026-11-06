from __future__ import annotations

import asyncio
import hashlib
import logging
from typing import TYPE_CHECKING, Any

from .config import ExecutionConfig
from .errors import CriticalExecutionError, OrderNotFound
from .models import (
    BPS,
    ZERO,
    Action,
    D,
    ExecutionRecord,
    ExecutionStage,
    OrderKind,
    OrderRequest,
    OrderSnapshot,
    OrderStatus,
    PositionMode,
    Side,
    SymbolRules,
    ceil_to_step,
    floor_to_step,
)
from .ports import ControlPlane, ExchangeAPI

if TYPE_CHECKING:
    from .modes import DynamicProtectionPolicy

log = logging.getLogger(__name__)


class ProtectionSupervisor:
    """Continuously proves that every managed open position has a live stop.

    The supervisor is deliberately fail-closed. It never assumes a protection
    order exists merely because an earlier POST returned successfully; every
    pass reconciles the durable saga with exchange state.
    """

    def __init__(
        self,
        exchange_api: ExchangeAPI,
        control_plane: ControlPlane,
        config: ExecutionConfig = ExecutionConfig(),
        *,
        interval_seconds: float = 1.0,
        dynamic_protection: "DynamicProtectionPolicy | None" = None,
    ) -> None:
        self.api = exchange_api
        self.control = control_plane
        self.config = config
        self.interval_seconds = interval_seconds
        self.dynamic_protection = dynamic_protection
        self._stop_event = asyncio.Event()

    async def run(self) -> None:
        self._stop_event.clear()
        while not self._stop_event.is_set():
            try:
                await self.reconcile_once()
            except Exception:
                log.exception("Protection supervisor iteration failed")
                await self.control.halt_admission("protection supervisor failure")
            try:
                await asyncio.wait_for(
                    self._stop_event.wait(), timeout=self.interval_seconds
                )
            except TimeoutError:
                pass

    def stop(self) -> None:
        self._stop_event.set()

    async def reconcile_once(self) -> int:
        repaired = 0
        for record in await self.control.list_nonterminal():
            if record.stage not in {
                ExecutionStage.PROTECTED,
                ExecutionStage.PROTECTION_PENDING,
                ExecutionStage.FAILED_UNCERTAIN,
            }:
                continue
            changed = await self.reconcile_record(record)
            repaired += int(changed)
        return repaired

    async def reconcile_record(self, record: ExecutionRecord) -> bool:
        action = record.action
        mode = PositionMode(record.payload.get("position_mode", "ONEWAY"))
        position = await self.api.get_position(record.symbol, action)
        protection = dict(record.payload.get("protection") or {})

        if position.is_flat:
            await self._cancel_known_protection(record.symbol, protection)
            await self.control.release_risk(record.execution_id)
            await self.control.transition(
                record.execution_id,
                expected=None,
                new_stage=ExecutionStage.CLOSED,
                patch={"closed_by_supervisor": True},
            )
            return True

        self._validate_position_identity(record, mode, position.quantity, position.position_side)

        stop_price_raw = record.payload.get("stop_price")
        if stop_price_raw is None:
            await self.control.halt_admission(
                f"open position without recoverable stop price: {record.execution_id}"
            )
            raise CriticalExecutionError(
                f"Execution {record.execution_id} has exposure but no stop price"
            )
        stop_price = D(str(stop_price_raw))
        rules = self._rules(record)
        target_qty = floor_to_step(position.abs_qty, rules.step_size)
        if target_qty < rules.min_qty:
            raise CriticalExecutionError(
                f"Position {record.symbol} is non-zero but below exchange lot size"
            )

        entry_price = position.entry_price
        if entry_price <= ZERO:
            entry_price = D(str(record.payload.get("avg_price", "0")))
        if entry_price <= ZERO:
            raise CriticalExecutionError(
                f"Cannot compute risk for {record.execution_id}: missing entry price"
            )

        adjusted_stop, stop_patch = self._dynamic_stop_price(
            record=record,
            action=action,
            entry_price=entry_price,
            mark_price=position.mark_price,
            current_stop=stop_price,
            rules=rules,
        )
        if adjusted_stop != stop_price:
            stop_price = adjusted_stop
            record = await self.control.transition(
                record.execution_id,
                expected=None,
                new_stage=record.stage,
                patch=stop_patch,
            )
            protection = dict(record.payload.get("protection") or protection)

        stop = await self._ensure_stop(
            record=record,
            action=action,
            mode=mode,
            stop_price=stop_price,
            target_qty=target_qty,
            rules=rules,
            protection=protection,
        )
        tp = await self._reconcile_take_profit(
            record=record,
            action=action,
            mode=mode,
            target_qty=target_qty,
            rules=rules,
            protection=protection,
        )

        risk = self._managed_risk(action, target_qty, entry_price, stop_price)
        await self.control.commit_risk(record.execution_id, risk)

        updated = {
            "symbol": record.symbol,
            "action": action.value,
            "stop_order_id": stop.order_id,
            "stop_client_order_id": stop.client_order_id,
            "stop_kind": OrderKind.ALGO.value,
            "take_profit_order_id": None if tp is None else tp.order_id,
            "take_profit_client_order_id": None if tp is None else tp.client_order_id,
            "take_profit_kind": None if tp is None else tp.kind.value,
        }
        await self.control.transition(
            record.execution_id,
            expected=None,
            new_stage=ExecutionStage.PROTECTED,
            patch={
                "protection": updated,
                "supervisor_reconciled": True,
                "executed_qty": str(target_qty),
                "actual_risk": str(risk),
            },
        )
        return True


    def _dynamic_stop_price(
        self,
        *,
        record: ExecutionRecord,
        action: Action,
        entry_price: D,
        mark_price: D,
        current_stop: D,
        rules: SymbolRules,
    ) -> tuple[D, dict[str, Any]]:
        policy = self.dynamic_protection
        if policy is None or not policy.enabled or mark_price <= ZERO:
            return current_stop, {}

        initial_raw = record.payload.get("initial_stop_price")
        initial_stop = D(str(initial_raw)) if initial_raw is not None else current_stop
        initial_distance = abs(entry_price - initial_stop)
        if initial_distance <= ZERO:
            return current_stop, {}

        favorable = (
            mark_price - entry_price
            if action is Action.LONG
            else entry_price - mark_price
        )
        r_multiple = favorable / initial_distance
        candidate = current_stop
        reason: str | None = None

        if r_multiple >= policy.breakeven_activation_r:
            # A so-called breakeven stop must cover the configured round-trip
            # fees and execution reserve; otherwise it is only nominally flat.
            effective_lock_bps = max(
                policy.breakeven_lock_bps,
                self.config.estimated_round_trip_fees_bps
                + self.config.execution_risk_reserve_bps,
            )
            buffer = entry_price * effective_lock_bps / BPS
            if action is Action.LONG:
                breakeven = floor_to_step(entry_price + buffer, rules.tick_size)
                breakeven = min(breakeven, mark_price - rules.tick_size)
                if breakeven > candidate:
                    candidate = breakeven
                    reason = "BREAKEVEN"
            else:
                breakeven = ceil_to_step(entry_price - buffer, rules.tick_size)
                breakeven = max(breakeven, mark_price + rules.tick_size)
                if breakeven < candidate:
                    candidate = breakeven
                    reason = "BREAKEVEN"

        atr_raw = record.payload.get("atr")
        if (
            policy.trailing_activation_r is not None
            and policy.trailing_atr_multiple is not None
            and r_multiple >= policy.trailing_activation_r
            and atr_raw is not None
        ):
            atr = D(str(atr_raw))
            if atr > ZERO:
                if action is Action.LONG:
                    trailing = floor_to_step(
                        mark_price - atr * policy.trailing_atr_multiple,
                        rules.tick_size,
                    )
                    trailing = min(trailing, mark_price - rules.tick_size)
                    if trailing > candidate:
                        candidate = trailing
                        reason = "ATR_TRAILING"
                else:
                    trailing = ceil_to_step(
                        mark_price + atr * policy.trailing_atr_multiple,
                        rules.tick_size,
                    )
                    trailing = max(trailing, mark_price + rules.tick_size)
                    if trailing < candidate:
                        candidate = trailing
                        reason = "ATR_TRAILING"

        # A dynamic policy may only tighten protection, never loosen it.
        if action is Action.LONG and candidate <= current_stop:
            return current_stop, {}
        if action is Action.SHORT and candidate >= current_stop:
            return current_stop, {}

        return candidate, {
            "initial_stop_price": str(initial_stop),
            "stop_price": str(candidate),
            "dynamic_stop": {
                "reason": reason,
                "r_multiple": str(r_multiple),
                "mark_price": str(mark_price),
            },
        }

    async def _ensure_stop(
        self,
        *,
        record: ExecutionRecord,
        action: Action,
        mode: PositionMode,
        stop_price: D,
        target_qty: D,
        rules: SymbolRules,
        protection: dict[str, Any],
    ) -> OrderSnapshot:
        stop_id = protection.get("stop_order_id")
        stop_client = protection.get("stop_client_order_id")
        existing_active: OrderSnapshot | None = None
        if stop_client:
            try:
                current = await self.api.get_order(
                    record.symbol,
                    str(stop_id) if stop_id else None,
                    str(stop_client),
                    OrderKind.ALGO,
                )
                if current.status.active and self._stop_matches(
                    current, stop_price, target_qty, rules
                ):
                    return current
                if current.status.active:
                    existing_active = current
            except OrderNotFound:
                pass
            except CriticalExecutionError:
                raise
            except Exception:
                log.exception("Could not query stop for %s", record.execution_id)
                raise CriticalExecutionError(
                    f"Could not prove current stop state for {record.execution_id}"
                )

        # Never remove the only proven stop before the replacement is active.
        replacement_client = self._replacement_id(record, "SLR")
        request = OrderRequest(
            symbol=record.symbol,
            side=Side.SELL if action is Action.LONG else Side.BUY,
            order_type="STOP_MARKET",
            kind=OrderKind.ALGO,
            quantity=None if self.config.stop_close_position else target_qty,
            stop_price=stop_price,
            close_position=self.config.stop_close_position,
            reduce_only=(
                False
                if self.config.stop_close_position
                else mode is PositionMode.ONEWAY
            ),
            position_side=action.value if mode is PositionMode.HEDGE else "BOTH",
            working_type=self.config.stop_working_type,
            price_protect=self.config.stop_price_protect,
            client_order_id=replacement_client,
        )
        replacement = await self.api.place_order(request)
        if not replacement.status.active:
            await self.control.halt_admission(
                f"replacement stop rejected: {record.execution_id}"
            )
            raise CriticalExecutionError(
                f"Replacement stop is not active: {replacement.status.value}"
            )

        if existing_active is not None:
            try:
                await self._cancel_active_strict(existing_active)
            except Exception as exc:
                # The replacement remains live. Two protective stops are preferable
                # to a naked position, but operator reconciliation is required.
                await self.control.halt_admission(
                    f"old stop cancellation uncertain after replacement: {record.execution_id}"
                )
                raise CriticalExecutionError(
                    f"Replacement stop is active but old stop cancellation is uncertain: "
                    f"{existing_active.client_order_id}"
                ) from exc
        return replacement

    def _stop_matches(
        self,
        current: OrderSnapshot,
        stop_price: D,
        target_qty: D,
        rules: SymbolRules,
    ) -> bool:
        price_matches = (
            current.stop_price <= ZERO
            or abs(current.stop_price - stop_price) < rules.tick_size
        )
        if self.config.stop_close_position:
            return price_matches
        quantity_matches = abs(current.remaining_qty - target_qty) < rules.step_size
        return price_matches and quantity_matches

    async def _cancel_active_strict(self, order: OrderSnapshot) -> None:
        try:
            canceled = await self.api.cancel_order(
                order.symbol,
                order.order_id,
                order.client_order_id,
                order.kind,
            )
        except Exception as exc:
            try:
                canceled = await self.api.get_order(
                    order.symbol,
                    order.order_id,
                    order.client_order_id,
                    order.kind,
                )
            except Exception as query_exc:
                raise CriticalExecutionError(
                    f"Cannot prove stop cancellation: {order.client_order_id}"
                ) from query_exc
            if canceled.status.active:
                raise CriticalExecutionError(
                    f"Stop remains active after failed replacement cancel: "
                    f"{order.client_order_id}"
                ) from exc
        if canceled.status.active:
            raise CriticalExecutionError(
                f"Stop remains active after cancellation: {order.client_order_id}"
            )

    async def _reconcile_take_profit(
        self,
        *,
        record: ExecutionRecord,
        action: Action,
        mode: PositionMode,
        target_qty: D,
        rules: SymbolRules,
        protection: dict[str, Any],
    ) -> OrderSnapshot | None:
        tp_price_raw = record.payload.get("take_profit_price")
        if tp_price_raw is None or target_qty < rules.min_qty:
            await self._cancel_one(
                record.symbol,
                protection.get("take_profit_order_id"),
                protection.get("take_profit_client_order_id"),
                OrderKind.REGULAR,
            )
            return None
        tp_price = D(str(tp_price_raw))
        if target_qty * tp_price < rules.min_notional:
            await self._cancel_one(
                record.symbol,
                protection.get("take_profit_order_id"),
                protection.get("take_profit_client_order_id"),
                OrderKind.REGULAR,
            )
            return None

        existing: OrderSnapshot | None = None
        client_id = protection.get("take_profit_client_order_id")
        order_id = protection.get("take_profit_order_id")
        if client_id:
            try:
                existing = await self.api.get_order(
                    record.symbol,
                    str(order_id) if order_id else None,
                    str(client_id),
                    OrderKind.REGULAR,
                )
            except OrderNotFound:
                existing = None

        if existing is not None and existing.status.active:
            same_qty = abs(existing.remaining_qty - target_qty) < rules.step_size
            same_price = (
                existing.price <= ZERO
                or abs(existing.price - tp_price) < rules.tick_size
            )
            if same_qty and same_price:
                return existing
            await self._cancel_one(
                record.symbol,
                existing.order_id,
                existing.client_order_id,
                OrderKind.REGULAR,
            )

        request = OrderRequest(
            symbol=record.symbol,
            side=Side.SELL if action is Action.LONG else Side.BUY,
            order_type="LIMIT",
            kind=OrderKind.REGULAR,
            quantity=target_qty,
            price=tp_price,
            time_in_force="GTC",
            reduce_only=mode is PositionMode.ONEWAY,
            position_side=action.value if mode is PositionMode.HEDGE else "BOTH",
            self_trade_prevention_mode=self.config.self_trade_prevention_mode,
            client_order_id=self._replacement_id(record, "TPR"),
        )
        replacement = await self.api.place_order(request)
        if replacement.status in {
            OrderStatus.REJECTED,
            OrderStatus.CANCELED,
            OrderStatus.EXPIRED,
            OrderStatus.EXPIRED_IN_MATCH,
        }:
            log.error("Replacement TP rejected for %s", record.execution_id)
            return None
        return replacement

    async def _cancel_known_protection(
        self, symbol: str, protection: dict[str, Any]
    ) -> None:
        await self._cancel_one(
            symbol,
            protection.get("stop_order_id"),
            protection.get("stop_client_order_id"),
            OrderKind.ALGO,
        )
        await self._cancel_one(
            symbol,
            protection.get("take_profit_order_id"),
            protection.get("take_profit_client_order_id"),
            OrderKind.REGULAR,
        )

    async def _cancel_one(
        self,
        symbol: str,
        order_id: Any,
        client_id: Any,
        kind: OrderKind,
    ) -> None:
        if not client_id:
            return
        try:
            await self.api.cancel_order(
                symbol, str(order_id or ""), str(client_id), kind
            )
        except Exception:
            log.debug("Protection order already terminal/not found", exc_info=True)

    def _managed_risk(
        self, action: Action, quantity: D, entry_price: D, stop_price: D
    ) -> D:
        if action is Action.LONG:
            downside = max(ZERO, entry_price - stop_price)
        else:
            downside = max(ZERO, stop_price - entry_price)
        costs = entry_price * (
            self.config.estimated_round_trip_fees_bps
            + self.config.execution_risk_reserve_bps
        ) / BPS
        return quantity * (downside + costs)

    @staticmethod
    def _validate_position_identity(
        record: ExecutionRecord,
        mode: PositionMode,
        quantity: D,
        position_side: str,
    ) -> None:
        if mode is PositionMode.ONEWAY:
            wrong_direction = (
                record.action is Action.LONG and quantity < ZERO
            ) or (record.action is Action.SHORT and quantity > ZERO)
            if wrong_direction:
                raise CriticalExecutionError(
                    f"One-way position direction no longer belongs to execution "
                    f"{record.execution_id}: qty={quantity}"
                )
        elif position_side != record.action.value:
            raise CriticalExecutionError(
                f"Hedge position side mismatch for {record.execution_id}: "
                f"{position_side}"
            )

    @staticmethod
    def _replacement_id(record: ExecutionRecord, suffix: str) -> str:
        digest = hashlib.sha256(record.execution_id.encode()).hexdigest()[:18]
        return f"smc-{digest}-{suffix}{record.version % 1000:03d}"[:36]

    @staticmethod
    def _rules(record: ExecutionRecord) -> SymbolRules:
        raw = record.payload.get("rules")
        if not isinstance(raw, dict):
            raise CriticalExecutionError("Missing symbol rules in durable state")
        return SymbolRules(
            symbol=str(raw["symbol"]),
            tick_size=D(str(raw["tick_size"])),
            step_size=D(str(raw["step_size"])),
            min_qty=D(str(raw["min_qty"])),
            max_qty=D(str(raw["max_qty"])),
            min_notional=D(str(raw["min_notional"])),
            max_notional=None
            if raw.get("max_notional") is None
            else D(str(raw["max_notional"])),
            status=str(raw.get("status", "TRADING")),
        )
