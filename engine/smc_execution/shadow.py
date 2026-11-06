from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Sequence

from .models import Action, D, ONE, ZERO
from .research_models import ResearchBar, SignalDecision, StrategyManifest
from .research_store import ResearchStore

BPS = D("10000")


@dataclass(frozen=True)
class ShadowConfig:
    stop_atr_multiple: D = D("1.5")
    take_profit_atr_multiple: D = D("2.25")
    entry_slippage_bps: D = D("5")
    stop_slippage_bps: D = D("5")
    round_trip_fee_bps: D = D("10")
    conservative_same_bar_exit: bool = True
    breakeven_activation_r: D | None = D("1.0")
    breakeven_lock_bps: D = D("10")
    trailing_activation_r: D | None = D("1.8")
    trailing_atr_multiple: D | None = D("1.35")
    max_holding_bars: int = 96


class ShadowBook:
    """Durable closed-bar forward tracker using the approved simulation policy."""

    def __init__(
        self,
        store: ResearchStore,
        config: ShadowConfig = ShadowConfig(),
    ) -> None:
        self.store = store
        self.config = config

    async def register(
        self,
        *,
        run_id: str,
        manifest: StrategyManifest,
        symbol: str,
        decision: SignalDecision,
    ) -> dict:
        signal_id = hashlib.sha256(
            f"{run_id}|{symbol}|{decision.feature_time_ms}|{decision.action.value}".encode()
        ).hexdigest()[:32]
        return await self.store.create_shadow_signal(
            {
                "signal_id": signal_id,
                "run_id": run_id,
                "strategy_id": manifest.strategy_id,
                "strategy_version": manifest.version,
                "code_fingerprint": manifest.code_fingerprint,
                "symbol": symbol,
                "action": decision.action.value,
                "confidence": decision.confidence,
                "signal_time_ms": decision.feature_time_ms,
                "reference_price": decision.reference_price,
            }
        )

    async def process_closed_bar(
        self,
        bar: ResearchBar,
        history: Sequence[ResearchBar],
    ) -> None:
        active = await self.store.list_active_shadow(bar.symbol)
        atr = _atr(history, 14)
        for item in active:
            if item["status"] == "PENDING":
                if bar.open_time_ms <= int(item["signal_time_ms"]):
                    continue
                if atr <= ZERO:
                    continue
                action = Action(item["action"])
                adverse = self.config.entry_slippage_bps / BPS
                entry = (
                    bar.open * (ONE + adverse)
                    if action is Action.LONG
                    else bar.open * (ONE - adverse)
                )
                distance = atr * self.config.stop_atr_multiple
                stop = entry - distance if action is Action.LONG else entry + distance
                take_distance = max(
                    atr * self.config.take_profit_atr_multiple,
                    distance,
                )
                take = entry + take_distance if action is Action.LONG else entry - take_distance
                stop_fill = _stop_fill_price(
                    action,
                    stop,
                    stop,
                    self.config.stop_slippage_bps,
                )
                market_loss = (
                    entry - stop_fill
                    if action is Action.LONG
                    else stop_fill - entry
                )
                fee_cost = entry * self.config.round_trip_fee_bps / BPS
                risk_basis = max(D("0.000000000001"), market_loss + fee_cost)
                item = await self.store.activate_shadow(
                    item["signal_id"],
                    entry_time_ms=bar.open_time_ms,
                    entry_price=entry,
                    stop_price=stop,
                    take_profit_price=take,
                    state={
                        "initial_stop_price": stop,
                        "initial_stop_distance": distance,
                        "initial_risk_basis_per_unit": risk_basis,
                        "atr_at_entry": atr,
                        "highest_price": entry,
                        "lowest_price": entry,
                        "holding_bars": 0,
                        "funding_pnl_per_unit": ZERO,
                        "last_processed_close_time_ms": 0,
                    },
                )
            if item["status"] == "OPEN":
                await self._evaluate_open(item, bar, history)

    async def _evaluate_open(
        self,
        item: dict,
        bar: ResearchBar,
        history: Sequence[ResearchBar],
    ) -> None:
        state = json.loads(item.get("state_json") or "{}")
        if int(state.get("last_processed_close_time_ms", 0)) >= bar.close_time_ms:
            return
        action = Action(item["action"])
        entry = D(str(item["entry_price"]))
        stop = D(str(item["stop_price"]))
        take = D(str(item["take_profit_price"]))
        risk = D(
            str(
                state.get("initial_risk_basis_per_unit")
                or state.get("initial_risk_per_unit")
                or abs(entry - stop)
            )
        )
        highest = D(str(state.get("highest_price") or entry))
        lowest = D(str(state.get("lowest_price") or entry))
        holding_bars = int(state.get("holding_bars", 0))
        funding_pnl_per_unit = D(str(state.get("funding_pnl_per_unit", "0")))
        if "funding_pnl_per_unit" not in state and "funding_r" in state:
            funding_pnl_per_unit = D(str(state.get("funding_r", "0"))) * risk

        favorable_before = (
            (highest - entry) / risk
            if action is Action.LONG
            else (entry - lowest) / risk
        ) if risk > ZERO else ZERO
        if (
            self.config.breakeven_activation_r is not None
            and favorable_before >= self.config.breakeven_activation_r
        ):
            lock = entry * self.config.breakeven_lock_bps / BPS
            candidate = entry + lock if action is Action.LONG else entry - lock
            stop = max(stop, candidate) if action is Action.LONG else min(stop, candidate)
        if (
            self.config.trailing_activation_r is not None
            and self.config.trailing_atr_multiple is not None
            and favorable_before >= self.config.trailing_activation_r
            and len(history) >= 2
        ):
            # _atr keeps only the last period + 1 bars, so copying the whole
            # history first was pure waste on every closed bar of every trade.
            local_atr = _atr(history[-16:-1], 14)
            prior = history[-2]
            if local_atr > ZERO:
                candidate = (
                    prior.close - local_atr * self.config.trailing_atr_multiple
                    if action is Action.LONG
                    else prior.close + local_atr * self.config.trailing_atr_multiple
                )
                stop = max(stop, candidate) if action is Action.LONG else min(stop, candidate)

        if action is Action.LONG:
            stop_hit = bar.low <= stop
            take_hit = bar.high >= take
            stop_gap = bar.open <= stop
        else:
            stop_hit = bar.high >= stop
            take_hit = bar.low <= take
            stop_gap = bar.open >= stop

        stop_fill = _stop_fill_price(
            action,
            stop,
            bar.open,
            self.config.stop_slippage_bps,
        )

        rate = bar.feature_funding_rate()
        available_at = bar.funding_available_at_ms
        if (
            rate is not None
            and available_at is not None
            and bar.open_time_ms <= available_at <= bar.close_time_ms
            and risk > ZERO
        ):
            mark = bar.mark_close or bar.close
            signed = -rate if action is Action.LONG else rate
            funding_pnl_per_unit += mark * signed

        holding_bars += 1
        exit_price: D | None = None
        reason: str | None = None
        if stop_hit and take_hit:
            exit_price = stop_fill if self.config.conservative_same_bar_exit else take
            reason = (
                (
                    "STOP_GAP_SAME_BAR_AMBIGUITY"
                    if stop_gap
                    else "STOP_SAME_BAR_AMBIGUITY"
                )
                if self.config.conservative_same_bar_exit
                else "TAKE_SAME_BAR_AMBIGUITY"
            )
        elif stop_hit:
            exit_price = stop_fill
            reason = "STOP_GAP" if stop_gap else "STOP"
        elif take_hit:
            exit_price, reason = take, "TAKE_PROFIT"
        elif holding_bars >= self.config.max_holding_bars:
            exit_price, reason = bar.close, "TIME_EXIT"

        if exit_price is not None and reason is not None:
            direction = ONE if action is Action.LONG else D("-1")
            gross_per_unit = (exit_price - entry) * direction
            fee_per_unit = entry * self.config.round_trip_fee_bps / BPS
            net_per_unit = gross_per_unit - fee_per_unit + funding_pnl_per_unit
            await self.store.close_shadow(
                item["signal_id"],
                exit_time_ms=bar.close_time_ms,
                exit_price=exit_price,
                r_multiple=net_per_unit / risk if risk > ZERO else ZERO,
                exit_reason=reason,
            )
            return

        highest = max(highest, bar.high)
        lowest = min(lowest, bar.low)
        await self.store.update_shadow_state(
            item["signal_id"],
            stop_price=stop,
            state={
                **state,
                "highest_price": highest,
                "lowest_price": lowest,
                "holding_bars": holding_bars,
                "funding_pnl_per_unit": funding_pnl_per_unit,
                "last_processed_close_time_ms": bar.close_time_ms,
            },
        )


def _atr(bars: Sequence[ResearchBar], period: int) -> D:
    selected = bars[-(period + 1) :]
    if len(selected) < 2:
        return ZERO
    values = []
    for previous, current in zip(selected[:-1], selected[1:]):
        values.append(
            max(
                current.high - current.low,
                abs(current.high - previous.close),
                abs(current.low - previous.close),
            )
        )
    return sum(values, ZERO) / D(str(len(values)))


def _stop_fill_price(
    action: Action,
    stop_price: D,
    bar_open: D,
    slippage_bps: D,
) -> D:
    slip = slippage_bps / BPS
    if action is Action.LONG:
        return max(ZERO, min(stop_price, bar_open) * (ONE - slip))
    return max(stop_price, bar_open) * (ONE + slip)
