from __future__ import annotations

import itertools
import math
import random
import time
import uuid
from dataclasses import dataclass, replace
from typing import Any, Mapping, Sequence

from .backtest import BacktestResult, EventDrivenBacktester, calculate_metrics
from .models import D, ONE, ZERO
from .research_data import ResearchDataset
from .research_models import (
    ApprovalStage,
    BootstrapResult,
    GateDecision,
    PerformanceMetrics,
    ResearchReport,
    RunStatus,
    SimulatedTrade,
    SimulationConfig,
    StressScenarioResult,
    WalkForwardFold,
)
from .strategy import (
    MeanReversionBaseline,
    RandomBaseline,
    ResearchStrategy,
    SMCCompositeStrategy,
    TrendFollowingBaseline,
)


@dataclass(frozen=True)
class WalkForwardConfig:
    train_bars: int = 4_000
    test_bars: int = 1_000
    step_bars: int = 1_000
    purge_bars: int = 100
    embargo_bars: int = 20
    min_train_trades: int = 25
    objective_drawdown_penalty: D = D("1.5")

    def __post_init__(self) -> None:
        if self.train_bars < 100 or self.test_bars < 50:
            raise ValueError("walk-forward windows are too small")
        if self.step_bars < 1:
            raise ValueError("step_bars must be positive")
        if self.purge_bars < 0 or self.embargo_bars < 0:
            raise ValueError("purge/embargo cannot be negative")
        if self.step_bars + self.embargo_bars < self.test_bars:
            raise ValueError(
                "step_bars + embargo_bars must be >= test_bars, otherwise "
                "out-of-sample windows overlap and the same bars are counted "
                "more than once in the aggregate gate metrics"
            )


@dataclass(frozen=True)
class AlphaGatePolicy:
    min_oos_trades: int = 80
    min_oos_folds: int = 2
    min_positive_fold_ratio: D = D("0.60")
    min_expectancy_r: D = D("0.05")
    min_profit_factor: D = D("1.15")
    max_drawdown_pct: D = D("0.15")
    min_probability_positive: D = D("0.90")
    min_stress_pass_ratio: D = D("0.60")
    baseline_expectancy_margin_r: D = D("0.02")
    require_positive_return: bool = True


DEFAULT_PARAMETER_GRID: tuple[Mapping[str, Any], ...] = tuple(
    {
        "swing_lookback": swing,
        "displacement_atr": displacement,
        "min_score": score,
        "allow_countertrend_sweeps": countertrend,
    }
    for swing, displacement, score, countertrend in itertools.product(
        (12, 20, 30),
        (D("0.65"), D("0.85"), D("1.05")),
        (D("2.75"), D("3.25")),
        (True,),
    )
)


def walk_forward_windows(
    rows: int, config: WalkForwardConfig
) -> tuple[tuple[int, int, int, int], ...]:
    """Return the canonical train/test index windows used by every validator.

    Test windows never overlap — ``__post_init__`` requires ``step_bars +
    embargo_bars >= test_bars``. Training windows deliberately do overlap and
    include the out-of-sample period of earlier folds: that is what makes this a
    rolling walk-forward rather than a set of independent trials, and it means
    the parameter choice of fold N+1 has seen the outcome of fold N.

    Keeping one canonical window generator guarantees that strategy, baselines
    and stress tests are compared on identical out-of-sample periods.
    """
    windows: list[tuple[int, int, int, int]] = []
    train_start = 0
    while True:
        train_end = train_start + config.train_bars
        test_start = train_end + config.purge_bars
        test_end = test_start + config.test_bars
        if test_end > rows:
            break
        windows.append((train_start, train_end, test_start, test_end))
        train_start += config.step_bars + config.embargo_bars
    return tuple(windows)


class WalkForwardValidator:
    def __init__(
        self,
        simulation: SimulationConfig,
        config: WalkForwardConfig = WalkForwardConfig(),
    ) -> None:
        self.simulation = simulation
        self.config = config

    def run(
        self,
        bars,
        strategy: ResearchStrategy,
        parameter_grid: Sequence[Mapping[str, Any]],
    ) -> tuple[
        tuple[WalkForwardFold, ...],
        tuple[SimulatedTrade, ...],
        tuple[SimulatedTrade, ...],
    ]:
        """Return the folds, the chained aggregate trades, and the raw ones.

        The raw per-window trades are the sizing decision the simulator actually
        made, so any diagnostic about sizing must read them rather than the
        rebased copies.
        """
        cfg = self.config
        folds: list[WalkForwardFold] = []
        oos_trades: list[SimulatedTrade] = []
        fold_number = 0

        for train_start, train_end, test_start, test_end in walk_forward_windows(
            len(bars), cfg
        ):
            selected, train_result = self._select(
                bars,
                strategy,
                parameter_grid,
                train_start,
                train_end,
            )
            test_strategy = strategy.with_parameters(selected)
            test_result = EventDrivenBacktester(self.simulation).run(
                bars,
                test_strategy,
                start_index=test_start,
                end_index=test_end,
            )
            fold_number += 1
            folds.append(
                WalkForwardFold(
                    fold=fold_number,
                    train_start_ms=bars[train_start].open_time_ms,
                    train_end_ms=bars[train_end - 1].close_time_ms,
                    test_start_ms=bars[test_start].open_time_ms,
                    test_end_ms=bars[test_end - 1].close_time_ms,
                    selected_parameters=dict(selected),
                    train_metrics=train_result.metrics,
                    test_metrics=test_result.metrics,
                )
            )
            oos_trades.extend(test_result.trades)

        return (
            tuple(folds),
            tuple(_rebase_trades(oos_trades, self.simulation)),
            tuple(oos_trades),
        )

    def _select(
        self,
        bars,
        strategy: ResearchStrategy,
        grid: Sequence[Mapping[str, Any]],
        start: int,
        end: int,
    ) -> tuple[Mapping[str, Any], BacktestResult]:
        candidates = tuple(grid) or ({},)
        best_params: Mapping[str, Any] = candidates[0]
        best_result = EventDrivenBacktester(self.simulation).run(
            bars, strategy.with_parameters(best_params), start_index=start, end_index=end
        )
        best_score = self._objective(best_result.metrics)
        for parameters in candidates[1:]:
            result = EventDrivenBacktester(self.simulation).run(
                bars,
                strategy.with_parameters(parameters),
                start_index=start,
                end_index=end,
            )
            score = self._objective(result.metrics)
            if score > best_score:
                best_params, best_result, best_score = parameters, result, score
        return dict(best_params), best_result

    def _objective(self, metrics: PerformanceMetrics) -> D:
        if metrics.trades < self.config.min_train_trades:
            return D("-999") + D(str(metrics.trades)) / D("1000")
        sample_bonus = D(str(math.sqrt(metrics.trades)))
        return (
            metrics.expectancy_r * sample_bonus
            + min(metrics.profit_factor, D("3")) / D("5")
            - metrics.max_drawdown_pct * self.config.objective_drawdown_penalty
        )


class ResearchRunner:
    def __init__(
        self,
        *,
        simulation: SimulationConfig = SimulationConfig(),
        walk_forward: WalkForwardConfig = WalkForwardConfig(),
        gate_policy: AlphaGatePolicy = AlphaGatePolicy(),
        bootstrap_samples: int = 2_000,
        bootstrap_seed: int = 17,
    ) -> None:
        self.simulation = simulation
        self.walk_forward = walk_forward
        self.gate_policy = gate_policy
        self.bootstrap_samples = bootstrap_samples
        self.bootstrap_seed = bootstrap_seed

    def run(
        self,
        dataset: ResearchDataset,
        strategy: ResearchStrategy | None = None,
        *,
        parameter_grid: Sequence[Mapping[str, Any]] = DEFAULT_PARAMETER_GRID,
        run_id: str | None = None,
    ) -> ResearchReport:
        selected_strategy = strategy or SMCCompositeStrategy()
        identifier = run_id or uuid.uuid4().hex
        created = int(time.time() * 1000)
        warnings = list(dataset.quality.warnings)
        try:
            folds, oos_trades, sized_trades = WalkForwardValidator(
                self.simulation, self.walk_forward
            ).run(dataset.bars, selected_strategy, parameter_grid)
            if not folds:
                warnings.append(
                    "Dataset is too short for configured walk-forward windows; "
                    "report cannot pass the alpha gate."
                )
            deployment_strategy = (
                selected_strategy.with_parameters(folds[-1].selected_parameters)
                if folds
                else selected_strategy
            )
            if folds:
                warnings.append(
                    "Deployment manifest is bound to parameters selected by the "
                    "last completed training fold; aggregate gate metrics remain "
                    "strictly out-of-sample across all folds."
                )
            metrics = calculate_metrics(oos_trades, self.simulation.initial_equity)
            # Read the sizing the simulator actually chose, not the rebased copy.
            sizing_note = sizing_diagnostic(sized_trades, self.simulation)
            if sizing_note:
                warnings.append(sizing_note)
            bootstrap = bootstrap_trade_distribution(
                oos_trades,
                samples=self.bootstrap_samples,
                seed=self.bootstrap_seed,
                risk_fraction=realized_risk_fraction(
                    oos_trades, self.simulation.risk_per_trade_pct
                ),
            )
            stress = run_stress_suite_oos(
                dataset.bars, selected_strategy, folds, self.walk_forward, self.simulation
            )
            baselines = run_baselines_oos(
                dataset.bars, self.simulation, self.walk_forward
            )
            gate = evaluate_alpha_gate(
                metrics, bootstrap, stress, baselines, folds, self.gate_policy
            )
            return ResearchReport(
                run_id=identifier,
                dataset_id=dataset.dataset_id,
                dataset_fingerprint=dataset.fingerprint,
                manifest=deployment_strategy.manifest,
                simulation=self.simulation,
                status=RunStatus.COMPLETED,
                created_at_ms=created,
                completed_at_ms=int(time.time() * 1000),
                metrics=metrics,
                bootstrap=bootstrap,
                stress=stress,
                folds=folds,
                baselines=baselines,
                gate=gate,
                warnings=tuple(warnings),
            )
        except Exception as exc:
            return ResearchReport(
                run_id=identifier,
                dataset_id=dataset.dataset_id,
                dataset_fingerprint=dataset.fingerprint,
                manifest=selected_strategy.manifest,
                simulation=self.simulation,
                status=RunStatus.FAILED,
                created_at_ms=created,
                completed_at_ms=int(time.time() * 1000),
                metrics=None,
                bootstrap=None,
                stress=(),
                folds=(),
                baselines={},
                gate=None,
                warnings=tuple(warnings),
                error=repr(exc),
            )


def _aggregate_window_trades(
    bars,
    strategy: ResearchStrategy,
    simulation: SimulationConfig,
    windows: Sequence[tuple[int, int, int, int]],
) -> tuple[SimulatedTrade, ...]:
    trades: list[SimulatedTrade] = []
    for _, _, test_start, test_end in windows:
        result = EventDrivenBacktester(simulation).run(
            bars, strategy, start_index=test_start, end_index=test_end
        )
        trades.extend(result.trades)
    return tuple(_rebase_trades(trades, simulation))


def run_baselines_oos(
    bars,
    simulation: SimulationConfig,
    walk_forward: WalkForwardConfig,
) -> Mapping[str, PerformanceMetrics]:
    windows = walk_forward_windows(len(bars), walk_forward)
    strategies = (
        TrendFollowingBaseline(),
        MeanReversionBaseline(),
        RandomBaseline(),
    )
    # Baseline confidence values are intentionally not calibrated to the SMC
    # score. Filtering them with the production confidence threshold would make
    # the comparison artificially easy (the random baseline could place zero
    # trades). Costs, latency, sizing and exits remain identical.
    baseline_simulation = replace(simulation, min_confidence=ZERO)
    result: dict[str, PerformanceMetrics] = {}
    for strategy in strategies:
        trades = _aggregate_window_trades(
            bars, strategy, baseline_simulation, windows
        )
        result[strategy.manifest.strategy_id] = calculate_metrics(
            trades, simulation.initial_equity
        )
    return result


def _stress_scenarios(
    simulation: SimulationConfig,
) -> Mapping[str, SimulationConfig]:
    return {
        "fees_x2": replace(
            simulation,
            maker_fee_bps=simulation.maker_fee_bps * D("2"),
            taker_fee_bps=simulation.taker_fee_bps * D("2"),
        ),
        "slippage_x2": replace(
            simulation,
            aggressive_fallback_bps=simulation.aggressive_fallback_bps * D("2"),
            stop_slippage_bps=simulation.stop_slippage_bps * D("2"),
            max_adverse_slippage_bps=max(
                simulation.max_adverse_slippage_bps,
                simulation.aggressive_fallback_bps * D("2"),
            ),
        ),
        "missed_fills_20pct": replace(
            simulation,
            missed_fill_probability=max(simulation.missed_fill_probability, D("0.20")),
        ),
        "latency_plus_one_bar": replace(
            simulation, entry_latency_bars=simulation.entry_latency_bars + 1
        ),
        "combined_adverse": replace(
            simulation,
            maker_fee_bps=simulation.maker_fee_bps * D("2"),
            taker_fee_bps=simulation.taker_fee_bps * D("2"),
            aggressive_fallback_bps=simulation.aggressive_fallback_bps * D("2"),
            stop_slippage_bps=simulation.stop_slippage_bps * D("2"),
            max_adverse_slippage_bps=max(
                simulation.max_adverse_slippage_bps,
                simulation.aggressive_fallback_bps * D("2"),
            ),
            missed_fill_probability=max(simulation.missed_fill_probability, D("0.20")),
            entry_latency_bars=simulation.entry_latency_bars + 1,
        ),
    }


def run_stress_suite_oos(
    bars,
    base_strategy: ResearchStrategy,
    folds: Sequence[WalkForwardFold],
    walk_forward: WalkForwardConfig,
    simulation: SimulationConfig,
) -> tuple[StressScenarioResult, ...]:
    windows = walk_forward_windows(len(bars), walk_forward)
    if len(windows) != len(folds):
        raise RuntimeError("walk-forward fold/window mismatch")
    results: list[StressScenarioResult] = []
    for name, config in _stress_scenarios(simulation).items():
        trades: list[SimulatedTrade] = []
        for fold, (_, _, test_start, test_end) in zip(folds, windows):
            strategy = base_strategy.with_parameters(fold.selected_parameters)
            result = EventDrivenBacktester(config).run(
                bars, strategy, start_index=test_start, end_index=test_end
            )
            trades.extend(result.trades)
        metrics = calculate_metrics(
            _rebase_trades(trades, config), config.initial_equity
        )
        results.append(
            StressScenarioResult(
                name=name,
                net_pnl=metrics.net_pnl,
                profit_factor=metrics.profit_factor,
                expectancy_r=metrics.expectancy_r,
                max_drawdown_pct=metrics.max_drawdown_pct,
                passed=(
                    metrics.trades > 0
                    and metrics.expectancy_r > ZERO
                    and metrics.profit_factor >= D("1")
                ),
            )
        )
    return tuple(results)


# Backward-compatible helpers for callers that explicitly want an in-sample diagnostic.
def run_baselines(
    bars, simulation: SimulationConfig
) -> Mapping[str, PerformanceMetrics]:
    strategies = (TrendFollowingBaseline(), MeanReversionBaseline(), RandomBaseline())
    baseline_simulation = replace(simulation, min_confidence=ZERO)
    return {
        strategy.manifest.strategy_id: EventDrivenBacktester(baseline_simulation)
        .run(bars, strategy)
        .metrics
        for strategy in strategies
    }


def run_stress_suite(
    bars, strategy: ResearchStrategy, simulation: SimulationConfig
) -> tuple[StressScenarioResult, ...]:
    results: list[StressScenarioResult] = []
    for name, config in _stress_scenarios(simulation).items():
        metrics = EventDrivenBacktester(config).run(bars, strategy).metrics
        results.append(
            StressScenarioResult(
                name=name,
                net_pnl=metrics.net_pnl,
                profit_factor=metrics.profit_factor,
                expectancy_r=metrics.expectancy_r,
                max_drawdown_pct=metrics.max_drawdown_pct,
                passed=(
                    metrics.trades > 0
                    and metrics.expectancy_r > ZERO
                    and metrics.profit_factor >= D("1")
                ),
            )
        )
    return tuple(results)


def bootstrap_trade_distribution(
    trades: Sequence[SimulatedTrade],
    *,
    samples: int,
    seed: int,
    risk_fraction: D,
) -> BootstrapResult:
    if not trades or samples <= 0:
        return BootstrapResult(0, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO)
    rng = random.Random(seed)
    values = [float(trade.r_multiple) for trade in trades]
    expectations: list[float] = []
    drawdowns: list[float] = []
    block = max(1, int(math.sqrt(len(values))))
    for _ in range(samples):
        sample: list[float] = []
        while len(sample) < len(values):
            start = rng.randrange(len(values))
            for offset in range(block):
                sample.append(values[(start + offset) % len(values)])
                if len(sample) >= len(values):
                    break
        expectations.append(sum(sample) / len(sample))
        equity = 1.0
        peak = 1.0
        max_dd = 0.0
        for r_value in sample:
            equity *= max(0.000001, 1.0 + float(risk_fraction) * r_value)
            peak = max(peak, equity)
            max_dd = max(max_dd, (peak - equity) / peak)
        drawdowns.append(max_dd)
    expectations.sort()
    drawdowns.sort()
    positive = sum(1 for value in expectations if value > 0) / len(expectations)
    return BootstrapResult(
        samples=samples,
        probability_positive_expectancy=D(str(positive)),
        expectancy_r_p05=D(str(_percentile(expectations, 0.05))),
        expectancy_r_p50=D(str(_percentile(expectations, 0.50))),
        expectancy_r_p95=D(str(_percentile(expectations, 0.95))),
        max_drawdown_pct_p50=D(str(_percentile(drawdowns, 0.50))),
        max_drawdown_pct_p95=D(str(_percentile(drawdowns, 0.95))),
    )


def evaluate_alpha_gate(
    metrics: PerformanceMetrics,
    bootstrap: BootstrapResult,
    stress: Sequence[StressScenarioResult],
    baselines: Mapping[str, PerformanceMetrics],
    folds: Sequence[WalkForwardFold],
    policy: AlphaGatePolicy,
) -> GateDecision:
    reasons: list[str] = []
    if len(folds) < policy.min_oos_folds:
        reasons.append(f"OOS folds {len(folds)} < {policy.min_oos_folds}")
    positive_fold_ratio = (
        D(str(sum(1 for fold in folds if fold.test_metrics.expectancy_r > ZERO)))
        / D(str(len(folds)))
        if folds
        else ZERO
    )
    if positive_fold_ratio < policy.min_positive_fold_ratio:
        reasons.append(
            f"positive OOS fold ratio {positive_fold_ratio} < "
            f"{policy.min_positive_fold_ratio}"
        )
    if metrics.trades < policy.min_oos_trades:
        reasons.append(f"OOS trades {metrics.trades} < {policy.min_oos_trades}")
    if metrics.expectancy_r < policy.min_expectancy_r:
        reasons.append(
            f"expectancy {metrics.expectancy_r}R < {policy.min_expectancy_r}R"
        )
    if metrics.profit_factor < policy.min_profit_factor:
        reasons.append(
            f"profit factor {metrics.profit_factor} < {policy.min_profit_factor}"
        )
    if metrics.max_drawdown_pct > policy.max_drawdown_pct:
        reasons.append(
            f"max drawdown {metrics.max_drawdown_pct} > {policy.max_drawdown_pct}"
        )
    if bootstrap.probability_positive_expectancy < policy.min_probability_positive:
        reasons.append(
            "bootstrap probability positive "
            f"{bootstrap.probability_positive_expectancy} < {policy.min_probability_positive}"
        )
    stress_ratio = (
        D(str(sum(1 for item in stress if item.passed))) / D(str(len(stress)))
        if stress
        else ZERO
    )
    if stress_ratio < policy.min_stress_pass_ratio:
        reasons.append(
            f"stress pass ratio {stress_ratio} < {policy.min_stress_pass_ratio}"
        )
    best_baseline = max(
        (item.expectancy_r for item in baselines.values()), default=ZERO
    )
    if metrics.expectancy_r < best_baseline + policy.baseline_expectancy_margin_r:
        reasons.append(
            f"does not beat best baseline by {policy.baseline_expectancy_margin_r}R"
        )
    if policy.require_positive_return and metrics.return_pct <= ZERO:
        reasons.append("OOS net return is not positive")

    components = [
        min(D("1"), D(str(len(folds))) / D(str(max(1, policy.min_oos_folds)))),
        min(
            D("1"),
            positive_fold_ratio / max(policy.min_positive_fold_ratio, D("0.0001")),
        ),
        min(D("1"), D(str(metrics.trades)) / D(str(max(1, policy.min_oos_trades)))),
        min(D("1"), max(ZERO, metrics.expectancy_r) / max(policy.min_expectancy_r, D("0.0001"))),
        min(D("1"), metrics.profit_factor / max(policy.min_profit_factor, D("0.0001"))),
        min(D("1"), bootstrap.probability_positive_expectancy / max(policy.min_probability_positive, D("0.0001"))),
        min(D("1"), stress_ratio / max(policy.min_stress_pass_ratio, D("0.0001"))),
        min(D("1"), policy.max_drawdown_pct / max(metrics.max_drawdown_pct, D("0.0001"))),
    ]
    score = sum(components, ZERO) / D(str(len(components))) * D("100")
    return GateDecision(
        passed=not reasons,
        reasons=tuple(reasons),
        score=score,
        required_stage=ApprovalStage.RESEARCH_PASSED,
    )


def _percentile(values: Sequence[float], quantile: float) -> float:
    if not values:
        return 0.0
    position = (len(values) - 1) * quantile
    low = int(math.floor(position))
    high = int(math.ceil(position))
    if low == high:
        return values[low]
    weight = position - low
    return values[low] * (1.0 - weight) + values[high] * weight


def realized_risk_fraction(trades: Sequence[SimulatedTrade], fallback: D) -> D:
    """Mean fraction of equity actually risked per trade.

    ``risk_per_trade_pct`` is only an upper bound: ``max_position_notional``
    frequently binds first, and then the configured value never describes the
    simulated account. Statistics that compound risk must use this instead.
    """
    fractions = [
        trade.initial_risk / trade.equity_before
        for trade in trades
        if trade.equity_before > ZERO and trade.initial_risk > ZERO
    ]
    if not fractions:
        return fallback
    return sum(fractions, ZERO) / D(str(len(fractions)))


def sizing_diagnostic(
    trades: Sequence[SimulatedTrade], simulation: SimulationConfig
) -> str | None:
    """Warn when the notional cap, not the risk profile, decided position size."""
    if not trades:
        return None
    capped = sum(
        1
        for trade in trades
        if trade.entry_price > ZERO
        and trade.quantity * trade.entry_price
        >= simulation.max_position_notional * D("0.999999")
    )
    if not capped:
        return None
    effective = realized_risk_fraction(trades, simulation.risk_per_trade_pct)
    return (
        f"Position size was limited by max_position_notional="
        f"{simulation.max_position_notional} on {capped}/{len(trades)} "
        f"out-of-sample trades; the effective risk per trade was "
        f"{effective * D('100'):.4f}% of equity instead of the configured "
        f"{simulation.risk_per_trade_pct * D('100'):.4f}%. The risk profile was "
        "therefore not validated at its stated per-trade risk."
    )


def _rebase_trades(
    trades: Sequence[SimulatedTrade], simulation: SimulationConfig
) -> list[SimulatedTrade]:
    """Chain per-window trades into one coherent fixed-fractional equity curve.

    Every walk-forward window is simulated from ``initial_equity``, so the raw
    per-trade currency amounts belong to different equity regimes and must not
    be summed into a single account: the aggregate drawdown and return would
    then describe a path no account could have followed. Each trade is instead
    re-sized by replaying the simulator's own rule at the running equity — risk
    budget first, ``max_position_notional`` second — and every currency field is
    scaled by that one factor, which leaves ``r_multiple`` invariant. This makes
    the gate drawdown directly comparable to the compounded drawdown modelled by
    ``bootstrap_trade_distribution``.

    Re-deriving the size rather than preserving the trade's realized risk
    fraction matters in exactly one place, and it is the place that decides the
    gate: a cap-bound trade must stay cap-bound while equity falls. Preserving
    the fraction would shrink it, flattering the drawdown.
    """
    equity = simulation.initial_equity
    result: list[SimulatedTrade] = []
    for trade in sorted(trades, key=lambda item: item.entry_time_ms):
        if equity <= ZERO:
            # The reconstructed account is ruined; no further position can be sized.
            break
        scale = ONE
        if (
            trade.equity_before > ZERO
            and trade.quantity > ZERO
            and trade.entry_price > ZERO
            and trade.initial_risk > ZERO
        ):
            # Replay the simulator's own sizing decision at the new equity:
            # risk budget first, notional cap second. Preserving the realized
            # risk fraction instead would shrink an already cap-bound trade
            # whenever equity is below that window's, so a drawdown would make
            # positions smaller than an honest replay and the gate would read a
            # drawdown shallower than the profile actually produces.
            risk_per_unit = trade.initial_risk / trade.quantity
            target = equity * simulation.risk_per_trade_pct / risk_per_unit
            capped = min(
                target, simulation.max_position_notional / trade.entry_price
            )
            scale = max(ZERO, capped) / trade.quantity
        net_pnl = trade.net_pnl * scale
        rebased = replace(
            trade,
            quantity=trade.quantity * scale,
            gross_pnl=trade.gross_pnl * scale,
            fees=trade.fees * scale,
            funding=trade.funding * scale,
            net_pnl=net_pnl,
            initial_risk=trade.initial_risk * scale,
            equity_before=equity,
            equity_after=equity + net_pnl,
        )
        equity = max(ZERO, rebased.equity_after)
        result.append(rebased)
    return result
