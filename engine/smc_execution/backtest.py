from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass
from typing import Sequence

from .models import Action, D, ONE, ZERO
from .research_models import (
    PerformanceMetrics,
    ResearchBar,
    SimulatedTrade,
    SimulationConfig,
)
from .strategy import ResearchStrategy

BPS = D("10000")
ATR_PERIOD = 14


class _PrefixView(Sequence[ResearchBar]):
    def __init__(self, source: Sequence[ResearchBar], end: int) -> None:
        self.source = source
        self.end = end

    def __len__(self) -> int:
        return self.end

    def __getitem__(self, index):
        if isinstance(index, slice):
            start, stop, step = index.indices(self.end)
            if step == 1:
                # Native slice of the underlying sequence: same contents, no
                # interpreted loop. This is the only step the strategies use.
                return list(self.source[start:stop])
            return [self.source[i] for i in range(start, stop, step)]
        resolved = index + self.end if index < 0 else index
        if resolved < 0 or resolved >= self.end:
            raise IndexError(index)
        return self.source[resolved]


@dataclass(frozen=True)
class BacktestResult:
    trades: tuple[SimulatedTrade, ...]
    metrics: PerformanceMetrics
    equity_curve: tuple[D, ...]
    signals: int
    missed_fills: int
    rejected_confidence: int


def _atr(bars: Sequence[ResearchBar], period: int = ATR_PERIOD) -> D:
    if len(bars) < 2:
        return ZERO
    values: list[D] = []
    for previous, current in zip(bars[:-1], bars[1:]):
        values.append(
            max(
                current.high - current.low,
                abs(current.high - previous.close),
                abs(current.low - previous.close),
            )
        )
    selected = values[-period:]
    return sum(selected, ZERO) / D(str(len(selected))) if selected else ZERO


def _atr_tail(
    bars: Sequence[ResearchBar], end: int, period: int = ATR_PERIOD
) -> D:
    """ATR over the last ``period`` true ranges of ``bars[:end]``.

    ``period + 1`` bars produce exactly ``period`` true ranges, so this returns
    the same value as ``_atr(bars[:end], period)`` while copying a constant-size
    tail instead of the whole prefix. Taking the full prefix made every backtest
    quadratic in the number of bars.
    """
    return _atr(bars[max(0, end - (period + 1)) : end], period)


def _deterministic_unit(key: str) -> D:
    digest = hashlib.sha256(key.encode("utf-8")).digest()
    integer = int.from_bytes(digest[:8], "big")
    return D(integer) / D(2**64 - 1)


class EventDrivenBacktester:
    """Single-symbol event-driven simulator with conservative intrabar rules.

    Decisions are produced at candle close. Entry cannot occur before a later
    candle. When stop and take-profit are both touched in one OHLC bar, the stop
    wins by default because the true intrabar sequence is unknown.
    """

    def __init__(self, config: SimulationConfig = SimulationConfig()) -> None:
        self.config = config

    def run(
        self,
        bars: Sequence[ResearchBar],
        strategy: ResearchStrategy,
        *,
        start_index: int | None = None,
        end_index: int | None = None,
    ) -> BacktestResult:
        if not bars:
            return BacktestResult((), empty_metrics(self.config.initial_equity), (self.config.initial_equity,), 0, 0, 0)
        start = max(strategy.warmup_bars - 1, start_index or 0)
        end = min(len(bars), end_index if end_index is not None else len(bars))
        equity = self.config.initial_equity
        equity_curve: list[D] = [equity]
        trades: list[SimulatedTrade] = []
        signals = 0
        missed = 0
        rejected_confidence = 0
        i = start

        while i < end - self.config.entry_latency_bars:
            decision = strategy.decide(_PrefixView(bars, i + 1))
            if decision is None:
                i += 1
                continue
            signals += 1
            if decision.feature_time_ms > bars[i].close_time_ms:
                raise RuntimeError("look-ahead detected: decision uses a future feature timestamp")
            if decision.confidence < self.config.min_confidence:
                rejected_confidence += 1
                i += 1
                continue

            entry = self._resolve_entry(bars, i, end, decision.action, f"{strategy.manifest.identity}:{bars[i].close_time_ms}")
            if entry is None:
                missed += 1
                i += 1
                continue
            entry_index, entry_price, liquidity = entry
            atr = _atr_tail(bars, i + 1)
            if atr <= ZERO:
                i += 1
                continue
            stop_distance = atr * self.config.stop_atr_multiple
            take_distance = max(
                atr * self.config.take_profit_atr_multiple,
                stop_distance * self.config.minimum_reward_risk,
            )
            if decision.action is Action.LONG:
                stop = entry_price - stop_distance
                take = entry_price + take_distance
            else:
                stop = entry_price + stop_distance
                take = entry_price - take_distance

            risk_budget = equity * self.config.risk_per_trade_pct
            risk_basis_per_unit = self._planned_risk_per_unit(
                decision.action,
                entry_price,
                stop,
                liquidity,
            )
            quantity = risk_budget / risk_basis_per_unit
            quantity = min(quantity, self.config.max_position_notional / entry_price)
            if quantity <= ZERO:
                i += 1
                continue

            trade = self._simulate_exit(
                bars,
                entry_index,
                end,
                decision.action,
                entry_price,
                stop,
                take,
                quantity,
                liquidity,
                decision.confidence,
                equity,
                risk_basis_per_unit,
                trade_id=f"{strategy.manifest.strategy_id}-{len(trades)+1}-{bars[i].close_time_ms}",
                signal_time_ms=bars[i].close_time_ms,
            )
            equity = max(ZERO, trade.equity_after)
            trades.append(trade)
            equity_curve.append(equity)
            i = max(i + 1, entry_index + trade.holding_bars)
            if equity <= ZERO:
                break

        return BacktestResult(
            trades=tuple(trades),
            metrics=calculate_metrics(trades, self.config.initial_equity),
            equity_curve=tuple(equity_curve),
            signals=signals,
            missed_fills=missed,
            rejected_confidence=rejected_confidence,
        )

    def _resolve_entry(
        self,
        bars: Sequence[ResearchBar],
        signal_index: int,
        end: int,
        action: Action,
        identity: str,
    ) -> tuple[int, D, str] | None:
        entry_index = signal_index + self.config.entry_latency_bars
        if entry_index >= end:
            return None
        if _deterministic_unit(identity) < self.config.missed_fill_probability:
            return None

        arrival = bars[entry_index].open
        offset = self.config.maker_offset_bps / BPS
        maker_price = (
            arrival * (ONE - offset)
            if action is Action.LONG
            else arrival * (ONE + offset)
        )
        maker_end = min(end, entry_index + self.config.maker_wait_bars)
        for index in range(entry_index, maker_end):
            bar = bars[index]
            touched = bar.low <= maker_price if action is Action.LONG else bar.high >= maker_price
            if touched:
                return index, maker_price, "MAKER"

        if not self.config.allow_aggressive_fallback:
            return None
        fallback_index = entry_index + self.config.maker_wait_bars
        if fallback_index >= end:
            return None
        fallback_open = bars[fallback_index].open
        adverse = self.config.aggressive_fallback_bps / BPS
        price = (
            fallback_open * (ONE + adverse)
            if action is Action.LONG
            else fallback_open * (ONE - adverse)
        )
        adverse_move = (
            max(ZERO, price - arrival)
            if action is Action.LONG
            else max(ZERO, arrival - price)
        )
        movement_bps = adverse_move / arrival * BPS if arrival > ZERO else D("Infinity")
        if movement_bps > self.config.max_adverse_slippage_bps:
            return None
        return fallback_index, price, "TAKER"

    def _planned_risk_per_unit(
        self,
        action: Action,
        entry_price: D,
        stop_price: D,
        entry_liquidity: str,
    ) -> D:
        """Planned loss per unit at the initial stop, including costs."""
        stop_fill = self._stop_fill_price(action, stop_price, stop_price)
        entry_fee_bps = (
            self.config.maker_fee_bps
            if entry_liquidity == "MAKER"
            else self.config.taker_fee_bps
        )
        entry_fee = entry_price * entry_fee_bps / BPS
        exit_fee = stop_fill * self.config.taker_fee_bps / BPS
        market_loss = (
            entry_price - stop_fill
            if action is Action.LONG
            else stop_fill - entry_price
        )
        return max(D("0.000000000001"), market_loss + entry_fee + exit_fee)

    def _stop_fill_price(
        self,
        action: Action,
        stop_price: D,
        bar_open: D,
    ) -> D:
        """Conservative stop execution at trigger or through-gap open."""
        slip = self.config.stop_slippage_bps / BPS
        if action is Action.LONG:
            trigger_base = min(stop_price, bar_open)
            return max(ZERO, trigger_base * (ONE - slip))
        trigger_base = max(stop_price, bar_open)
        return trigger_base * (ONE + slip)

    def _simulate_exit(
        self,
        bars: Sequence[ResearchBar],
        entry_index: int,
        end: int,
        action: Action,
        entry_price: D,
        stop_initial: D,
        take_initial: D,
        quantity: D,
        entry_liquidity: str,
        confidence: D,
        equity_before: D,
        risk_basis_per_unit: D,
        *,
        trade_id: str,
        signal_time_ms: int,
    ) -> SimulatedTrade:
        stop = stop_initial
        take = take_initial
        risk_per_unit = risk_basis_per_unit
        highest = entry_price
        lowest = entry_price
        max_favorable_r = ZERO
        max_adverse_r = ZERO
        exit_price = bars[min(entry_index, end - 1)].close
        exit_index = min(end - 1, entry_index)
        exit_reason = "END_OF_DATA"
        funding = ZERO

        for index in range(entry_index, min(end, entry_index + self.config.max_holding_bars)):
            bar = bars[index]
            # Dynamic stop uses information available before this candle only.
            if index > entry_index and risk_per_unit > ZERO:
                prior = bars[index - 1]
                favorable_before = (
                    (highest - entry_price) / risk_per_unit
                    if action is Action.LONG
                    else (entry_price - lowest) / risk_per_unit
                )
                if (
                    self.config.breakeven_activation_r is not None
                    and favorable_before >= self.config.breakeven_activation_r
                ):
                    lock = entry_price * self.config.breakeven_lock_bps / BPS
                    candidate = entry_price + lock if action is Action.LONG else entry_price - lock
                    stop = max(stop, candidate) if action is Action.LONG else min(stop, candidate)
                if (
                    self.config.trailing_activation_r is not None
                    and self.config.trailing_atr_multiple is not None
                    and favorable_before >= self.config.trailing_activation_r
                ):
                    local_atr = _atr_tail(bars, index)
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

            stop_fill = self._stop_fill_price(action, stop, bar.open)

            if stop_hit and take_hit:
                if self.config.conservative_same_bar_exit:
                    exit_price = stop_fill
                    exit_reason = (
                        "STOP_GAP_SAME_BAR_AMBIGUITY"
                        if stop_gap
                        else "STOP_SAME_BAR_AMBIGUITY"
                    )
                else:
                    exit_price, exit_reason = take, "TAKE_SAME_BAR_AMBIGUITY"
                exit_index = index
                break
            if stop_hit:
                exit_price = stop_fill
                exit_reason = "STOP_GAP" if stop_gap else "STOP"
                exit_index = index
                break
            if take_hit:
                exit_price, exit_reason, exit_index = take, "TAKE_PROFIT", index
                break

            highest = max(highest, bar.high)
            lowest = min(lowest, bar.low)
            if risk_per_unit > ZERO:
                favorable = (
                    (highest - entry_price) / risk_per_unit
                    if action is Action.LONG
                    else (entry_price - lowest) / risk_per_unit
                )
                adverse = (
                    (entry_price - lowest) / risk_per_unit
                    if action is Action.LONG
                    else (highest - entry_price) / risk_per_unit
                )
                max_favorable_r = max(max_favorable_r, favorable)
                max_adverse_r = max(max_adverse_r, adverse)

            rate = bar.feature_funding_rate()
            available_at = bar.funding_available_at_ms
            if (
                rate is not None
                and available_at is not None
                and bar.open_time_ms <= available_at <= bar.close_time_ms
            ):
                notional = quantity * (bar.mark_close or bar.close)
                # Positive funding means longs pay shorts.
                signed = -rate if action is Action.LONG else rate
                funding += notional * signed

            exit_price = bar.close
            exit_index = index
            exit_reason = "TIME_EXIT" if index + 1 >= entry_index + self.config.max_holding_bars else "END_OF_DATA"

        direction = ONE if action is Action.LONG else D("-1")
        gross = (exit_price - entry_price) * quantity * direction
        entry_fee_bps = (
            self.config.maker_fee_bps if entry_liquidity == "MAKER" else self.config.taker_fee_bps
        )
        fees = quantity * entry_price * entry_fee_bps / BPS
        fees += quantity * exit_price * self.config.taker_fee_bps / BPS
        net = gross - fees + funding
        initial_risk = risk_per_unit * quantity
        r_multiple = net / initial_risk if initial_risk > ZERO else ZERO
        equity_after = equity_before + net
        return SimulatedTrade(
            trade_id=trade_id,
            symbol=bars[entry_index].symbol,
            action=action,
            signal_time_ms=signal_time_ms,
            entry_time_ms=bars[entry_index].open_time_ms,
            exit_time_ms=bars[exit_index].close_time_ms,
            entry_price=entry_price,
            exit_price=exit_price,
            stop_price_initial=stop_initial,
            take_profit_price_initial=take_initial,
            quantity=quantity,
            gross_pnl=gross,
            fees=fees,
            funding=funding,
            net_pnl=net,
            initial_risk=initial_risk,
            r_multiple=r_multiple,
            holding_bars=exit_index - entry_index + 1,
            exit_reason=exit_reason,
            entry_liquidity=entry_liquidity,
            confidence=confidence,
            equity_before=equity_before,
            equity_after=equity_after,
            max_favorable_r=max_favorable_r,
            max_adverse_r=max_adverse_r,
        )


def calculate_metrics(
    trades: Sequence[SimulatedTrade], initial_equity: D
) -> PerformanceMetrics:
    if not trades:
        return empty_metrics(initial_equity)
    r_values = [trade.r_multiple for trade in trades]
    pnl_values = [trade.net_pnl for trade in trades]
    wins = [trade for trade in trades if trade.net_pnl > ZERO]
    losses = [trade for trade in trades if trade.net_pnl < ZERO]
    breakeven = len(trades) - len(wins) - len(losses)
    gross_profit = sum((trade.net_pnl for trade in wins), ZERO)
    gross_loss = abs(sum((trade.net_pnl for trade in losses), ZERO))
    profit_factor = gross_profit / gross_loss if gross_loss > ZERO else D("999") if gross_profit > ZERO else ZERO
    mean_r = sum(r_values, ZERO) / D(str(len(r_values)))
    std_r = _sample_std(r_values)
    downside = [min(ZERO, value) for value in r_values]
    downside_std = _sample_std(downside)
    sharpe = mean_r / std_r * D(str(math.sqrt(len(r_values)))) if std_r > ZERO else ZERO
    sortino = mean_r / downside_std * D(str(math.sqrt(len(r_values)))) if downside_std > ZERO else ZERO

    peak = initial_equity
    max_dd = ZERO
    for trade in trades:
        peak = max(peak, trade.equity_after)
        if peak > ZERO:
            max_dd = max(max_dd, (peak - trade.equity_after) / peak)
    net = sum(pnl_values, ZERO)
    return_pct = net / initial_equity if initial_equity > ZERO else ZERO
    calmar = return_pct / max_dd if max_dd > ZERO else ZERO
    sorted_r = sorted(r_values)
    median = sorted_r[len(sorted_r) // 2] if len(sorted_r) % 2 else (sorted_r[len(sorted_r)//2 - 1] + sorted_r[len(sorted_r)//2]) / D("2")
    return PerformanceMetrics(
        trades=len(trades),
        wins=len(wins),
        losses=len(losses),
        breakeven=breakeven,
        win_rate=D(str(len(wins))) / D(str(len(trades))),
        net_pnl=net,
        return_pct=return_pct,
        expectancy_r=mean_r,
        avg_win_r=sum((t.r_multiple for t in wins), ZERO) / D(str(len(wins))) if wins else ZERO,
        avg_loss_r=sum((t.r_multiple for t in losses), ZERO) / D(str(len(losses))) if losses else ZERO,
        profit_factor=profit_factor,
        max_drawdown_pct=max_dd,
        sharpe_per_trade=sharpe,
        sortino_per_trade=sortino,
        calmar=calmar,
        total_fees=sum((trade.fees for trade in trades), ZERO),
        total_funding=sum((trade.funding for trade in trades), ZERO),
        maker_ratio=D(str(sum(1 for t in trades if t.entry_liquidity == "MAKER"))) / D(str(len(trades))),
        avg_holding_bars=D(str(sum(t.holding_bars for t in trades))) / D(str(len(trades))),
        median_r=median,
        best_trade_r=max(r_values),
        worst_trade_r=min(r_values),
    )


def _sample_std(values: Sequence[D]) -> D:
    if len(values) < 2:
        return ZERO
    mean = sum(values, ZERO) / D(str(len(values)))
    variance = sum(((value - mean) ** 2 for value in values), ZERO) / D(str(len(values) - 1))
    return variance.sqrt() if variance > ZERO else ZERO


def empty_metrics(initial_equity: D = D("10000")) -> PerformanceMetrics:
    return PerformanceMetrics(
        trades=0,
        wins=0,
        losses=0,
        breakeven=0,
        win_rate=ZERO,
        net_pnl=ZERO,
        return_pct=ZERO,
        expectancy_r=ZERO,
        avg_win_r=ZERO,
        avg_loss_r=ZERO,
        profit_factor=ZERO,
        max_drawdown_pct=ZERO,
        sharpe_per_trade=ZERO,
        sortino_per_trade=ZERO,
        calmar=ZERO,
        total_fees=ZERO,
        total_funding=ZERO,
        maker_ratio=ZERO,
        avg_holding_bars=ZERO,
        median_r=ZERO,
        best_trade_r=ZERO,
        worst_trade_r=ZERO,
    )
