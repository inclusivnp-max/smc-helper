from __future__ import annotations

import asyncio
import hashlib
import time
import uuid
from dataclasses import asdict, dataclass, field
from typing import Any, Mapping

from .models import (
    ZERO,
    D,
    ExecutionRecord,
    OrderKind,
    OrderRequest,
    OrderSnapshot,
    PositionMode,
    PositionSnapshot,
    Side,
    encode_json,
)
from .live_guard import LIVE_CAPITAL_STATUS
from .modes import TradingMode, TradingModeController
from .operator_store import OperatorStore
from .ports import ControlPlane, ExchangeAPI
from .runtime import TradingRuntime


@dataclass(frozen=True)
class OperatorSettings:
    token: str = field(repr=False)
    symbols: tuple[str, ...] = ("BTCUSDT",)
    testnet: bool = True
    title: str = "SMC Execution Control"
    actor_name: str = "operator"
    telemetry_interval_seconds: float = 5.0
    snapshot_cache_seconds: float = 3.0
    orders_cache_seconds: float = 10.0
    telemetry_retention_days: int = 30
    flatten_plan_ttl_seconds: int = 30
    allow_flatten_command: bool = True

    def __post_init__(self) -> None:
        if len(self.token) < 20:
            raise ValueError("Operator token must contain at least 20 characters")
        if not self.symbols:
            raise ValueError("At least one symbol is required")
        if self.telemetry_interval_seconds < 1:
            raise ValueError("telemetry_interval_seconds must be >= 1")
        if not (0.1 <= self.snapshot_cache_seconds <= 30):
            raise ValueError("snapshot_cache_seconds must be in [0.1, 30]")
        if not (1.0 <= self.orders_cache_seconds <= 60):
            raise ValueError("orders_cache_seconds must be in [1, 60]")


@dataclass(frozen=True)
class FlattenTarget:
    symbol: str
    position_side: str
    position_mode: PositionMode
    quantity: D
    entry_price: D
    mark_price: D

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class FlattenPlan:
    plan_id: str
    created_at_ms: int
    expires_at_ms: int
    targets: tuple[FlattenTarget, ...]
    regular_orders_to_cancel: int
    algo_orders_to_cancel_after_flat: int
    confirmation_phrase: str
    snapshot_hash: str

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


class OperatorCommandError(RuntimeError):
    pass


class OperatorService:
    def __init__(
        self,
        runtime: TradingRuntime,
        mode_controller: TradingModeController,
        store: OperatorStore,
        settings: OperatorSettings,
        research_service: Any | None = None,
        strategy_automation: Any | None = None,
        live_authorization: Any | None = None,
    ) -> None:
        self.runtime = runtime
        self.api: ExchangeAPI = runtime.api
        self.control: ControlPlane = runtime.control
        self.mode_controller = mode_controller
        self.store = store
        self.settings = settings
        self.research = research_service
        self.automation = strategy_automation
        self.live_authorization = live_authorization
        self.automation_start_error: str | None = None
        self._plans: dict[str, FlattenPlan] = {}
        self._plan_lock = asyncio.Lock()
        self._telemetry_stop = asyncio.Event()
        self._snapshot_lock = asyncio.Lock()
        self._snapshot_cache: dict[str, Any] | None = None
        self._snapshot_cached_at = 0.0
        self._orders_lock = asyncio.Lock()
        self._orders_cache: dict[OrderKind, list[OrderSnapshot]] = {}
        self._orders_cached_at: dict[OrderKind, float] = {}

    async def initialize(self) -> None:
        await self.store.initialize()
        await self.mode_controller.initialize()
        if self.research is not None:
            await self.research.initialize()
        if not self.settings.testnet:
            if self.live_authorization is None:
                raise RuntimeError("LIVE startup requires a verified live authorization")
            if self.research is None:
                raise RuntimeError("LIVE startup requires the durable research store")
            self.live_authorization.validate_active_risk_profile(
                self.mode_controller.active_profile.mode.value
            )
            await self.live_authorization.validate_research(self.research.store)

    async def status_snapshot(self, *, force: bool = False) -> dict[str, Any]:
        now = time.monotonic()
        if (
            not force
            and self._snapshot_cache is not None
            and now - self._snapshot_cached_at < self.settings.snapshot_cache_seconds
        ):
            return dict(self._snapshot_cache)
        async with self._snapshot_lock:
            now = time.monotonic()
            if (
                not force
                and self._snapshot_cache is not None
                and now - self._snapshot_cached_at < self.settings.snapshot_cache_seconds
            ):
                return dict(self._snapshot_cache)
            account, positions, risk_totals, admission, healthy, regular, algo, executions = (
                await asyncio.gather(
                    self.api.get_account_snapshot(),
                    self.api.list_positions(),
                    self.control.risk_totals(),
                    self.control.admission_status(),
                    self.api.is_user_stream_healthy(),
                    self._list_configured_open_orders(OrderKind.REGULAR),
                    self._list_configured_open_orders(OrderKind.ALGO),
                    self.control.list_executions(100),
                )
            )
            open_positions = [position for position in positions if not position.is_flat]
            unrealized = sum((self._unrealized(position) for position in open_positions), ZERO)
            governor_state = await self.control.get_system_value("portfolio_governor") or {}
            last_report = self.runtime.last_reconciliation_report
            snapshot = encode_json(
                {
                    "server_time_ms": int(time.time() * 1000),
                    "runtime_started": self.runtime.is_started,
                    "testnet": self.settings.testnet,
                    "live_capital_status": LIVE_CAPITAL_STATUS,
                    "symbols": self.settings.symbols,
                    "mode": {
                        **self.mode_controller.current.to_dict(),
                        "effective_profile": self.mode_controller.active_profile.to_dict(),
                    },
                    "admission": {"open": admission[0], "reason": admission[1]},
                    "user_stream_healthy": healthy,
                    "account": {
                        "equity": account.equity,
                        "available_balance": account.available_balance,
                        "current_open_risk": account.current_open_risk,
                        "position_mode": account.position_mode,
                        "risk_halt": account.risk_halt,
                        "risk_halt_reason": account.risk_halt_reason,
                    },
                    "risk_ledger": dict(risk_totals),
                    "unrealized_pnl": unrealized,
                    "counts": {
                        "open_positions": len(open_positions),
                        "regular_orders": len(regular),
                        "algo_orders": len(algo),
                        "nonterminal_executions": sum(1 for item in executions if not item.terminal),
                    },
                    "governor": dict(governor_state),
                    "last_reconciliation": None
                    if last_report is None
                    else asdict(last_report),
                    "strategy_automation": (
                        {"enabled": False}
                        if self.automation is None
                        else {
                            **self.automation.status(),
                            "start_error": self.automation_start_error,
                        }
                    ),
                    "live_authorization": (
                        None
                        if self.live_authorization is None
                        else self.live_authorization.summary()
                    ),
                }
            )
            self._snapshot_cache = dict(snapshot)
            self._snapshot_cached_at = time.monotonic()
            return dict(snapshot)

    def invalidate_snapshot(self, *, include_orders: bool = False) -> None:
        self._snapshot_cache = None
        self._snapshot_cached_at = 0.0
        if include_orders:
            self._orders_cache.clear()
            self._orders_cached_at.clear()

    async def _list_configured_open_orders(
        self,
        kind: OrderKind,
        *,
        force: bool = False,
        symbols: tuple[str, ...] | None = None,
    ) -> list[OrderSnapshot]:
        selected_symbols = tuple(
            dict.fromkeys(
                symbol.upper() for symbol in (symbols or self.settings.symbols)
            )
        )
        use_shared_cache = symbols is None
        now = time.monotonic()
        if (
            use_shared_cache
            and not force
            and kind in self._orders_cache
            and now - self._orders_cached_at.get(kind, 0.0) < self.settings.orders_cache_seconds
        ):
            return list(self._orders_cache[kind])

        async with self._orders_lock:
            now = time.monotonic()
            if (
                use_shared_cache
                and not force
                and kind in self._orders_cache
                and now - self._orders_cached_at.get(kind, 0.0) < self.settings.orders_cache_seconds
            ):
                return list(self._orders_cache[kind])

            groups = await asyncio.gather(
                *(self.api.list_open_orders(symbol, kind) for symbol in selected_symbols)
            )
            deduplicated: dict[tuple[str, str], OrderSnapshot] = {}
            selected = set(selected_symbols)
            for group in groups:
                for order in group:
                    if order.symbol.upper() not in selected or not order.status.active:
                        continue
                    identity = (order.order_id or "", order.client_order_id)
                    deduplicated[identity] = order
            result = sorted(
                deduplicated.values(), key=lambda item: item.update_time_ms, reverse=True
            )
            if use_shared_cache:
                self._orders_cache[kind] = list(result)
                self._orders_cached_at[kind] = time.monotonic()
            return result

    async def list_positions(self) -> list[dict[str, Any]]:
        positions = await self.api.list_positions()
        return [
            encode_json(
                {
                    **asdict(position),
                    "unrealized_pnl": self._unrealized(position),
                    "notional": position.abs_qty * position.mark_price,
                }
            )
            for position in positions
            if not position.is_flat
        ]

    async def list_orders(self, kind: OrderKind | None = None) -> list[dict[str, Any]]:
        kinds = (kind,) if kind is not None else (OrderKind.REGULAR, OrderKind.ALGO)
        result: list[dict[str, Any]] = []
        for selected in kinds:
            for order in await self._list_configured_open_orders(selected):
                result.append(self._order_dict(order))
        result.sort(key=lambda item: int(item["update_time_ms"]), reverse=True)
        return result

    async def list_executions(self, limit: int = 100) -> list[dict[str, Any]]:
        return [self._execution_dict(item) for item in await self.control.list_executions(limit)]

    async def get_execution(self, execution_id: str) -> dict[str, Any] | None:
        record = await self.control.get_execution(execution_id)
        return None if record is None else self._execution_dict(record)

    async def _begin_command(
        self, *, actor: str, action: str, idempotency_key: str
    ) -> dict[str, Any] | None:
        record, created = await self.store.claim_command(
            actor=actor, action=action, idempotency_key=idempotency_key
        )
        if created:
            return None
        if record.get("action") != action:
            raise OperatorCommandError(
                "Idempotency-Key was already used for a different operator action"
            )
        if record["outcome"] == "IN_PROGRESS":
            raise OperatorCommandError("Command with this Idempotency-Key is already in progress")
        if record["outcome"] == "FAILED":
            raise OperatorCommandError(
                str(record.get("details", {}).get("error", "Previous command attempt failed"))
            )
        return dict(record["details"])

    async def _complete_command(
        self, *, idempotency_key: str, outcome: str, details: Mapping[str, Any]
    ) -> dict[str, Any]:
        await self.store.complete_command(
            idempotency_key=idempotency_key,
            outcome=outcome,
            details=details,
        )
        return dict(details)

    async def _fail_command(self, idempotency_key: str, exc: Exception) -> None:
        await self.store.complete_command(
            idempotency_key=idempotency_key,
            outcome="FAILED",
            details={"error": repr(exc)},
        )

    async def halt(self, *, actor: str, reason: str, idempotency_key: str) -> dict[str, Any]:
        cached = await self._begin_command(
            actor=actor, action="HALT_ADMISSION", idempotency_key=idempotency_key
        )
        if cached is not None:
            return cached
        try:
            await self.control.halt_admission(f"operator halt by {actor}: {reason}")
            details = {"status": "HALTED", "reason": reason}
            self.invalidate_snapshot(include_orders=True)
            return await self._complete_command(
                idempotency_key=idempotency_key,
                outcome="SUCCESS",
                details=details,
            )
        except Exception as exc:
            await self._fail_command(idempotency_key, exc)
            raise

    async def reconcile(
        self,
        *,
        actor: str,
        resume_on_success: bool,
        idempotency_key: str,
    ) -> dict[str, Any]:
        action = "RECONCILE_AND_RESUME" if resume_on_success else "RECONCILE"
        cached = await self._begin_command(
            actor=actor, action=action, idempotency_key=idempotency_key
        )
        if cached is not None:
            return cached
        try:
            report = await self.runtime.recovery.recover_startup(
                resume_on_success=resume_on_success
            )
            self.runtime.last_reconciliation_report = report
            details = encode_json(asdict(report))
            self.invalidate_snapshot(include_orders=True)
            return await self._complete_command(
                idempotency_key=idempotency_key,
                outcome="SUCCESS" if report.unresolved == 0 else "UNRESOLVED",
                details=details,
            )
        except Exception as exc:
            await self._fail_command(idempotency_key, exc)
            raise

    async def switch_mode(
        self,
        *,
        actor: str,
        mode: TradingMode,
        idempotency_key: str,
    ) -> dict[str, Any]:
        cached = await self._begin_command(
            actor=actor, action="SWITCH_MODE", idempotency_key=idempotency_key
        )
        if cached is not None:
            return cached
        try:
            profile = await self.mode_controller.switch(mode, actor=actor)
            report: Mapping[str, Any] | None = None
            if mode is not TradingMode.PAUSED:
                recovered = await self.runtime.recovery.recover_startup(resume_on_success=True)
                self.runtime.last_reconciliation_report = recovered
                report = encode_json(asdict(recovered))
                if recovered.unresolved:
                    raise OperatorCommandError(
                        f"Mode applied but recovery unresolved={recovered.unresolved}; admission remains closed"
                    )
            details = {"profile": profile.to_dict(), "reconciliation": report}
            self.invalidate_snapshot(include_orders=True)
            return await self._complete_command(
                idempotency_key=idempotency_key,
                outcome="SUCCESS",
                details=details,
            )
        except Exception as exc:
            await self._fail_command(idempotency_key, exc)
            raise

    async def create_flatten_plan(
        self, *, actor: str, symbol: str | None = None
    ) -> FlattenPlan:
        if not self.settings.allow_flatten_command:
            raise OperatorCommandError("Flatten command is disabled by configuration")
        positions = [
            position
            for position in await self.api.list_positions()
            if not position.is_flat
            and (symbol is None or position.symbol == symbol.upper())
        ]
        if not positions:
            raise OperatorCommandError("No matching open positions")
        targets = tuple(
            FlattenTarget(
                symbol=item.symbol,
                position_side=item.position_side,
                position_mode=item.position_mode,
                quantity=item.quantity,
                entry_price=item.entry_price,
                mark_price=item.mark_price,
            )
            for item in positions
        )
        target_symbols = {item.symbol for item in targets}
        regular = [
            order
            for order in await self._list_configured_open_orders(
                OrderKind.REGULAR, force=True, symbols=tuple(sorted(target_symbols))
            )
            if order.symbol in target_symbols
        ]
        algo = [
            order
            for order in await self._list_configured_open_orders(
                OrderKind.ALGO, force=True, symbols=tuple(sorted(target_symbols))
            )
            if order.symbol in target_symbols
        ]
        now = int(time.time() * 1000)
        plan_id = uuid.uuid4().hex
        phrase = f"FLATTEN {len(targets)} POSITION{'S' if len(targets) != 1 else ''}"
        snapshot_hash = self._targets_hash(targets)
        plan = FlattenPlan(
            plan_id=plan_id,
            created_at_ms=now,
            expires_at_ms=now + self.settings.flatten_plan_ttl_seconds * 1000,
            targets=targets,
            regular_orders_to_cancel=len(regular),
            algo_orders_to_cancel_after_flat=len(algo),
            confirmation_phrase=phrase,
            snapshot_hash=snapshot_hash,
        )
        async with self._plan_lock:
            self._plans[plan_id] = plan
            self._expire_plans(now)
        await self.store.append_audit(
            actor=actor,
            action="CREATE_FLATTEN_PLAN",
            outcome="PLANNED",
            details=plan.to_dict(),
        )
        return plan

    async def confirm_flatten(
        self,
        *,
        actor: str,
        plan_id: str,
        confirmation_phrase: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        cached = await self._begin_command(
            actor=actor, action="CONFIRM_FLATTEN", idempotency_key=idempotency_key
        )
        if cached is not None:
            return cached
        try:
            async with self._plan_lock:
                plan = self._plans.get(plan_id)
            if plan is None:
                raise OperatorCommandError("Unknown or expired flatten plan")
            now = int(time.time() * 1000)
            if now > plan.expires_at_ms:
                raise OperatorCommandError("Flatten plan expired")
            if confirmation_phrase.strip() != plan.confirmation_phrase:
                raise OperatorCommandError("Confirmation phrase does not match")

            current_positions = [
                position
                for position in await self.api.list_positions()
                if not position.is_flat and position.symbol in {x.symbol for x in plan.targets}
            ]
            current_targets = tuple(
                FlattenTarget(
                    symbol=item.symbol,
                    position_side=item.position_side,
                    position_mode=item.position_mode,
                    quantity=item.quantity,
                    entry_price=item.entry_price,
                    mark_price=item.mark_price,
                )
                for item in current_positions
            )
            if self._targets_hash(current_targets) != plan.snapshot_hash:
                raise OperatorCommandError(
                    "Position state changed after planning; create a new flatten plan"
                )

            await self.control.halt_admission(f"operator flatten in progress: {actor}")
            target_symbols = {target.symbol for target in plan.targets}
            cancelled_regular: list[str] = []
            for order in await self._list_configured_open_orders(
                OrderKind.REGULAR, force=True, symbols=tuple(sorted(target_symbols))
            ):
                if order.symbol not in target_symbols:
                    continue
                await self.api.cancel_order(
                    order.symbol, order.order_id, order.client_order_id, OrderKind.REGULAR
                )
                cancelled_regular.append(order.client_order_id)

            close_orders: list[dict[str, Any]] = []
            for target in plan.targets:
                is_long = (
                    target.position_side == "LONG"
                    if target.position_mode is PositionMode.HEDGE
                    else target.quantity > ZERO
                )
                request = OrderRequest(
                    symbol=target.symbol,
                    side=Side.SELL if is_long else Side.BUY,
                    order_type="MARKET",
                    client_order_id=f"op-{uuid.uuid4().hex[:28]}",
                    kind=OrderKind.REGULAR,
                    quantity=abs(target.quantity),
                    reduce_only=target.position_mode is PositionMode.ONEWAY,
                    position_side=(
                        target.position_side
                        if target.position_mode is PositionMode.HEDGE
                        else "BOTH"
                    ),
                )
                closed = await self.api.place_order(request)
                close_orders.append(self._order_dict(closed))

            remaining = [
                position
                for position in await self.api.list_positions()
                if not position.is_flat and position.symbol in target_symbols
            ]
            if remaining:
                raise OperatorCommandError(
                    "Flatten did not prove all positions flat; protective algo orders were preserved"
                )

            cancelled_algo: list[str] = []
            for order in await self._list_configured_open_orders(
                OrderKind.ALGO, force=True, symbols=tuple(sorted(target_symbols))
            ):
                if order.symbol not in target_symbols:
                    continue
                await self.api.cancel_order(
                    order.symbol, order.order_id, order.client_order_id, OrderKind.ALGO
                )
                cancelled_algo.append(order.client_order_id)

            await self.runtime.supervisor.reconcile_once()
            self.invalidate_snapshot(include_orders=True)
            details = {
                "status": "FLATTENED",
                "plan_id": plan.plan_id,
                "close_orders": close_orders,
                "cancelled_regular": cancelled_regular,
                "cancelled_algo": cancelled_algo,
                "admission_remains_closed": True,
            }
            async with self._plan_lock:
                self._plans.pop(plan_id, None)
            return await self._complete_command(
                idempotency_key=idempotency_key,
                outcome="SUCCESS",
                details=details,
            )

        except Exception as exc:
            await self._fail_command(idempotency_key, exc)
            raise

    async def telemetry_loop(self) -> None:
        self._telemetry_stop.clear()
        while not self._telemetry_stop.is_set():
            try:
                snapshot = await self.status_snapshot()
                await self.store.record_telemetry(
                    {
                        "ts_ms": snapshot["server_time_ms"],
                        "equity": snapshot["account"]["equity"],
                        "available_balance": snapshot["account"]["available_balance"],
                        "open_risk": snapshot["account"]["current_open_risk"],
                        "reserved_risk": snapshot["risk_ledger"]["reserved"],
                        "committed_risk": snapshot["risk_ledger"]["committed"],
                        "unrealized_pnl": snapshot["unrealized_pnl"],
                        "admission_open": snapshot["admission"]["open"],
                        "mode": snapshot["mode"]["mode"],
                        "user_stream_healthy": snapshot["user_stream_healthy"],
                    }
                )
                cutoff = int(time.time() * 1000) - self.settings.telemetry_retention_days * 86_400_000
                await self.store.prune_telemetry(cutoff)
            except Exception:
                # Runtime health monitor remains the authority for admission. Dashboard
                # telemetry failures must never alter exchange state by themselves.
                pass
            try:
                await asyncio.wait_for(
                    self._telemetry_stop.wait(),
                    timeout=self.settings.telemetry_interval_seconds,
                )
            except TimeoutError:
                pass

    def stop_telemetry(self) -> None:
        self._telemetry_stop.set()

    @staticmethod
    def _unrealized(position: PositionSnapshot) -> D:
        if position.entry_price <= ZERO or position.mark_price <= ZERO:
            return ZERO
        return position.quantity * (position.mark_price - position.entry_price)

    @staticmethod
    def _order_dict(order: OrderSnapshot) -> dict[str, Any]:
        return encode_json(asdict(order))

    @staticmethod
    def _execution_dict(record: ExecutionRecord) -> dict[str, Any]:
        return encode_json(asdict(record))

    @staticmethod
    def _targets_hash(targets: tuple[FlattenTarget, ...]) -> str:
        canonical = "|".join(
            f"{item.symbol}:{item.position_side}:{item.position_mode.value}:{item.quantity}"
            for item in sorted(targets, key=lambda x: (x.symbol, x.position_side))
        )
        return hashlib.sha256(canonical.encode()).hexdigest()

    def _expire_plans(self, now_ms: int) -> None:
        for plan_id in [
            key for key, value in self._plans.items() if value.expires_at_ms < now_ms
        ]:
            self._plans.pop(plan_id, None)
