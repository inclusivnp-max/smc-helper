from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from collections.abc import Mapping
from dataclasses import asdict
from typing import Any

from .config import ExecutionConfig
from .deadline import DeadlineBudget
from .errors import (
    AmbiguousSubmitError,
    CriticalExecutionError,
    DeadlineExceeded,
    ExchangeReject,
    ExecutionError,
    OrderNotFound,
)
from .fills import FillAccumulator
from .models import (
    BPS,
    ONE,
    ZERO,
    AccountSnapshot,
    Action,
    D,
    ExecutionResult,
    ExecutionStage,
    OrderKind,
    OrderRequest,
    OrderSnapshot,
    OrderStatus,
    PartialFillPolicy,
    PositionMode,
    ProtectionOrders,
    Quote,
    Side,
    Signal,
    parse_signal,
    SymbolRules,
    ceil_to_step,
    encode_json,
    floor_to_step,
)
from .ports import ControlPlane, ExchangeAPI
from .quality import ExecutionQualityGuard

log = logging.getLogger(__name__)


class ExecutionEngine:
    def __init__(
        self,
        exchange_api: ExchangeAPI,
        control_plane: ControlPlane,
        config: ExecutionConfig = ExecutionConfig(),
        quality_guard: ExecutionQualityGuard | None = None,
    ) -> None:
        self.api = exchange_api
        self.control = control_plane
        self.config = config
        self.quality_guard = quality_guard or ExecutionQualityGuard(config)
        self._symbol_locks: dict[str, asyncio.Lock] = {}
        self._symbol_locks_guard = asyncio.Lock()
        self._global_semaphore = asyncio.Semaphore(config.max_concurrent_symbols)

    async def execute_signal(
        self, raw_signal: Signal | Mapping[str, Any]
    ) -> ExecutionResult:
        signal = self.parse_signal(raw_signal)
        early = await self._preclaim_rejection(signal)
        if early is not None:
            return early

        record, created = await self.control.claim(signal)
        if not created:
            if record.result is not None:
                return ExecutionResult.from_dict(record.result)
            return self._empty_result(
                "DUPLICATE_ACTIVE_RECOVERY_REQUIRED",
                signal,
                warnings=(f"Existing execution stage: {record.stage.value}",),
            )

        async with self._global_semaphore:
            lock = await self._get_symbol_lock(signal.symbol)
            async with lock:
                try:
                    result = await self._execute_claimed(signal)
                except CriticalExecutionError as exc:
                    log.exception("Critical execution uncertainty for %s", signal.signal_id)
                    await self.control.transition(
                        signal.signal_id,
                        expected=None,
                        new_stage=ExecutionStage.FAILED_UNCERTAIN,
                        patch={"critical_error": repr(exc)},
                    )
                    await self.control.halt_admission(
                        f"critical execution uncertainty: {signal.signal_id}: {exc}"
                    )
                    result = self._empty_result(
                        "FAILED_UNCERTAIN", signal, warnings=(str(exc),)
                    )
                except (ExecutionError, DeadlineExceeded) as exc:
                    record = await self.control.get_execution(signal.signal_id)
                    stage = record.stage if record is not None else ExecutionStage.FAILED_UNCERTAIN
                    provably_safe = stage in {
                        ExecutionStage.CLAIMED,
                        ExecutionStage.PRECHECKED,
                        ExecutionStage.RISK_RESERVED,
                    } or (
                        stage is ExecutionStage.ENTRY_SUBMITTING
                        and isinstance(exc, ExchangeReject)
                    )
                    if provably_safe:
                        log.warning("Safe execution failure for %s: %s", signal.signal_id, exc)
                        await self.control.release_risk(signal.signal_id)
                        await self.control.transition(
                            signal.signal_id,
                            expected=None,
                            new_stage=ExecutionStage.FAILED_SAFE,
                            patch={"safe_error": repr(exc)},
                        )
                        result = self._empty_result(
                            "FAILED_SAFE", signal, warnings=(str(exc),)
                        )
                    else:
                        log.exception(
                            "Failure after possible exchange exposure for %s", signal.signal_id
                        )
                        await self.control.transition(
                            signal.signal_id,
                            expected=None,
                            new_stage=ExecutionStage.FAILED_UNCERTAIN,
                            patch={"post_submit_error": repr(exc), "stage_at_error": stage.value},
                        )
                        await self.control.halt_admission(
                            f"post-submit execution uncertainty: {signal.signal_id}: {exc}"
                        )
                        result = self._empty_result(
                            "FAILED_UNCERTAIN", signal, warnings=(str(exc),)
                        )
                except Exception as exc:
                    log.exception("Unhandled execution error for %s", signal.signal_id)
                    await self.control.transition(
                        signal.signal_id,
                        expected=None,
                        new_stage=ExecutionStage.FAILED_UNCERTAIN,
                        patch={"unhandled_error": repr(exc)},
                    )
                    await self.control.halt_admission(
                        f"unhandled execution failure: {signal.signal_id}: {exc!r}"
                    )
                    result = self._empty_result(
                        "FAILED_UNCERTAIN", signal, warnings=(repr(exc),)
                    )

        await self._observe_quality(result)
        return result

    async def _preclaim_rejection(self, signal: Signal) -> ExecutionResult | None:
        now_ms = int(time.time() * 1000)
        if now_ms - signal.created_at_ms > self.config.max_signal_age_ms:
            return self._empty_result(
                "REJECTED_STALE_SIGNAL",
                signal,
                warnings=("Signal exceeded max_signal_age_ms",),
            )
        if signal.created_at_ms - now_ms > self.config.quote_future_tolerance_ms:
            return self._empty_result(
                "REJECTED_FUTURE_SIGNAL",
                signal,
                warnings=("Signal timestamp is implausibly in the future",),
            )
        if signal.confidence < self.config.min_confidence:
            return self._empty_result("REJECTED_LOW_CONFIDENCE", signal)
        admission_open, reason = await self.control.admission_status()
        if not admission_open:
            return self._empty_result(
                "REJECTED_ADMISSION_HALTED",
                signal,
                warnings=(reason or "Admission gate is closed",),
            )
        if self.config.admission_require_user_stream:
            healthy = await self.api.is_user_stream_healthy()
            if not healthy:
                await self.control.halt_admission("private user stream is unhealthy")
                return self._empty_result(
                    "REJECTED_USER_STREAM_UNHEALTHY",
                    signal,
                    warnings=("Private order/position stream is not healthy",),
                )
        return None

    async def _execute_claimed(self, signal: Signal) -> ExecutionResult:
        started = time.monotonic()
        entry_budget = DeadlineBudget(
            self.config.total_entry_timeout_ms, label=f"entry:{signal.signal_id}"
        )
        warnings: list[str] = []

        rules, initial_quote, account = await entry_budget.run(
            asyncio.gather(
                self.api.get_symbol_rules(signal.symbol),
                self.api.get_quote(signal.symbol),
                self.api.get_account_snapshot(),
            ),
            operation="initial preflight",
            cap_ms=self.config.default_io_timeout_ms,
        )
        self._validate_rules_and_quote(signal.symbol, rules, initial_quote)
        arrival_price = (
            initial_quote.ask if signal.action is Action.LONG else initial_quote.bid
        )
        if account.risk_halt:
            return await self._finish(
                signal,
                ExecutionStage.REJECTED,
                self._result(
                    "REJECTED_RISK_HALT",
                    signal,
                    ZERO,
                    FillAccumulator(ZERO),
                    arrival_price,
                    started,
                    warnings=(account.risk_halt_reason or "Account risk halt",),
                ),
            )

        atr = await entry_budget.run(
            self._get_atr(signal.symbol),
            operation="ATR snapshot",
            cap_ms=self.config.default_io_timeout_ms,
        )
        stop_distance = self._stop_distance(arrival_price, atr)
        effective_loss_per_unit = self._effective_loss_per_unit(
            arrival_price, stop_distance
        )
        requested_qty = self._calculate_position_size(
            account=account,
            rules=rules,
            entry_price=arrival_price,
            effective_loss_per_unit=effective_loss_per_unit,
        )
        if requested_qty <= ZERO:
            return await self._finish(
                signal,
                ExecutionStage.REJECTED,
                self._result(
                    "REJECTED_RISK_OR_FILTERS",
                    signal,
                    ZERO,
                    FillAccumulator(ZERO),
                    arrival_price,
                    started,
                    warnings=("Calculated quantity is below risk/exchange minimums",),
                ),
            )

        position = await entry_budget.run(
            self.api.get_position(signal.symbol, signal.action),
            operation="position precheck",
            cap_ms=self.config.default_io_timeout_ms,
        )
        if not position.is_flat:
            if (
                account.position_mode is PositionMode.ONEWAY
                and self.config.one_way_policy.value == "CLOSE_THEN_OPEN"
            ):
                existing_action = Action.LONG if position.quantity > ZERO else Action.SHORT
                close_warning = await self._emergency_close(
                    symbol=signal.symbol,
                    action=existing_action,
                    quantity=position.abs_qty,
                    rules=rules,
                    position_mode=account.position_mode,
                )
                if close_warning:
                    raise CriticalExecutionError(
                        f"Could not flatten existing one-way position: {close_warning}"
                    )
            else:
                return await self._finish(
                    signal,
                    ExecutionStage.REJECTED,
                    self._result(
                        "REJECTED_EXISTING_POSITION",
                        signal,
                        requested_qty,
                        FillAccumulator(requested_qty),
                        arrival_price,
                        started,
                        warnings=(
                            f"Existing position qty={position.quantity} "
                            f"side={position.position_side}",
                        ),
                    ),
                )

        planned_risk = requested_qty * effective_loss_per_unit
        reservation_amount = planned_risk * (
            ONE + self.config.risk_reservation_buffer_pct
        )
        mode_state = await self.control.get_system_value("trading_mode")
        await self.control.transition(
            signal.signal_id,
            expected={ExecutionStage.CLAIMED},
            new_stage=ExecutionStage.PRECHECKED,
            patch={
                "trading_mode": str((mode_state or {}).get("mode", "UNSPECIFIED")),
                "arrival_price": str(arrival_price),
                "atr": str(atr),
                "planned_stop_distance": str(stop_distance),
                "requested_qty": str(requested_qty),
                "position_mode": account.position_mode.value,
                "rules": self._rules_dict(rules),
            },
        )
        reservation = await self.control.reserve_risk(
            signal.signal_id,
            reservation_amount,
            max_total_risk=account.equity * self.config.max_portfolio_risk_pct,
            external_open_risk=account.current_open_risk,
        )
        if reservation is None:
            return await self._finish(
                signal,
                ExecutionStage.REJECTED,
                self._result(
                    "REJECTED_ATOMIC_PORTFOLIO_RISK",
                    signal,
                    requested_qty,
                    FillAccumulator(requested_qty),
                    arrival_price,
                    started,
                    warnings=("Atomic portfolio-risk reservation was denied",),
                ),
            )
        await self.control.transition(
            signal.signal_id,
            expected={ExecutionStage.PRECHECKED},
            new_stage=ExecutionStage.RISK_RESERVED,
            patch={"risk_reserved": str(reservation.amount)},
        )

        # Revalidate the executable quote immediately before order submission.
        quote = await entry_budget.run(
            self.api.get_quote(signal.symbol),
            operation="pre-submit quote refresh",
            cap_ms=self.config.default_io_timeout_ms,
        )
        self._validate_rules_and_quote(signal.symbol, rules, quote)
        adverse = self._adverse_slippage_bps(signal.action, arrival_price, quote)
        if adverse > self.config.max_adverse_slippage_bps:
            await self.control.release_risk(signal.signal_id)
            return await self._finish(
                signal,
                ExecutionStage.ABORTED,
                self._result(
                    "ABORTED_PRICE_ESCAPED_BEFORE_SUBMIT",
                    signal,
                    requested_qty,
                    FillAccumulator(requested_qty),
                    arrival_price,
                    started,
                    warnings=(f"Adverse move {adverse} bps",),
                ),
            )

        fills = FillAccumulator(requested_qty=requested_qty)
        ioc_final: OrderSnapshot | None = None
        maker_request = self._maker_request(
            signal, requested_qty, quote, rules, account.position_mode
        )
        await self.control.transition(
            signal.signal_id,
            expected={ExecutionStage.RISK_RESERVED},
            new_stage=ExecutionStage.ENTRY_SUBMITTING,
            patch={"maker_request": self._request_dict(maker_request)},
        )
        maker_order = await self._submit_order(
            maker_request, entry_budget, operation="maker order submit"
        )
        await self.control.transition(
            signal.signal_id,
            expected={ExecutionStage.ENTRY_SUBMITTING},
            new_stage=ExecutionStage.ENTRY_OPEN,
            patch={"maker_order": self._order_dict(maker_order)},
        )
        maker_final = await self._await_or_cancel(
            maker_order,
            entry_budget=entry_budget,
            phase_timeout_ms=self.config.maker_timeout_ms,
        )
        fills.add_terminal_snapshot(maker_final, maker=True)

        if fills.remaining_qty > ZERO and not entry_budget.expired:
            current_quote = await entry_budget.run(
                self.api.get_quote(signal.symbol),
                operation="IOC quote refresh",
                cap_ms=self.config.default_io_timeout_ms,
            )
            self._validate_rules_and_quote(signal.symbol, rules, current_quote)
            adverse = self._adverse_slippage_bps(
                signal.action, arrival_price, current_quote
            )
            if adverse <= self.config.max_adverse_slippage_bps:
                remaining = floor_to_step(fills.remaining_qty, rules.step_size)
                if remaining >= rules.min_qty and remaining * current_quote.mid >= rules.min_notional:
                    ioc_request = self._aggressive_request(
                        signal,
                        remaining,
                        arrival_price,
                        current_quote,
                        rules,
                        account.position_mode,
                    )
                    await self.control.transition(
                        signal.signal_id,
                        expected={ExecutionStage.ENTRY_OPEN},
                        new_stage=ExecutionStage.ENTRY_OPEN,
                        patch={
                            "maker_final": self._order_dict(maker_final),
                            "ioc_request": self._request_dict(ioc_request),
                        },
                    )
                    ioc_order = await self._submit_order(
                        ioc_request, entry_budget, operation="IOC submit"
                    )
                    ioc_final = await self._await_or_cancel(
                        ioc_order,
                        entry_budget=entry_budget,
                        phase_timeout_ms=max(1, entry_budget.remaining_ms),
                    )
                    fills.add_terminal_snapshot(ioc_final, maker=False)
                else:
                    warnings.append("Residual quantity is below exchange filters")
            else:
                warnings.append(
                    f"Adverse move {adverse} bps exceeded entry threshold"
                )

        await self.control.transition(
            signal.signal_id,
            expected={ExecutionStage.ENTRY_OPEN},
            new_stage=ExecutionStage.ENTRY_TERMINAL,
            patch={
                "maker_final": self._order_dict(maker_final),
                "ioc_final": None if ioc_final is None else self._order_dict(ioc_final),
                "fills": self._fills_dict(fills),
            },
        )

        if fills.executed_qty <= ZERO:
            await self.control.release_risk(signal.signal_id)
            return await self._finish(
                signal,
                ExecutionStage.ABORTED,
                self._result(
                    "CANCELLED_NO_FILL",
                    signal,
                    requested_qty,
                    fills,
                    arrival_price,
                    started,
                    warnings=tuple(warnings),
                ),
            )

        if (
            fills.fill_ratio < self.config.min_fill_ratio
            and self.config.partial_fill_policy is PartialFillPolicy.FLATTEN_BELOW_MIN
        ):
            await self.control.transition(
                signal.signal_id,
                expected={ExecutionStage.ENTRY_TERMINAL},
                new_stage=ExecutionStage.FLATTENING,
            )
            close_warning = await self._emergency_close(
                signal.symbol,
                signal.action,
                fills.executed_qty,
                rules,
                account.position_mode,
            )
            if close_warning:
                raise CriticalExecutionError(
                    f"Partial entry could not be flattened: {close_warning}"
                )
            await self.control.release_risk(signal.signal_id)
            return await self._finish(
                signal,
                ExecutionStage.ABORTED,
                self._result(
                    "ABORTED_PARTIAL_FLATTENED",
                    signal,
                    requested_qty,
                    fills,
                    arrival_price,
                    started,
                    warnings=tuple(warnings),
                ),
            )

        avg_price = fills.avg_price
        if avg_price is None:
            raise CriticalExecutionError("Positive fill without average execution price")
        actual_stop_distance = self._stop_distance(avg_price, atr)
        stop_price, tp_price = self._protection_prices(
            signal.action, avg_price, actual_stop_distance, atr, rules
        )
        actual_risk = fills.executed_qty * self._effective_loss_per_unit(
            avg_price, abs(avg_price - stop_price)
        )
        if actual_risk > reservation.amount:
            await self.control.transition(
                signal.signal_id,
                expected={ExecutionStage.ENTRY_TERMINAL},
                new_stage=ExecutionStage.FLATTENING,
                patch={
                    "actual_risk": str(actual_risk),
                    "risk_over_reservation": str(actual_risk - reservation.amount),
                },
            )
            close_warning = await self._emergency_close(
                signal.symbol,
                signal.action,
                fills.executed_qty,
                rules,
                account.position_mode,
            )
            if close_warning:
                raise CriticalExecutionError(
                    f"Risk exceeded reservation and flatten failed: {close_warning}"
                )
            await self.control.release_risk(signal.signal_id)
            return await self._finish(
                signal,
                ExecutionStage.ABORTED,
                self._result(
                    "ABORTED_RISK_RESERVATION_OVERRUN",
                    signal,
                    requested_qty,
                    fills,
                    arrival_price,
                    started,
                    warnings=("Actual entry risk exceeded atomic reservation",),
                ),
            )

        await self.control.commit_risk(signal.signal_id, actual_risk)
        await self.control.transition(
            signal.signal_id,
            expected={ExecutionStage.ENTRY_TERMINAL},
            new_stage=ExecutionStage.PROTECTION_PENDING,
            patch={
                "avg_price": str(avg_price),
                "executed_qty": str(fills.executed_qty),
                "actual_risk": str(actual_risk),
                "initial_stop_price": str(stop_price),
                "stop_price": str(stop_price),
                "take_profit_price": str(tp_price),
            },
        )

        protection_budget = DeadlineBudget(
            self.config.protection_timeout_ms,
            label=f"protection:{signal.signal_id}",
        )
        try:
            protection = await self._place_protection(
                signal,
                fills.executed_qty,
                stop_price,
                tp_price,
                account.position_mode,
                protection_budget,
            )
        except Exception as exc:
            log.exception("Protection failed; flattening %s", signal.signal_id)
            await self.control.transition(
                signal.signal_id,
                expected={ExecutionStage.PROTECTION_PENDING},
                new_stage=ExecutionStage.FLATTENING,
                patch={"protection_error": repr(exc)},
            )
            close_warning = await self._emergency_close(
                signal.symbol,
                signal.action,
                fills.executed_qty,
                rules,
                account.position_mode,
            )
            if close_warning:
                raise CriticalExecutionError(
                    f"Protection failed ({exc!r}); emergency close uncertain: "
                    f"{close_warning}"
                ) from exc
            await self.control.halt_admission(
                f"protective order failure for {signal.signal_id}: {exc}"
            )
            await self.control.release_risk(signal.signal_id)
            return await self._finish(
                signal,
                ExecutionStage.FAILED_SAFE,
                self._result(
                    "PROTECTION_FAILED_POSITION_CLOSED",
                    signal,
                    requested_qty,
                    fills,
                    arrival_price,
                    started,
                    warnings=(str(exc),),
                ),
            )

        if protection.take_profit_order_id is None:
            warnings.append("Stop active; take-profit was not accepted")
        result = self._result(
            "FILLED_PROTECTED"
            if fills.fill_ratio == ONE
            else "PARTIALLY_FILLED_PROTECTED",
            signal,
            requested_qty,
            fills,
            arrival_price,
            started,
            stop_price=stop_price,
            take_profit_price=tp_price,
            stop_order_id=protection.stop_order_id,
            take_profit_order_id=protection.take_profit_order_id,
            warnings=tuple(warnings),
        )
        await self.control.transition(
            signal.signal_id,
            expected={ExecutionStage.PROTECTION_PENDING},
            new_stage=ExecutionStage.PROTECTED,
            patch={"protection": encode_json(asdict(protection))},
            result=result.to_dict(),
        )
        return result

    async def _submit_order(
        self,
        request: OrderRequest,
        budget: DeadlineBudget,
        *,
        operation: str,
    ) -> OrderSnapshot:
        """Shield the initial POST, then fail closed after ambiguity reconciliation.

        The task is cancelled only after the exchange result cannot be proven. This
        prevents a late local adapter retry while preserving FAILED_UNCERTAIN semantics:
        cancellation is never treated as proof that the exchange rejected the order.
        """
        timeout_ms = min(budget.remaining_ms, self.config.default_io_timeout_ms)
        if timeout_ms <= 0:
            raise DeadlineExceeded(f"{operation}: no remaining deadline")
        task = asyncio.create_task(self.api.place_order(request))
        try:
            async with asyncio.timeout(timeout_ms / 1000):
                return await asyncio.shield(task)
        except TimeoutError as timeout_exc:
            # The POST may still be in flight. Query by deterministic client id before
            # deciding whether it is safe to retry or abort.
            reconciliation = DeadlineBudget(
                self.config.cancel_reconcile_timeout_ms,
                label=f"submit-reconcile:{request.client_order_id}",
            )
            for delay in (0.02, 0.05, 0.10, 0.20):
                if task.done():
                    return task.result()
                if reconciliation.expired:
                    break
                await asyncio.sleep(min(delay, reconciliation.remaining_ms / 1000))
                try:
                    return await reconciliation.run(
                        self.api.get_order(
                            request.symbol,
                            None,
                            request.client_order_id,
                            request.kind,
                        ),
                        operation="query ambiguous submit",
                        cap_ms=self.config.default_io_timeout_ms,
                    )
                except OrderNotFound:
                    continue
                except ExchangeReject as exc:
                    if exc.code in {-2013, -2011}:
                        continue
                    raise
            if task.done():
                return task.result()

            # Stop any *local* query/retry continuation inside the adapter. The
            # original request may still have reached the exchange, so the result
            # remains ambiguous and must be reconciled from durable state.
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            log.critical(
                "Order submit remains ambiguous after local task cancellation: %s",
                request.client_order_id,
            )
            raise AmbiguousSubmitError(
                f"{operation} timed out and order existence cannot be proven: "
                f"{request.client_order_id}"
            ) from timeout_exc

    async def _place_protection(
        self,
        signal: Signal,
        quantity: D,
        stop_price: D,
        take_profit_price: D,
        position_mode: PositionMode,
        budget: DeadlineBudget,
    ) -> ProtectionOrders:
        exit_side = self._exit_side(signal.action)
        close_position = self.config.stop_close_position
        stop_request = OrderRequest(
            symbol=signal.symbol,
            side=exit_side,
            order_type="STOP_MARKET",
            kind=OrderKind.ALGO,
            quantity=None if close_position else quantity,
            stop_price=stop_price,
            close_position=close_position,
            reduce_only=False if close_position else self._use_reduce_only(position_mode),
            position_side=self._position_side(signal.action, position_mode),
            working_type=self.config.stop_working_type,
            price_protect=self.config.stop_price_protect,
            client_order_id=self._client_order_id(signal, "SL"),
        )
        stop = await self._submit_order(
            stop_request, budget, operation="stop submit"
        )
        if not stop.status.active:
            raise CriticalExecutionError(
                f"Stop was not accepted as active: {stop.status.value}"
            )
        stop = await self._confirm_protective_order(
            stop,
            budget,
            label="stop",
            allow_filled=False,
        )

        tp_id: str | None = None
        tp_client_id: str | None = self._client_order_id(signal, "TP")
        try:
            tp_request = OrderRequest(
                symbol=signal.symbol,
                side=exit_side,
                order_type="LIMIT",
                kind=OrderKind.REGULAR,
                quantity=quantity,
                price=take_profit_price,
                time_in_force="GTC",
                reduce_only=self._use_reduce_only(position_mode),
                position_side=self._position_side(signal.action, position_mode),
                self_trade_prevention_mode=self.config.self_trade_prevention_mode,
                client_order_id=tp_client_id,
            )
            tp = await self._submit_order(
                tp_request, budget, operation="take-profit submit"
            )
            if tp.status in {OrderStatus.REJECTED, OrderStatus.CANCELED, OrderStatus.EXPIRED}:
                raise ExecutionError(f"Take-profit rejected: {tp.status.value}")
            tp = await self._confirm_protective_order(
                tp,
                budget,
                label="take-profit",
                allow_filled=True,
            )
            tp_id = tp.order_id
        except Exception:
            log.exception("Take-profit placement failed; close-position stop remains active")
            tp_client_id = None

        return ProtectionOrders(
            symbol=signal.symbol,
            action=signal.action,
            stop_order_id=stop.order_id,
            stop_client_order_id=stop.client_order_id,
            stop_kind=OrderKind.ALGO,
            take_profit_order_id=tp_id,
            take_profit_client_order_id=tp_client_id,
            take_profit_kind=OrderKind.REGULAR if tp_id else None,
        )

    async def _confirm_protective_order(
        self,
        order: OrderSnapshot,
        budget: DeadlineBudget,
        *,
        label: str,
        allow_filled: bool,
    ) -> OrderSnapshot:
        """Require REST proof after an acknowledgement before declaring protection."""

        latest = order
        for delay in (0.0, 0.03, 0.08, 0.16):
            if budget.expired:
                break
            if delay:
                await asyncio.sleep(min(delay, budget.remaining_ms / 1000))
            try:
                latest = await budget.run(
                    self.api.get_order(
                        order.symbol,
                        order.order_id,
                        order.client_order_id,
                        order.kind,
                    ),
                    operation=f"confirm {label}",
                    cap_ms=self.config.default_io_timeout_ms,
                )
            except OrderNotFound:
                continue
            if latest.status.active or (
                allow_filled and latest.status is OrderStatus.FILLED
            ):
                return latest
            raise CriticalExecutionError(
                f"Confirmed {label} is not protective: {latest.status.value}"
            )
        raise CriticalExecutionError(
            f"Could not confirm {label} {order.client_order_id} through REST"
        )

    async def _await_or_cancel(
        self,
        order: OrderSnapshot,
        *,
        entry_budget: DeadlineBudget,
        phase_timeout_ms: int,
    ) -> OrderSnapshot:
        latest = await self._await_terminal(order, entry_budget, phase_timeout_ms)
        if latest.status.terminal:
            return latest

        # Cancellation/reconciliation gets its own safety budget, even if entry time is over.
        cancel_budget = DeadlineBudget(
            self.config.cancel_reconcile_timeout_ms,
            label=f"cancel:{order.client_order_id}",
        )
        try:
            canceled = await cancel_budget.run(
                self.api.cancel_order(
                    order.symbol,
                    order.order_id,
                    order.client_order_id,
                    order.kind,
                ),
                operation="cancel order",
                cap_ms=self.config.default_io_timeout_ms,
            )
        except Exception:
            canceled = await cancel_budget.run(
                self.api.get_order(
                    order.symbol,
                    order.order_id,
                    order.client_order_id,
                    order.kind,
                ),
                operation="post-cancel query",
                cap_ms=self.config.default_io_timeout_ms,
            )

        while not canceled.status.terminal and not cancel_budget.expired:
            await asyncio.sleep(
                min(self.config.poll_fallback_ms, cancel_budget.remaining_ms) / 1000
            )
            canceled = await cancel_budget.run(
                self.api.get_order(
                    order.symbol,
                    order.order_id,
                    order.client_order_id,
                    order.kind,
                ),
                operation="cancel reconciliation",
                cap_ms=self.config.default_io_timeout_ms,
            )
        if not canceled.status.terminal:
            raise CriticalExecutionError(
                f"Order {order.client_order_id} remained non-terminal after cancel"
            )
        return canceled

    async def _await_terminal(
        self,
        order: OrderSnapshot,
        entry_budget: DeadlineBudget,
        phase_timeout_ms: int,
    ) -> OrderSnapshot:
        if order.status.terminal:
            return order
        phase_deadline = time.monotonic() + max(1, phase_timeout_ms) / 1000
        latest = order
        while not latest.status.terminal:
            remaining_ms = min(
                entry_budget.remaining_ms,
                max(0, int((phase_deadline - time.monotonic()) * 1000)),
            )
            if remaining_ms <= 0:
                break
            try:
                update = await entry_budget.run(
                    self.api.wait_order_update(
                        order.symbol,
                        order.order_id,
                        order.client_order_id,
                        order.kind,
                        remaining_ms,
                    ),
                    operation="wait order update",
                    cap_ms=remaining_ms,
                )
            except DeadlineExceeded:
                # A phase timeout is expected here. The caller must proceed to
                # cancel-and-reconcile rather than classify the entry as unknown.
                break
            if update is None:
                break
            latest = update
        if latest.status.terminal:
            return latest
        if entry_budget.expired:
            return latest
        return await entry_budget.run(
            self.api.get_order(
                order.symbol,
                order.order_id,
                order.client_order_id,
                order.kind,
            ),
            operation="terminal order query",
            cap_ms=self.config.default_io_timeout_ms,
        )

    async def _emergency_close(
        self,
        symbol: str,
        action: Action,
        quantity: D,
        rules: SymbolRules,
        position_mode: PositionMode,
    ) -> str | None:
        remaining = floor_to_step(quantity, rules.step_size)
        if remaining <= ZERO:
            return None
        close_signal = Signal(
            signal_id=f"emergency-{symbol}-{time.time_ns()}",
            symbol=symbol,
            action=action,
            confidence=ONE,
            created_at_ms=int(time.time() * 1000),
        )
        budget = DeadlineBudget(2_500, label=f"emergency-close:{symbol}")
        try:
            quote = await budget.run(
                self.api.get_quote(symbol),
                operation="emergency quote",
                cap_ms=self.config.default_io_timeout_ms,
            )
            if action is Action.LONG:
                price = floor_to_step(
                    quote.bid * (ONE - self.config.emergency_ioc_band_bps / BPS),
                    rules.tick_size,
                )
            else:
                price = ceil_to_step(
                    quote.ask * (ONE + self.config.emergency_ioc_band_bps / BPS),
                    rules.tick_size,
                )
            ioc_request = OrderRequest(
                symbol=symbol,
                side=self._exit_side(action),
                order_type="LIMIT",
                kind=OrderKind.REGULAR,
                quantity=remaining,
                price=price,
                time_in_force="IOC",
                reduce_only=self._use_reduce_only(position_mode),
                position_side=self._position_side(action, position_mode),
                client_order_id=self._client_order_id(close_signal, "EIOC"),
            )
            ioc = await self._submit_order(
                ioc_request, budget, operation="emergency IOC submit"
            )
            final = await self._await_or_cancel(
                ioc,
                entry_budget=budget,
                phase_timeout_ms=min(500, budget.remaining_ms),
            )
            remaining = floor_to_step(
                max(ZERO, remaining - final.executed_qty), rules.step_size
            )
        except Exception as exc:
            log.exception("Emergency IOC failed")
            if not self.config.allow_emergency_market_exit:
                return f"IOC failed and MARKET fallback disabled: {exc!r}"

        if remaining > ZERO and self.config.allow_emergency_market_exit:
            try:
                market_request = OrderRequest(
                    symbol=symbol,
                    side=self._exit_side(action),
                    order_type="MARKET",
                    kind=OrderKind.REGULAR,
                    quantity=remaining,
                    reduce_only=self._use_reduce_only(position_mode),
                    position_side=self._position_side(action, position_mode),
                    client_order_id=self._client_order_id(close_signal, "EMKT"),
                )
                market = await self._submit_order(
                    market_request, budget, operation="emergency MARKET submit"
                )
                final = await self._await_terminal(
                    market, budget, max(1, budget.remaining_ms)
                )
                remaining = floor_to_step(
                    max(ZERO, remaining - final.executed_qty), rules.step_size
                )
            except Exception as exc:
                return f"MARKET emergency close failed: {exc!r}"

        try:
            position = await self.api.get_position(symbol, action)
        except Exception as exc:
            return f"Could not verify position after close: {exc!r}"
        if remaining > ZERO or not position.is_flat:
            return (
                f"residual exposure order-derived={remaining}, "
                f"account-derived={position.quantity}"
            )
        return None

    async def _get_atr(self, symbol: str) -> D:
        klines = await self.api.get_closed_klines(
            symbol, self.config.atr_timeframe, self.config.atr_warmup_bars
        )
        closed = sorted(
            (k for k in klines if k.is_closed), key=lambda k: k.open_time_ms
        )
        if len(closed) < self.config.atr_period + 1:
            raise ExecutionError("Not enough closed bars for ATR")
        true_ranges: list[D] = []
        for previous, current in zip(closed, closed[1:]):
            true_ranges.append(
                max(
                    current.high - current.low,
                    abs(current.high - previous.close),
                    abs(current.low - previous.close),
                )
            )
        period = self.config.atr_period
        atr = sum(true_ranges[:period], ZERO) / D(period)
        for tr in true_ranges[period:]:
            atr = (atr * D(period - 1) + tr) / D(period)
        if atr <= ZERO:
            raise ExecutionError("ATR is not positive")
        return atr

    def _calculate_position_size(
        self,
        *,
        account: AccountSnapshot,
        rules: SymbolRules,
        entry_price: D,
        effective_loss_per_unit: D,
    ) -> D:
        if (
            account.equity <= ZERO
            or account.available_balance <= ZERO
            or account.max_new_position_notional is None
            or account.max_new_position_notional <= ZERO
            or effective_loss_per_unit <= ZERO
        ):
            return ZERO
        remaining_risk = max(
            ZERO,
            account.equity * self.config.max_portfolio_risk_pct
            - account.current_open_risk,
        )
        risk_budget = min(
            account.equity * self.config.risk_per_trade_pct, remaining_risk
        )
        if risk_budget <= ZERO:
            return ZERO
        qty_by_risk = risk_budget / effective_loss_per_unit
        notional_cap = min(
            self.config.max_position_notional,
            account.max_new_position_notional,
        )
        if rules.max_notional is not None:
            notional_cap = min(notional_cap, rules.max_notional)
        qty_by_notional = notional_cap / entry_price
        qty = floor_to_step(
            min(qty_by_risk, qty_by_notional, rules.max_qty), rules.step_size
        )
        if qty < rules.min_qty or qty * entry_price < rules.min_notional:
            return ZERO
        return qty

    def _maker_request(
        self,
        signal: Signal,
        quantity: D,
        quote: Quote,
        rules: SymbolRules,
        position_mode: PositionMode,
    ) -> OrderRequest:
        return OrderRequest(
            symbol=signal.symbol,
            side=self._entry_side(signal.action),
            order_type="LIMIT",
            kind=OrderKind.REGULAR,
            quantity=quantity,
            price=self._maker_price(signal.action, quote, rules),
            time_in_force="GTX",
            post_only=True,
            position_side=self._position_side(signal.action, position_mode),
            self_trade_prevention_mode=None,  # GTX is not an STP-supported TIF.
            client_order_id=self._client_order_id(signal, "MKR"),
        )

    def _aggressive_request(
        self,
        signal: Signal,
        quantity: D,
        arrival_price: D,
        quote: Quote,
        rules: SymbolRules,
        position_mode: PositionMode,
    ) -> OrderRequest:
        if signal.action is Action.LONG:
            cap = arrival_price * (
                ONE + self.config.max_adverse_slippage_bps / BPS
            )
            raw = min(
                quote.ask * (ONE + self.config.aggressive_cross_bps / BPS), cap
            )
            price = floor_to_step(raw, rules.tick_size)
        else:
            floor = arrival_price * (
                ONE - self.config.max_adverse_slippage_bps / BPS
            )
            raw = max(
                quote.bid * (ONE - self.config.aggressive_cross_bps / BPS), floor
            )
            price = ceil_to_step(raw, rules.tick_size)
        return OrderRequest(
            symbol=signal.symbol,
            side=self._entry_side(signal.action),
            order_type="LIMIT",
            kind=OrderKind.REGULAR,
            quantity=quantity,
            price=price,
            time_in_force="IOC",
            position_side=self._position_side(signal.action, position_mode),
            self_trade_prevention_mode=self.config.self_trade_prevention_mode,
            client_order_id=self._client_order_id(signal, "IOC"),
        )

    def _maker_price(self, action: Action, quote: Quote, rules: SymbolRules) -> D:
        tick = rules.tick_size
        spread_ticks = int(quote.spread / tick) if tick > ZERO else 0
        max_improve = max(0, min(self.config.maker_max_improve_ticks, spread_ticks - 1))
        improve = min(self.config.maker_base_improve_ticks, max_improve)
        total_qty = quote.bid_qty + quote.ask_qty
        imbalance = (
            (quote.bid_qty - quote.ask_qty) / total_qty if total_qty > ZERO else ZERO
        )
        if action is Action.LONG and imbalance >= self.config.maker_imbalance_threshold:
            improve = max_improve
        elif action is Action.SHORT and -imbalance >= self.config.maker_imbalance_threshold:
            improve = max_improve

        if action is Action.LONG:
            raw = min(quote.bid + tick * D(improve), quote.ask - tick)
            price = floor_to_step(raw, tick)
            if price >= quote.ask:
                price = floor_to_step(quote.ask - tick, tick)
        else:
            raw = max(quote.ask - tick * D(improve), quote.bid + tick)
            price = ceil_to_step(raw, tick)
            if price <= quote.bid:
                price = ceil_to_step(quote.bid + tick, tick)
        if price <= ZERO:
            raise ExecutionError("Invalid maker price")
        return price

    def _stop_distance(self, entry_price: D, atr: D) -> D:
        return max(
            atr * self.config.stop_atr_multiple,
            entry_price * self.config.min_stop_distance_bps / BPS,
        )

    def _effective_loss_per_unit(self, entry_price: D, stop_distance: D) -> D:
        costs = entry_price * (
            self.config.estimated_round_trip_fees_bps
            + self.config.execution_risk_reserve_bps
        ) / BPS
        return stop_distance + costs

    def _protection_prices(
        self,
        action: Action,
        entry_price: D,
        stop_distance: D,
        atr: D,
        rules: SymbolRules,
    ) -> tuple[D, D]:
        reward_distance = max(
            atr * self.config.take_profit_atr_multiple,
            stop_distance * self.config.minimum_reward_risk,
        )
        if action is Action.LONG:
            stop = floor_to_step(entry_price - stop_distance, rules.tick_size)
            tp = ceil_to_step(entry_price + reward_distance, rules.tick_size)
        else:
            stop = ceil_to_step(entry_price + stop_distance, rules.tick_size)
            tp = floor_to_step(entry_price - reward_distance, rules.tick_size)
        if stop <= ZERO or tp <= ZERO:
            raise ExecutionError("Invalid stop/take-profit prices")
        return stop, tp

    def _validate_rules_and_quote(
        self, symbol: str, rules: SymbolRules, quote: Quote
    ) -> None:
        if rules.symbol != symbol or quote.symbol != symbol:
            raise ExecutionError("Symbol mismatch in rules/quote")
        if rules.status != "TRADING":
            raise ExecutionError(f"Symbol {symbol} is not trading")
        if min(rules.tick_size, rules.step_size, rules.min_qty) <= ZERO:
            raise ExecutionError("Invalid symbol filters")
        if quote.bid <= ZERO or quote.ask <= quote.bid:
            raise ExecutionError("Invalid or crossed quote")
        local_age_ms = int((time.monotonic() - quote.received_monotonic) * 1000)
        if local_age_ms > self.config.quote_local_max_age_ms:
            raise ExecutionError(f"Quote local age {local_age_ms} ms is stale")
        if quote.exchange_age_at_receive_ms is not None:
            exchange_age = quote.exchange_age_at_receive_ms + local_age_ms
        else:
            now_ms = int(time.time() * 1000)
            exchange_age = now_ms - quote.exchange_time_ms
        if exchange_age > self.config.quote_exchange_max_age_ms:
            raise ExecutionError(f"Quote exchange age {exchange_age} ms is stale")
        if exchange_age < -self.config.quote_future_tolerance_ms:
            raise ExecutionError("Quote exchange timestamp is in the future")
        if quote.spread_bps > self.config.max_spread_bps:
            raise ExecutionError(
                f"Spread {quote.spread_bps} bps exceeds {self.config.max_spread_bps}"
            )

    def _adverse_slippage_bps(
        self, action: Action, arrival_price: D, quote: Quote
    ) -> D:
        if arrival_price <= ZERO:
            return D("Infinity")
        if action is Action.LONG:
            return max(ZERO, (quote.ask - arrival_price) / arrival_price * BPS)
        return max(ZERO, (arrival_price - quote.bid) / arrival_price * BPS)

    def _implementation_shortfall_bps(
        self, action: Action, arrival_price: D, avg_price: D | None
    ) -> D | None:
        if avg_price is None or arrival_price <= ZERO:
            return None
        if action is Action.LONG:
            return (avg_price - arrival_price) / arrival_price * BPS
        return (arrival_price - avg_price) / arrival_price * BPS

    async def _finish(
        self,
        signal: Signal,
        stage: ExecutionStage,
        result: ExecutionResult,
    ) -> ExecutionResult:
        await self.control.transition(
            signal.signal_id,
            expected=None,
            new_stage=stage,
            result=result.to_dict(),
        )
        return result

    async def _observe_quality(self, result: ExecutionResult) -> None:
        decision = self.quality_guard.observe(result)
        if decision.halt:
            await self.control.halt_admission(
                decision.reason or "execution quality circuit breaker"
            )

    def _result(
        self,
        status: str,
        signal: Signal,
        requested_qty: D,
        fills: FillAccumulator,
        arrival_price: D,
        started: float,
        *,
        stop_price: D | None = None,
        take_profit_price: D | None = None,
        stop_order_id: str | None = None,
        take_profit_order_id: str | None = None,
        warnings: tuple[str, ...] = (),
    ) -> ExecutionResult:
        avg = fills.avg_price
        return ExecutionResult(
            status=status,
            signal_id=signal.signal_id,
            symbol=signal.symbol,
            action=signal.action,
            requested_qty=requested_qty,
            executed_qty=fills.executed_qty,
            avg_price=avg,
            arrival_price=arrival_price,
            implementation_shortfall_bps=self._implementation_shortfall_bps(
                signal.action, arrival_price, avg
            ),
            fill_ratio=fills.fill_ratio,
            elapsed_ms=int((time.monotonic() - started) * 1000),
            maker_qty=fills.maker_qty,
            taker_qty=fills.taker_qty,
            stop_price=stop_price,
            take_profit_price=take_profit_price,
            stop_order_id=stop_order_id,
            take_profit_order_id=take_profit_order_id,
            warnings=warnings,
        )

    def _empty_result(
        self,
        status: str,
        signal: Signal,
        *,
        warnings: tuple[str, ...] = (),
    ) -> ExecutionResult:
        return ExecutionResult(
            status=status,
            signal_id=signal.signal_id,
            symbol=signal.symbol,
            action=signal.action,
            requested_qty=ZERO,
            executed_qty=ZERO,
            avg_price=None,
            arrival_price=signal.reference_price or ZERO,
            implementation_shortfall_bps=None,
            fill_ratio=ZERO,
            elapsed_ms=0,
            warnings=warnings,
        )

    async def _get_symbol_lock(self, symbol: str) -> asyncio.Lock:
        async with self._symbol_locks_guard:
            return self._symbol_locks.setdefault(symbol, asyncio.Lock())

    @staticmethod
    def parse_signal(raw: Signal | Mapping[str, Any]) -> Signal:
        try:
            return parse_signal(raw)
        except ValueError as exc:
            raise ExecutionError(str(exc)) from exc

    # Compatibility alias for integrations written against v4 internals.
    _parse_signal = parse_signal

    @staticmethod
    def _entry_side(action: Action) -> Side:
        return Side.BUY if action is Action.LONG else Side.SELL

    @staticmethod
    def _exit_side(action: Action) -> Side:
        return Side.SELL if action is Action.LONG else Side.BUY

    @staticmethod
    def _position_side(action: Action, mode: PositionMode) -> str:
        return action.value if mode is PositionMode.HEDGE else "BOTH"

    @staticmethod
    def _use_reduce_only(mode: PositionMode) -> bool:
        return mode is PositionMode.ONEWAY

    @staticmethod
    def _client_order_id(signal: Signal, suffix: str) -> str:
        digest = hashlib.sha256(signal.signal_id.encode()).hexdigest()[:20]
        return f"smc-{digest}-{suffix}"[:36]

    @staticmethod
    def _request_dict(request: OrderRequest) -> Mapping[str, Any]:
        return encode_json(asdict(request))

    @staticmethod
    def _order_dict(order: OrderSnapshot) -> Mapping[str, Any]:
        return encode_json(asdict(order))

    @staticmethod
    def _rules_dict(rules: SymbolRules) -> Mapping[str, Any]:
        return encode_json(asdict(rules))

    @staticmethod
    def _fills_dict(fills: FillAccumulator) -> Mapping[str, Any]:
        return {
            "requested_qty": str(fills.requested_qty),
            "executed_qty": str(fills.executed_qty),
            "cumulative_quote_qty": str(fills.cumulative_quote_qty),
            "maker_qty": str(fills.maker_qty),
            "taker_qty": str(fills.taker_qty),
            "fill_ratio": str(fills.fill_ratio),
        }
