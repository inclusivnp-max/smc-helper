from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

from .config import ExecutionConfig
from .errors import CriticalExecutionError, OrderNotFound, StateConflict
from .models import (
    BPS,
    ZERO,
    Action,
    D,
    ExecutionRecord,
    ExecutionStage,
    OrderKind,
    PositionMode,
    PositionSnapshot,
    ReconciliationReport,
    ceil_to_step,
    encode_json,
    floor_to_step,
)
from .ports import ControlPlane, ExchangeAPI
from .supervisor import ProtectionSupervisor

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class RecoveryConfig:
    """Startup policy for exchange state not owned by this runtime."""

    halt_on_orphan_positions: bool = True
    halt_on_orphan_orders: bool = True


class RecoveryManager:
    """Fail-closed startup reconciliation.

    Entry orders are never replayed blindly. Active known entry orders are first
    canceled and reconciled, then actual account positions become the source of
    truth. Any exposure is protected before admission can reopen.
    """

    def __init__(
        self,
        exchange_api: ExchangeAPI,
        control_plane: ControlPlane,
        supervisor: ProtectionSupervisor,
        execution_config: ExecutionConfig = ExecutionConfig(),
        recovery_config: RecoveryConfig = RecoveryConfig(),
    ) -> None:
        self.api = exchange_api
        self.control = control_plane
        self.supervisor = supervisor
        self.execution_config = execution_config
        self.recovery_config = recovery_config

    async def recover_startup(
        self, *, resume_on_success: bool = True
    ) -> ReconciliationReport:
        await self.control.halt_admission("startup reconciliation in progress")
        records = list(await self.control.list_nonterminal())
        repaired = closed = unresolved = 0
        messages: list[str] = []
        managed_positions: set[tuple[str, str]] = set()

        for record in records:
            try:
                if record.stage in {
                    ExecutionStage.CLAIMED,
                    ExecutionStage.PRECHECKED,
                    ExecutionStage.RISK_RESERVED,
                }:
                    await self.control.release_risk(record.execution_id)
                    await self.control.transition(
                        record.execution_id,
                        expected=None,
                        new_stage=ExecutionStage.ABORTED,
                        patch={"recovery": "aborted before exchange submission"},
                    )
                    closed += 1
                    continue

                # Any active entry order can still alter exposure. Make it terminal
                # before using the account position as recovery truth.
                await self._cancel_and_reconcile_entry_orders(record)
                position = await self.api.get_position(record.symbol, record.action)
                if position.is_flat:
                    await self._cancel_known_protection(record)
                    await self.control.release_risk(record.execution_id)
                    await self.control.transition(
                        record.execution_id,
                        expected=None,
                        new_stage=ExecutionStage.CLOSED,
                        patch={"recovery": "flat account state confirmed"},
                    )
                    closed += 1
                    continue

                self._validate_position_direction(record, position)
                current = await self._ensure_recoverable_plan(record, position)
                await self.supervisor.reconcile_record(current)
                managed_positions.add(self._position_key(position, record.action))
                repaired += 1
            except Exception as exc:
                unresolved += 1
                message = f"{record.execution_id}: {exc!r}"
                messages.append(message)
                log.exception("Recovery unresolved: %s", record.execution_id)
                try:
                    await self.control.transition(
                        record.execution_id,
                        expected=None,
                        new_stage=ExecutionStage.FAILED_UNCERTAIN,
                        patch={"recovery_error": repr(exc)},
                    )
                except Exception:
                    log.exception("Could not persist failed recovery state")

        orphan_messages = await self._detect_orphan_exchange_state(managed_positions)
        if orphan_messages:
            unresolved += len(orphan_messages)
            messages.extend(orphan_messages)

        stream_healthy = await self.api.is_user_stream_healthy()
        if unresolved == 0 and stream_healthy:
            if resume_on_success:
                await self.control.resume_admission()
            else:
                reason = "containment reconciliation completed; operator/startup gate remains closed"
                await self.control.halt_admission(reason)
                messages.append(reason)
        else:
            reason = (
                f"startup recovery unresolved={unresolved}, "
                f"user_stream_healthy={stream_healthy}"
            )
            await self.control.halt_admission(reason)
            messages.append(reason)

        return ReconciliationReport(
            scanned=len(records),
            repaired=repaired,
            closed=closed,
            unresolved=unresolved,
            messages=tuple(messages),
        )

    async def _cancel_and_reconcile_entry_orders(
        self, record: ExecutionRecord
    ) -> None:
        for client_id, order_id in self._entry_identifiers(record.payload):
            try:
                order = await self.api.get_order(
                    record.symbol, order_id, client_id, OrderKind.REGULAR
                )
            except OrderNotFound:
                continue
            if order.status.active:
                try:
                    order = await self.api.cancel_order(
                        record.symbol,
                        order.order_id,
                        order.client_order_id,
                        OrderKind.REGULAR,
                    )
                except Exception:
                    order = await self.api.get_order(
                        record.symbol,
                        order.order_id,
                        order.client_order_id,
                        OrderKind.REGULAR,
                    )
            if order.status.active:
                raise CriticalExecutionError(
                    f"Entry order remains active during recovery: {client_id}"
                )

    async def _ensure_recoverable_plan(
        self, record: ExecutionRecord, position: PositionSnapshot
    ) -> ExecutionRecord:
        payload = dict(record.payload)
        rules = await self.api.get_symbol_rules(record.symbol)
        entry_price = position.entry_price
        if entry_price <= ZERO:
            entry_price = D(str(payload.get("avg_price", "0")))
        if entry_price <= ZERO:
            raise CriticalExecutionError("Open position has no recoverable entry price")

        stop_raw = payload.get("stop_price")
        tp_raw = payload.get("take_profit_price")
        if stop_raw is None or tp_raw is None:
            atr_raw = payload.get("atr")
            if atr_raw is None:
                raise CriticalExecutionError(
                    "Exposure exists but durable ATR/protection plan is missing"
                )
            atr = D(str(atr_raw))
            stop_distance = max(
                atr * self.execution_config.stop_atr_multiple,
                entry_price * self.execution_config.min_stop_distance_bps / BPS,
            )
            reward_distance = max(
                atr * self.execution_config.take_profit_atr_multiple,
                stop_distance * self.execution_config.minimum_reward_risk,
            )
            if record.action is Action.LONG:
                stop_price = floor_to_step(
                    entry_price - stop_distance, rules.tick_size
                )
                tp_price = ceil_to_step(
                    entry_price + reward_distance, rules.tick_size
                )
            else:
                stop_price = ceil_to_step(
                    entry_price + stop_distance, rules.tick_size
                )
                tp_price = floor_to_step(
                    entry_price - reward_distance, rules.tick_size
                )
        else:
            stop_price = D(str(stop_raw))
            tp_price = D(str(tp_raw))

        if stop_price <= ZERO or tp_price <= ZERO:
            raise CriticalExecutionError("Recovered protection prices are invalid")

        quantity = floor_to_step(position.abs_qty, rules.step_size)
        if quantity < rules.min_qty:
            raise CriticalExecutionError("Recovered position is below minimum lot size")
        costs = entry_price * (
            self.execution_config.estimated_round_trip_fees_bps
            + self.execution_config.execution_risk_reserve_bps
        ) / BPS
        downside = (
            max(ZERO, entry_price - stop_price)
            if record.action is Action.LONG
            else max(ZERO, stop_price - entry_price)
        )
        actual_risk = quantity * (downside + costs)
        try:
            await self.control.commit_risk(record.execution_id, actual_risk)
        except StateConflict as exc:
            raise CriticalExecutionError(
                "Exposure exists without its durable risk reservation"
            ) from exc

        return await self.control.transition(
            record.execution_id,
            expected=None,
            new_stage=ExecutionStage.PROTECTION_PENDING,
            patch={
                "recovery": "protection plan reconstructed from account position",
                "position_mode": position.position_mode.value,
                "avg_price": str(entry_price),
                "executed_qty": str(quantity),
                "actual_risk": str(actual_risk),
                "stop_price": str(stop_price),
                "take_profit_price": str(tp_price),
                "rules": encode_json(rules.__dict__),
            },
        )

    async def _cancel_known_protection(self, record: ExecutionRecord) -> None:
        protection = record.payload.get("protection")
        if not isinstance(protection, dict):
            return
        for order_id_key, client_key, kind in (
            ("stop_order_id", "stop_client_order_id", OrderKind.ALGO),
            (
                "take_profit_order_id",
                "take_profit_client_order_id",
                OrderKind.REGULAR,
            ),
        ):
            client_id = protection.get(client_key)
            if not client_id:
                continue
            try:
                await self.api.cancel_order(
                    record.symbol,
                    str(protection.get(order_id_key) or ""),
                    str(client_id),
                    kind,
                )
            except Exception:
                pass

    async def _detect_orphan_exchange_state(
        self, managed_positions: set[tuple[str, str]]
    ) -> list[str]:
        messages: list[str] = []
        if self.recovery_config.halt_on_orphan_positions:
            for position in await self.api.list_positions():
                if position.is_flat:
                    continue
                key = self._position_key_from_snapshot(position)
                if key not in managed_positions:
                    messages.append(
                        f"orphan position: {position.symbol}:{position.position_side} "
                        f"qty={position.quantity}"
                    )

        if self.recovery_config.halt_on_orphan_orders:
            known_clients = self._known_client_ids(
                await self.control.list_nonterminal()
            )
            for kind in (OrderKind.REGULAR, OrderKind.ALGO):
                for order in await self.api.list_open_orders(None, kind):
                    if order.client_order_id not in known_clients:
                        messages.append(
                            f"orphan {kind.value.lower()} order: "
                            f"{order.symbol}:{order.client_order_id}"
                        )
        return messages

    @staticmethod
    def _entry_identifiers(payload: Any) -> list[tuple[str, str | None]]:
        if not isinstance(payload, dict):
            return []
        result: list[tuple[str, str | None]] = []
        for name in (
            "maker_request",
            "maker_order",
            "maker_final",
            "ioc_request",
            "ioc_order",
            "ioc_final",
            "recovered_entry_order",
        ):
            item = payload.get(name)
            if not isinstance(item, dict):
                continue
            client = item.get("client_order_id")
            if client:
                result.append((str(client), None if not item.get("order_id") else str(item["order_id"])))
        # Preserve order while removing duplicate client IDs.
        return list(dict.fromkeys(result))

    @classmethod
    def _known_client_ids(cls, records: Any) -> set[str]:
        result: set[str] = set()

        def visit(value: Any, key: str = "") -> None:
            if isinstance(value, dict):
                for child_key, child in value.items():
                    if child_key.endswith("client_order_id") and child:
                        result.add(str(child))
                    else:
                        visit(child, child_key)
            elif isinstance(value, (list, tuple)):
                for child in value:
                    visit(child, key)

        for record in records:
            visit(record.payload)
        return result

    @staticmethod
    def _validate_position_direction(
        record: ExecutionRecord, position: PositionSnapshot
    ) -> None:
        if position.position_mode is PositionMode.ONEWAY:
            wrong = (
                record.action is Action.LONG and position.quantity < ZERO
            ) or (record.action is Action.SHORT and position.quantity > ZERO)
            if wrong:
                raise CriticalExecutionError(
                    f"Recovered one-way position points opposite to saga action: "
                    f"{position.quantity}"
                )
        elif position.position_side != record.action.value:
            raise CriticalExecutionError(
                f"Recovered hedge side {position.position_side} does not match "
                f"{record.action.value}"
            )

    @staticmethod
    def _position_key(position: PositionSnapshot, action: Action) -> tuple[str, str]:
        return (
            position.symbol,
            action.value
            if position.position_mode is PositionMode.HEDGE
            else "BOTH",
        )

    @staticmethod
    def _position_key_from_snapshot(position: PositionSnapshot) -> tuple[str, str]:
        return (
            position.symbol,
            position.position_side
            if position.position_mode is PositionMode.HEDGE
            else "BOTH",
        )
