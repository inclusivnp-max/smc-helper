from __future__ import annotations

import json
import random
import sqlite3
import time
from contextlib import closing
from dataclasses import replace
from pathlib import Path

import pytest

from smc_execution.alpha_gate import AlphaGateSettings, GovernedSignalRouter, SignalAdmissionError
from smc_execution.backtest import (
    ATR_PERIOD,
    EventDrivenBacktester,
    _atr,
    _atr_tail,
    calculate_metrics,
)
from smc_execution.models import Action, D, ExecutionResult, ZERO
from smc_execution.research_data import ResearchDataset, validate_bars
from smc_execution.research_models import (
    METRICS_ALGORITHM_VERSION,
    ApprovalStage,
    BootstrapResult,
    GateDecision,
    PerformanceMetrics,
    ResearchBar,
    ResearchReport,
    RunStatus,
    SimulatedTrade,
    SimulationConfig,
    StressScenarioResult,
    StrategyManifest,
    WalkForwardFold,
)
from smc_execution.research_store import ResearchStore
from smc_execution.strategy import SMCCompositeStrategy
from smc_execution.validation import (
    AlphaGatePolicy,
    ResearchRunner,
    WalkForwardConfig,
    evaluate_alpha_gate,
    _rebase_trades,
    realized_risk_fraction,
    sizing_diagnostic,
)


def make_bars(count: int = 500, *, interval_ms: int = 300_000) -> tuple[ResearchBar, ...]:
    bars = []
    price = D("100")
    start = 1_700_000_000_000
    for i in range(count):
        # Alternating waves with a weak positive drift create sweeps and trend changes.
        direction = D("1") if (i // 12) % 2 == 0 else D("-1")
        move = direction * D("0.35") + D("0.03")
        open_price = price
        close = max(D("5"), open_price + move)
        high = max(open_price, close) + D("0.30") + (D("0.60") if i % 17 == 0 else ZERO)
        low = min(open_price, close) - D("0.30") - (D("0.60") if i % 19 == 0 else ZERO)
        open_time = start + i * interval_ms
        close_time = open_time + interval_ms - 1
        bars.append(
            ResearchBar(
                symbol="BTCUSDT",
                interval="5m",
                open_time_ms=open_time,
                close_time_ms=close_time,
                open=open_price,
                high=high,
                low=low,
                close=close,
                volume=D("100") + D(str(i % 20)) * D("5"),
                quote_volume=(D("100") + D(str(i % 20)) * D("5")) * close,
                trades=100 + i,
                open_interest=D("100000") + D(str(i * 10)),
                open_interest_available_at_ms=close_time,
                funding_rate=D("0.0001") if i % 96 == 0 else None,
                funding_available_at_ms=close_time if i % 96 == 0 else None,
            )
        )
        price = close
    return tuple(bars)


class OneShotStrategy:
    def __init__(self) -> None:
        self._manifest = StrategyManifest.create("one_shot", "1", {})

    @property
    def manifest(self):
        return self._manifest

    @property
    def warmup_bars(self):
        return 3

    def with_parameters(self, parameters):
        return self

    def decide(self, history):
        if len(history) != 3:
            return None
        bar = history[-1]
        from smc_execution.research_models import SignalDecision

        return SignalDecision(
            Action.LONG,
            D("0.9"),
            "test",
            bar.close_time_ms,
            bar.close,
            {},
        )


def test_data_quality_and_feature_availability() -> None:
    bars = list(make_bars(10))
    future = bars[5]
    bars[5] = ResearchBar(
        **{
            **future.__dict__,
            "open_interest_available_at_ms": future.close_time_ms + 1,
        }
    )
    report = validate_bars(bars)
    assert report.passed
    assert report.unavailable_future_features == 1
    assert bars[5].feature_open_interest() is None


def test_smc_strategy_never_uses_future_feature_time() -> None:
    strategy = SMCCompositeStrategy()
    bars = make_bars(strategy.warmup_bars + 50)
    for i in range(strategy.warmup_bars, len(bars)):
        decision = strategy.decide(bars[: i + 1])
        if decision is not None:
            assert decision.feature_time_ms <= bars[i].close_time_ms


def test_backtester_uses_conservative_same_bar_exit() -> None:
    bars = list(make_bars(6))
    # The signal is produced on index 2 and fallback entry occurs at index 3 open.
    entry_open = bars[3].open
    bars[3] = ResearchBar(
        **{
            **bars[3].__dict__,
            "high": entry_open + D("20"),
            "low": entry_open - D("20"),
        }
    )
    result = EventDrivenBacktester(
        SimulationConfig(
            maker_wait_bars=0,
            entry_latency_bars=1,
            stop_atr_multiple=D("1"),
            take_profit_atr_multiple=D("1.5"),
            min_confidence=D("0.5"),
            max_holding_bars=3,
            max_adverse_slippage_bps=D("20"),
        )
    ).run(bars, OneShotStrategy())
    assert len(result.trades) == 1
    assert result.trades[0].exit_reason == "STOP_SAME_BAR_AMBIGUITY"


def test_research_runner_produces_oos_report() -> None:
    bars = make_bars(800)
    quality = validate_bars(bars)
    dataset = ResearchDataset(
        "synthetic",
        "BTCUSDT",
        "5m",
        bars,
        "fingerprint",
        quality,
        "unit-test",
    )
    runner = ResearchRunner(
        simulation=SimulationConfig(min_confidence=D("0.5"), max_holding_bars=20),
        walk_forward=WalkForwardConfig(
            train_bars=350,
            test_bars=150,
            step_bars=150,
            purge_bars=20,
            embargo_bars=5,
            min_train_trades=1,
        ),
        bootstrap_samples=100,
    )
    report = runner.run(dataset, SMCCompositeStrategy(), parameter_grid=({},))
    assert report.status is RunStatus.COMPLETED
    assert report.metrics is not None
    assert len(report.folds) >= 1
    assert report.bootstrap is not None
    assert set(report.baselines) == {
        "baseline_trend",
        "baseline_mean_reversion",
        "baseline_random",
    }


def passed_report(dataset_id: str, fingerprint: str) -> ResearchReport:
    manifest = StrategyManifest.create("approved", "1.0", {})
    metrics = PerformanceMetrics(
        trades=100,
        wins=55,
        losses=45,
        breakeven=0,
        win_rate=D("0.55"),
        net_pnl=D("100"),
        return_pct=D("0.01"),
        expectancy_r=D("0.10"),
        avg_win_r=D("1"),
        avg_loss_r=D("-0.8"),
        profit_factor=D("1.3"),
        max_drawdown_pct=D("0.08"),
        sharpe_per_trade=D("1"),
        sortino_per_trade=D("1"),
        calmar=D("0.1"),
        total_fees=D("10"),
        total_funding=ZERO,
        maker_ratio=D("0.8"),
        avg_holding_bars=D("4"),
        median_r=D("0.1"),
        best_trade_r=D("2"),
        worst_trade_r=D("-1"),
    )
    return ResearchReport(
        run_id="passed-run",
        dataset_id=dataset_id,
        dataset_fingerprint=fingerprint,
        manifest=manifest,
        simulation=SimulationConfig(profile="CAPITAL_PROTECTION"),
        status=RunStatus.COMPLETED,
        created_at_ms=1,
        completed_at_ms=2,
        metrics=metrics,
        bootstrap=BootstrapResult(100, D("0.95"), D("0.01"), D("0.1"), D("0.2"), D("0.05"), D("0.1")),
        stress=(),
        folds=(),
        baselines={},
        gate=GateDecision(True, (), D("90")),
    )


@pytest.mark.asyncio
async def test_research_store_roundtrip_and_approval(tmp_path: Path) -> None:
    store = ResearchStore(tmp_path / "research.db")
    await store.initialize()
    bars = make_bars(20)
    dataset = ResearchDataset(
        "stored",
        "BTCUSDT",
        "5m",
        bars,
        "stored-fingerprint",
        validate_bars(bars),
        "unit-test",
    )
    await store.save_dataset(dataset)
    loaded = await store.get_dataset("stored")
    assert loaded is not None
    assert loaded.bars == bars
    report = passed_report(dataset.dataset_id, dataset.fingerprint)
    await store.save_report(report)
    approval = await store.promote(
        run_id=report.run_id,
        stage=ApprovalStage.TESTNET,
        environment="testnet",
        actor="tester",
        reason="unit test",
    )
    assert approval.active
    assert (await store.latest_approval("approved", "1.0", "testnet")) == approval


class ParseEngine:
    class _Config:
        atr_timeframe = "5m"

    config = _Config()


class _FakeControl:
    async def get_system_value(self, key):
        assert key == "trading_mode"
        return {"active_mode": "CAPITAL_PROTECTION"}


class FakeRuntime:
    def __init__(self):
        self.engine = ParseEngine()
        self.control = _FakeControl()
        self.received = None

    async def submit_signal(self, signal):
        self.received = signal
        return ExecutionResult(
            "FILLED_PROTECTED",
            signal.signal_id,
            signal.symbol,
            signal.action,
            ZERO,
            ZERO,
            None,
            ZERO,
            None,
            ZERO,
            0,
        )


@pytest.mark.asyncio
async def test_governed_router_requires_matching_fingerprint(tmp_path: Path) -> None:
    store = ResearchStore(tmp_path / "research.db")
    await store.initialize()
    bars = make_bars(20)
    dataset = ResearchDataset(
        "stored",
        "BTCUSDT",
        "5m",
        bars,
        "stored-fingerprint",
        validate_bars(bars),
        "unit-test",
    )
    await store.save_dataset(dataset)
    report = passed_report(dataset.dataset_id, dataset.fingerprint)
    await store.save_report(report)
    await store.promote(
        run_id=report.run_id,
        stage=ApprovalStage.TESTNET,
        environment="testnet",
        actor="tester",
        reason="unit test",
    )
    runtime = FakeRuntime()
    router = GovernedSignalRouter(runtime, store, AlphaGateSettings("testnet"))
    payload = {
        "signal_id": "governed-1",
        "symbol": "BTCUSDT",
        "action": "LONG",
        "confidence": "0.9",
        "created_at_ms": int(time.time() * 1000),
        "strategy_id": report.manifest.strategy_id,
        "strategy_version": report.manifest.version,
        "research_run_id": report.run_id,
        "code_fingerprint": "wrong",
        "metadata": {"interval": "5m"},
    }
    with pytest.raises(SignalAdmissionError):
        await router.submit(payload)
    payload["code_fingerprint"] = report.manifest.code_fingerprint
    result = await router.submit(payload)
    assert result.status == "FILLED_PROTECTED"
    assert runtime.received.metadata["alpha_gate_stage"] == "TESTNET"

    payload["signal_id"] = "governed-wrong-symbol"
    payload["symbol"] = "ETHUSDT"
    with pytest.raises(SignalAdmissionError, match="approved for BTCUSDT"):
        await router.submit(payload)


def test_data_quality_rejects_time_gaps() -> None:
    bars = list(make_bars(8))
    skipped = bars.pop(4)
    report = validate_bars(bars)
    assert not report.passed
    assert report.gaps >= 1
    assert skipped.open_time_ms not in {bar.open_time_ms for bar in bars}


@pytest.mark.asyncio
async def test_research_store_datasets_are_immutable(tmp_path: Path) -> None:
    store = ResearchStore(tmp_path / "immutable.db")
    await store.initialize()
    bars = make_bars(20)
    first = ResearchDataset(
        "immutable",
        "BTCUSDT",
        "5m",
        bars,
        "fingerprint-one",
        validate_bars(bars),
        "unit-test",
    )
    await store.save_dataset(first)
    await store.save_dataset(first)  # exact retry is idempotent
    changed = ResearchDataset(
        "immutable",
        "BTCUSDT",
        "5m",
        bars,
        "fingerprint-two",
        validate_bars(bars),
        "unit-test",
    )
    with pytest.raises(ValueError, match="immutable"):
        await store.save_dataset(changed)


@pytest.mark.asyncio
async def test_governed_router_rejects_runtime_profile_mismatch(tmp_path: Path) -> None:
    store = ResearchStore(tmp_path / "profile.db")
    await store.initialize()
    bars = make_bars(20)
    dataset = ResearchDataset(
        "profile-data",
        "BTCUSDT",
        "5m",
        bars,
        "profile-fingerprint",
        validate_bars(bars),
        "unit-test",
    )
    await store.save_dataset(dataset)
    report = passed_report(dataset.dataset_id, dataset.fingerprint)
    await store.save_report(report)
    await store.promote(
        run_id=report.run_id,
        stage=ApprovalStage.TESTNET,
        environment="testnet",
        actor="tester",
        reason="unit test",
    )
    runtime = FakeRuntime()

    async def wrong_mode(_):
        return {"active_mode": "PROFITABILITY"}

    runtime.control.get_system_value = wrong_mode
    router = GovernedSignalRouter(runtime, store, AlphaGateSettings("testnet"))
    with pytest.raises(SignalAdmissionError, match="Research profile"):
        await router.submit(
            {
                "signal_id": "wrong-mode",
                "symbol": "BTCUSDT",
                "action": "LONG",
                "confidence": "0.9",
                "created_at_ms": int(time.time() * 1000),
                "strategy_id": report.manifest.strategy_id,
                "strategy_version": report.manifest.version,
                "research_run_id": report.run_id,
                "code_fingerprint": report.manifest.code_fingerprint,
                "metadata": {"interval": "5m"},
            }
        )


def _flat_bar(index: int, open_price: D, high: D, low: D, close: D) -> ResearchBar:
    start = 1_800_000_000_000 + index * 300_000
    return ResearchBar(
        symbol="BTCUSDT",
        interval="5m",
        open_time_ms=start,
        close_time_ms=start + 299_999,
        open=open_price,
        high=high,
        low=low,
        close=close,
        volume=D("100"),
        quote_volume=D("10000"),
        trades=100,
    )


def test_favorable_fallback_move_is_not_counted_as_adverse_slippage() -> None:
    bars = (
        _flat_bar(0, D("100"), D("100"), D("100"), D("100")),
        _flat_bar(1, D("100"), D("100"), D("100"), D("100")),
        _flat_bar(2, D("90"), D("90"), D("90"), D("90")),
    )
    backtester = EventDrivenBacktester(
        SimulationConfig(
            entry_latency_bars=1,
            maker_wait_bars=1,
            maker_offset_bps=D("1"),
            aggressive_fallback_bps=D("5"),
            max_adverse_slippage_bps=D("8"),
        )
    )
    entry = backtester._resolve_entry(
        bars, 0, len(bars), Action.LONG, "favorable-fallback"
    )
    assert entry is not None
    assert entry[0] == 2
    assert entry[2] == "TAKER"
    assert entry[1] < D("100")


def test_stop_gap_uses_open_through_price_plus_stop_slippage() -> None:
    bar = _flat_bar(0, D("98"), D("99"), D("97"), D("98"))
    backtester = EventDrivenBacktester(
        SimulationConfig(stop_slippage_bps=D("10"), max_holding_bars=1)
    )
    risk_basis = backtester._planned_risk_per_unit(
        Action.LONG, D("100"), D("99"), "TAKER"
    )
    trade = backtester._simulate_exit(
        (bar,),
        0,
        1,
        Action.LONG,
        D("100"),
        D("99"),
        D("105"),
        D("1"),
        "TAKER",
        D("0.9"),
        D("10000"),
        risk_basis,
        trade_id="gap-stop",
        signal_time_ms=bar.open_time_ms - 1,
    )
    assert trade.exit_reason == "STOP_GAP"
    assert trade.exit_price == D("97.902")
    assert trade.exit_price < bar.open


def test_cost_aware_sizing_keeps_normal_stop_within_risk_budget() -> None:
    bar = _flat_bar(0, D("100"), D("100"), D("98.5"), D("99"))
    backtester = EventDrivenBacktester(
        SimulationConfig(
            stop_slippage_bps=D("5"),
            maker_fee_bps=D("2"),
            taker_fee_bps=D("5"),
            max_holding_bars=1,
        )
    )
    risk_budget = D("30")
    risk_basis = backtester._planned_risk_per_unit(
        Action.LONG, D("100"), D("99"), "TAKER"
    )
    quantity = risk_budget / risk_basis
    trade = backtester._simulate_exit(
        (bar,),
        0,
        1,
        Action.LONG,
        D("100"),
        D("99"),
        D("105"),
        quantity,
        "TAKER",
        D("0.9"),
        D("10000"),
        risk_basis,
        trade_id="cost-aware-risk",
        signal_time_ms=bar.open_time_ms - 1,
    )
    assert trade.exit_reason == "STOP"
    assert abs(trade.net_pnl + risk_budget) < D("0.0000001")
    assert abs(trade.r_multiple + D("1")) < D("0.0000001")


def test_baseline_comparison_does_not_depend_on_smc_confidence_scale() -> None:
    from smc_execution.validation import run_baselines_oos

    bars = make_bars(800)
    metrics = run_baselines_oos(
        bars,
        SimulationConfig(min_confidence=D("0.99"), max_holding_bars=20),
        WalkForwardConfig(
            train_bars=350,
            test_bars=150,
            step_bars=150,
            purge_bars=20,
            embargo_bars=5,
            min_train_trades=1,
        ),
    )
    assert metrics["baseline_random"].trades > 0


def simulated_trade(
    *,
    trade_id: str,
    entry_time_ms: int,
    net_pnl: D,
    initial_risk: D,
    equity_before: D,
    quantity: D = D("1"),
    entry_price: D = D("100"),
) -> SimulatedTrade:
    return SimulatedTrade(
        trade_id=trade_id,
        symbol="BTCUSDT",
        action=Action.LONG,
        signal_time_ms=entry_time_ms - 1,
        entry_time_ms=entry_time_ms,
        exit_time_ms=entry_time_ms + 1,
        entry_price=entry_price,
        exit_price=entry_price + net_pnl / quantity,
        stop_price_initial=entry_price - D("1"),
        take_profit_price_initial=entry_price + D("2"),
        quantity=quantity,
        gross_pnl=net_pnl,
        fees=ZERO,
        funding=ZERO,
        net_pnl=net_pnl,
        initial_risk=initial_risk,
        r_multiple=net_pnl / initial_risk,
        holding_bars=1,
        exit_reason="TAKE_PROFIT",
        entry_liquidity="MAKER",
        confidence=D("0.80"),
        equity_before=equity_before,
        equity_after=equity_before + net_pnl,
        max_favorable_r=ZERO,
        max_adverse_r=ZERO,
    )


def test_atr_tail_matches_the_full_prefix_slice() -> None:
    """The linear-time ATR helper must be numerically identical to the old slice."""
    bars = make_bars(120)
    for end in (0, 1, 2, 3, ATR_PERIOD, ATR_PERIOD + 1, ATR_PERIOD + 2, 60, 120):
        assert _atr_tail(bars, end) == _atr(bars[:end], ATR_PERIOD), end


def test_rebase_chains_windows_into_one_fixed_fractional_account() -> None:
    """Each walk-forward window restarts at initial_equity; the aggregate must not."""
    simulation = SimulationConfig(
        initial_equity=D("10000"), max_position_notional=D("1000000")
    )
    first = simulated_trade(
        trade_id="w1",
        entry_time_ms=1_000,
        net_pnl=D("1000"),
        initial_risk=D("30"),
        equity_before=D("10000"),
    )
    second = simulated_trade(
        trade_id="w2",
        entry_time_ms=2_000,
        net_pnl=D("1000"),
        initial_risk=D("30"),
        equity_before=D("10000"),
    )

    rebased = _rebase_trades([first, second], simulation)

    assert [trade.trade_id for trade in rebased] == ["w1", "w2"]
    # Only the currency scale changes; the R outcome of each trade is untouched.
    assert [trade.r_multiple for trade in rebased] == [first.r_multiple, second.r_multiple]
    assert rebased[0].equity_before == D("10000")
    assert rebased[0].equity_after == D("11000")
    # The second window is replayed at the grown equity, so its size and PnL grow.
    assert rebased[1].equity_before == D("11000")
    assert rebased[1].net_pnl == D("1100")
    assert rebased[1].initial_risk == D("33")
    # Naively summing the raw per-window PnL would have ended at 12000 instead.
    assert rebased[1].equity_after == D("12100")


def test_rebase_keeps_a_cap_bound_trade_at_the_cap_while_equity_falls() -> None:
    """A drawdown must not shrink a position the notional cap already limits.

    Re-sizing from the trade's realized risk fraction would do exactly that, and
    since the fraction of a capped trade is itself cap-reduced, every loss would
    make the next position smaller than an honest replay — reporting a drawdown
    shallower than the profile really produces, straight into the gate.
    """
    simulation = SimulationConfig(
        initial_equity=D("10000"),
        risk_per_trade_pct=D("0.10"),
        max_position_notional=D("2000"),
    )
    # risk_per_unit = 25, so the risk budget alone would buy 40 units while the
    # cap allows 2000/100 = 20: every trade here is decided by the cap.
    losses = [
        simulated_trade(
            trade_id=f"w{index}",
            entry_time_ms=1_000 + index,
            net_pnl=D("-500"),
            initial_risk=D("500"),
            equity_before=D("10000"),
            quantity=D("20"),
            entry_price=D("100"),
        )
        for index in range(3)
    ]

    rebased = _rebase_trades(losses, simulation)

    # The cap still binds at 9500 and at 9000, so nothing shrinks.
    assert [trade.quantity for trade in rebased] == [D("20")] * 3
    assert [trade.net_pnl for trade in rebased] == [D("-500")] * 3
    assert rebased[-1].equity_after == D("8500")
    # 15% is the honest drawdown; the realized-fraction rebase reported 14.26%.
    assert calculate_metrics(rebased, simulation.initial_equity).max_drawdown_pct == D(
        "0.15"
    )


def test_rebase_reapplies_the_notional_cap_at_the_new_equity() -> None:
    simulation = SimulationConfig(
        initial_equity=D("10000"), max_position_notional=D("105")
    )
    first = simulated_trade(
        trade_id="w1",
        entry_time_ms=1_000,
        net_pnl=D("5000"),
        initial_risk=D("30"),
        equity_before=D("10000"),
    )
    second = simulated_trade(
        trade_id="w2",
        entry_time_ms=2_000,
        net_pnl=D("100"),
        initial_risk=D("30"),
        equity_before=D("10000"),
    )

    rebased = _rebase_trades([first, second], simulation)

    # Equity grew 1.5x but the exchange notional cap allows only 1.05 units.
    assert rebased[1].quantity == D("1.05")
    assert rebased[1].quantity * rebased[1].entry_price == simulation.max_position_notional
    assert rebased[1].net_pnl == D("105")
    assert rebased[1].r_multiple == second.r_multiple


def test_walk_forward_rejects_overlapping_out_of_sample_windows() -> None:
    with pytest.raises(ValueError, match="overlap"):
        WalkForwardConfig(
            train_bars=400, test_bars=150, step_bars=100, embargo_bars=0
        )


def test_sizing_diagnostic_reports_when_the_notional_cap_decided_size() -> None:
    simulation = SimulationConfig(
        initial_equity=D("10000"),
        risk_per_trade_pct=D("0.003"),
        max_position_notional=D("100"),
    )
    capped = simulated_trade(
        trade_id="w1",
        entry_time_ms=1_000,
        net_pnl=D("10"),
        initial_risk=D("15"),
        equity_before=D("10000"),
    )

    note = sizing_diagnostic([capped], simulation)

    assert note is not None
    assert "max_position_notional" in note
    assert "0.1500%" in note
    assert "0.3000%" in note
    assert realized_risk_fraction(
        [capped], simulation.risk_per_trade_pct
    ) == D("0.0015")


def test_sizing_diagnostic_is_silent_when_the_risk_profile_binds() -> None:
    simulation = SimulationConfig(max_position_notional=D("1000000"))
    trade = simulated_trade(
        trade_id="w1",
        entry_time_ms=1_000,
        net_pnl=D("10"),
        initial_risk=D("30"),
        equity_before=D("10000"),
    )

    assert sizing_diagnostic([trade], simulation) is None


def make_volatile_bars(count: int = 2000, *, seed: int = 11) -> tuple[ResearchBar, ...]:
    """A deterministic random walk the SMC strategy actually trades.

    ``make_bars`` produces a regular sawtooth on which the strategy emits zero
    signals, so a runner driven by it aggregates nothing but empty objects.
    """
    rng = random.Random(seed)
    bars: list[ResearchBar] = []
    price = 30000.0
    start = 1_600_000_000_000
    for index in range(count):
        open_price = price
        close = max(1.0, open_price * (1 + rng.gauss(0, 0.0035)))
        high = max(open_price, close) * (1 + abs(rng.gauss(0, 0.0012)))
        low = min(open_price, close) * (1 - abs(rng.gauss(0, 0.0012)))
        open_time = start + index * 300_000
        bars.append(
            ResearchBar(
                symbol="BTCUSDT",
                interval="5m",
                open_time_ms=open_time,
                close_time_ms=open_time + 299_999,
                open=D(str(round(open_price, 2))),
                high=D(str(round(high, 2))),
                low=D(str(round(low, 2))),
                close=D(str(round(close, 2))),
                volume=D(str(round(abs(rng.gauss(500, 150)), 3))),
                quote_volume=ZERO,
                trades=100,
            )
        )
        price = close
    return tuple(bars)


def test_research_runner_aggregates_non_empty_trades_end_to_end() -> None:
    bars = make_volatile_bars()
    dataset = ResearchDataset(
        "volatile", "BTCUSDT", "5m", bars, "volatile-fp", validate_bars(bars), "unit-test"
    )
    runner = ResearchRunner(
        simulation=SimulationConfig(min_confidence=D("0.5"), max_holding_bars=20),
        walk_forward=WalkForwardConfig(
            train_bars=350,
            test_bars=150,
            step_bars=150,
            purge_bars=20,
            embargo_bars=5,
            min_train_trades=1,
        ),
        bootstrap_samples=200,
    )

    report = runner.run(dataset, SMCCompositeStrategy(), parameter_grid=({},))

    assert report.status is RunStatus.COMPLETED
    assert len(report.folds) >= 2
    # The guard the suite lacked: without it the whole aggregate path can go
    # degenerate again without a single test turning red.
    assert report.metrics is not None and report.metrics.trades > 0
    assert report.bootstrap is not None and report.bootstrap.samples == 200
    # A real chained equity curve has a real drawdown.
    assert report.metrics.max_drawdown_pct > ZERO
    # Bound to a measured input rather than restating how GateDecision is built:
    # this dataset yields far fewer than the 80 OOS trades the policy demands.
    assert report.gate is not None
    assert report.metrics.trades < AlphaGatePolicy().min_oos_trades
    assert report.gate.passed is False
    assert any("OOS trades" in reason for reason in report.gate.reasons)
    # The default profile is notional-capped, so the sizing warning must fire.
    assert any("max_position_notional" in warning for warning in report.warnings)
    assert report.metrics_algorithm_version == METRICS_ALGORITHM_VERSION


# --- alpha gate: derived, not fixtured -------------------------------------
#
# Every other governance test feeds a hand-written GateDecision(True), so until
# now nothing in the suite ever made evaluate_alpha_gate produce a verdict.


def gate_metrics(**overrides) -> PerformanceMetrics:
    base = passed_report("gate", "gate").metrics
    assert base is not None
    return replace(base, **overrides)


def gate_fold(number: int, expectancy_r: D) -> WalkForwardFold:
    metrics = gate_metrics(expectancy_r=expectancy_r)
    return WalkForwardFold(
        fold=number,
        train_start_ms=number * 1000,
        train_end_ms=number * 1000 + 500,
        test_start_ms=number * 1000 + 600,
        test_end_ms=number * 1000 + 900,
        selected_parameters={},
        train_metrics=metrics,
        test_metrics=metrics,
    )


def gate_inputs() -> dict:
    """Inputs that satisfy every default alpha-gate criterion."""
    return {
        "metrics": gate_metrics(
            trades=100,
            expectancy_r=D("0.10"),
            profit_factor=D("1.30"),
            max_drawdown_pct=D("0.08"),
            return_pct=D("0.01"),
        ),
        "bootstrap": BootstrapResult(
            2000, D("0.95"), D("0.01"), D("0.1"), D("0.2"), D("0.05"), D("0.1")
        ),
        "stress": tuple(
            StressScenarioResult(
                name=f"scenario_{index}",
                net_pnl=D("10"),
                profit_factor=D("1.2"),
                expectancy_r=D("0.05"),
                max_drawdown_pct=D("0.05"),
                passed=index < 4,
            )
            for index in range(5)
        ),
        "baselines": {"baseline_trend": gate_metrics(expectancy_r=D("0.05"))},
        "folds": (gate_fold(1, D("0.10")), gate_fold(2, D("0.08"))),
        "policy": AlphaGatePolicy(),
    }


def test_alpha_gate_passes_only_when_every_criterion_holds() -> None:
    decision = evaluate_alpha_gate(**gate_inputs())
    assert decision.reasons == ()
    assert decision.passed is True
    assert decision.score == D("100")


def test_alpha_gate_rejects_excess_drawdown() -> None:
    """The drawdown threshold is the criterion the 5.0.1 aggregate rework moves."""
    inputs = gate_inputs()
    inputs["metrics"] = replace(inputs["metrics"], max_drawdown_pct=D("0.20"))

    decision = evaluate_alpha_gate(**inputs)

    assert decision.passed is False
    assert any("max drawdown" in reason for reason in decision.reasons)
    assert decision.score < D("100")


def test_alpha_gate_rejects_a_strategy_that_only_matches_its_baseline() -> None:
    inputs = gate_inputs()
    inputs["baselines"] = {"baseline_trend": gate_metrics(expectancy_r=D("0.095"))}

    decision = evaluate_alpha_gate(**inputs)

    assert decision.passed is False
    assert any("baseline" in reason for reason in decision.reasons)


def test_alpha_gate_rejects_too_few_out_of_sample_trades() -> None:
    inputs = gate_inputs()
    inputs["metrics"] = replace(inputs["metrics"], trades=50)

    decision = evaluate_alpha_gate(**inputs)

    assert decision.passed is False
    assert any("OOS trades" in reason for reason in decision.reasons)


# --- stored reports carry the metrics semantics that produced them ----------


@pytest.mark.asyncio
async def test_promotion_refuses_a_report_from_older_metrics_semantics(
    tmp_path: Path,
) -> None:
    store = ResearchStore(tmp_path / "research.db")
    await store.initialize()
    bars = make_bars(20)
    dataset = ResearchDataset(
        "stale", "BTCUSDT", "5m", bars, "stale-fingerprint", validate_bars(bars), "unit-test"
    )
    await store.save_dataset(dataset)
    stale = replace(
        passed_report(dataset.dataset_id, dataset.fingerprint),
        metrics_algorithm_version=METRICS_ALGORITHM_VERSION - 1,
    )
    await store.save_report(stale)

    with pytest.raises(ValueError, match="metrics algorithm version"):
        await store.promote(
            run_id=stale.run_id,
            stage=ApprovalStage.RESEARCH_PASSED,
            environment="testnet",
            actor="tester",
            reason="unit test",
        )


@pytest.mark.asyncio
async def test_a_pre_upgrade_approval_can_still_be_revoked(tmp_path: Path) -> None:
    """De-authorising must never be gated on the evidence it retires."""
    path = tmp_path / "revoke.db"
    store = ResearchStore(path)
    await store.initialize()
    bars = make_bars(20)
    dataset = ResearchDataset(
        "revoke", "BTCUSDT", "5m", bars, "revoke-fp", validate_bars(bars), "unit-test"
    )
    await store.save_dataset(dataset)
    report = passed_report(dataset.dataset_id, dataset.fingerprint)
    await store.save_report(report)
    await store.promote(
        run_id=report.run_id,
        stage=ApprovalStage.TESTNET,
        environment="testnet",
        actor="tester",
        reason="unit test",
    )
    stored = replace(report, metrics_algorithm_version=METRICS_ALGORITHM_VERSION - 1)
    with closing(sqlite3.connect(path)) as connection:
        connection.execute(
            "UPDATE research_runs SET report_json = ? WHERE run_id = ?",
            (json.dumps(stored.to_dict(), sort_keys=True), report.run_id),
        )
        connection.commit()

    revoked = await store.promote(
        run_id=report.run_id,
        stage=ApprovalStage.REVOKED,
        environment="testnet",
        actor="tester",
        reason="retire stale evidence",
    )

    assert revoked.stage is ApprovalStage.REVOKED
    assert not revoked.active
    latest = await store.latest_approval("approved", "1.0", "testnet")
    assert latest is not None and latest.stage is ApprovalStage.REVOKED


@pytest.mark.asyncio
async def test_router_refuses_an_approval_whose_report_predates_the_upgrade(
    tmp_path: Path,
) -> None:
    """Approvals are durable, so an upgrade must not keep honouring old evidence."""
    path = tmp_path / "upgraded.db"
    store = ResearchStore(path)
    await store.initialize()
    bars = make_bars(20)
    dataset = ResearchDataset(
        "upgraded",
        "BTCUSDT",
        "5m",
        bars,
        "upgraded-fingerprint",
        validate_bars(bars),
        "unit-test",
    )
    await store.save_dataset(dataset)
    report = passed_report(dataset.dataset_id, dataset.fingerprint)
    await store.save_report(report)
    await store.promote(
        run_id=report.run_id,
        stage=ApprovalStage.TESTNET,
        environment="testnet",
        actor="tester",
        reason="unit test",
    )

    # Simulate a database written before the metric semantics changed: the
    # approval row survives the upgrade, the report row is from the old build.
    stored = replace(report, metrics_algorithm_version=METRICS_ALGORITHM_VERSION - 1)
    with closing(sqlite3.connect(path)) as connection:
        connection.execute(
            "UPDATE research_runs SET report_json = ? WHERE run_id = ?",
            (json.dumps(stored.to_dict(), sort_keys=True), report.run_id),
        )
        connection.commit()

    router = GovernedSignalRouter(FakeRuntime(), store, AlphaGateSettings("testnet"))
    with pytest.raises(SignalAdmissionError, match="metrics algorithm version"):
        await router.submit(
            {
                "signal_id": "stale-evidence",
                "symbol": "BTCUSDT",
                "action": "LONG",
                "confidence": "0.9",
                "created_at_ms": int(time.time() * 1000),
                "strategy_id": report.manifest.strategy_id,
                "strategy_version": report.manifest.version,
                "research_run_id": report.run_id,
                "code_fingerprint": report.manifest.code_fingerprint,
                "metadata": {"interval": "5m"},
            }
        )
