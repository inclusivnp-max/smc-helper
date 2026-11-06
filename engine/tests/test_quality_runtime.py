import asyncio
import time
from decimal import Decimal as D

import pytest

from smc_execution.config import ExecutionConfig
from smc_execution.control_plane import SQLiteControlPlane
from smc_execution.engine import ExecutionEngine
from smc_execution.governor import PortfolioGovernor, PortfolioGovernorConfig
from smc_execution.models import Action, ExecutionResult
from smc_execution.quality import ExecutionQualityGuard
from smc_execution.recovery import RecoveryManager
from smc_execution.runtime import TradingRuntime
from smc_execution.supervisor import ProtectionSupervisor

from .fakes import FakeExchange


def result(status: str, shortfall: D | None = None) -> ExecutionResult:
    return ExecutionResult(
        status=status,
        signal_id=f"x-{status}-{time.time_ns()}",
        symbol="BTCUSDT",
        action=Action.LONG,
        requested_qty=D("1"),
        executed_qty=D("0"),
        avg_price=None,
        arrival_price=D("100"),
        implementation_shortfall_bps=shortfall,
        fill_ratio=D("0"),
        elapsed_ms=1,
    )


def test_strategy_filter_rejections_do_not_poison_execution_quality():
    config = ExecutionConfig(
        quality_min_samples=2,
        quality_max_reject_rate=D("0.1"),
    )
    guard = ExecutionQualityGuard(config)
    guard.observe(result("REJECTED_LOW_CONFIDENCE"))
    decision = guard.observe(result("REJECTED_STALE_SIGNAL"))
    assert decision.halt is False
    assert decision.reject_rate == D("0")


def test_execution_failure_rate_opens_circuit_breaker():
    config = ExecutionConfig(
        quality_min_samples=2,
        quality_max_reject_rate=D("0.1"),
    )
    guard = ExecutionQualityGuard(config)
    guard.observe(result("FAILED_SAFE"))
    decision = guard.observe(result("CANCELLED_NO_FILL"))
    assert decision.halt is True


@pytest.mark.asyncio
async def test_governor_halts_on_open_risk_ratio(tmp_path):
    api = FakeExchange()
    api.account = api.account.__class__(
        equity=D("10000"),
        available_balance=D("9000"),
        max_new_position_notional=D("10000"),
        current_open_risk=D("400"),
        position_mode=api.account.position_mode,
    )
    control = SQLiteControlPlane(tmp_path / "state.db")
    governor = PortfolioGovernor(
        api,
        control,
        PortfolioGovernorConfig(max_open_risk_pct=D("0.03")),
    )
    decision = await governor.evaluate_once()
    assert decision.halt is True
    assert "open risk ratio" in (decision.reason or "")


@pytest.mark.asyncio
async def test_runtime_health_monitor_closes_admission_on_stream_loss(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    config = ExecutionConfig()
    supervisor = ProtectionSupervisor(api, control, config, interval_seconds=0.02)
    recovery = RecoveryManager(api, control, supervisor, config)
    governor = PortfolioGovernor(
        api,
        control,
        PortfolioGovernorConfig(interval_seconds=0.05),
    )
    engine = ExecutionEngine(api, control, config)
    runtime = TradingRuntime(
        api,
        control,
        engine,
        supervisor,
        recovery,
        governor,
        startup_stream_timeout_seconds=0.1,
        health_interval_seconds=0.01,
    )
    await runtime.start(["BTCUSDT"])
    api.healthy = False
    await asyncio.sleep(0.05)
    assert (await control.admission_status())[0] is False
    await runtime.stop()


@pytest.mark.asyncio
async def test_runtime_start_fails_closed_and_closes_adapter_when_stream_never_healthy(tmp_path):
    class UnhealthyExchange(FakeExchange):
        def __init__(self):
            super().__init__()
            self.healthy = False
            self.closed = False

        async def close(self):
            self.closed = True

    api = UnhealthyExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    config = ExecutionConfig()
    supervisor = ProtectionSupervisor(api, control, config, interval_seconds=0.02)
    recovery = RecoveryManager(api, control, supervisor, config)
    governor = PortfolioGovernor(api, control, PortfolioGovernorConfig(interval_seconds=0.05))
    engine = ExecutionEngine(api, control, config)
    runtime = TradingRuntime(
        api,
        control,
        engine,
        supervisor,
        recovery,
        governor,
        startup_stream_timeout_seconds=0.02,
        health_interval_seconds=0.01,
    )
    with pytest.raises(RuntimeError, match="private user stream"):
        await runtime.start(["BTCUSDT"])
    assert api.closed is True
    assert runtime._started is False
    assert (await control.admission_status())[0] is False


@pytest.mark.asyncio
async def test_runtime_immediately_contains_failed_uncertain_without_reopening_admission(tmp_path):
    from smc_execution.errors import OrderNotFound
    from smc_execution.models import Signal, ExecutionStage

    class AmbiguousExchange(FakeExchange):
        async def place_order(self, request):
            if request.client_order_id.endswith("MKR"):
                await asyncio.sleep(5)
            return await super().place_order(request)

        async def get_order(self, symbol, order_id, client_order_id, kind):
            if client_order_id.endswith("MKR"):
                raise OrderNotFound(client_order_id)
            return await super().get_order(symbol, order_id, client_order_id, kind)

    api = AmbiguousExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    config = ExecutionConfig(
        estimated_round_trip_fees_bps=D("0"),
        execution_risk_reserve_bps=D("0"),
        default_io_timeout_ms=10,
        cancel_reconcile_timeout_ms=40,
        maker_timeout_ms=100,
        total_entry_timeout_ms=500,
    )
    supervisor = ProtectionSupervisor(api, control, config, interval_seconds=0.05)
    recovery = RecoveryManager(api, control, supervisor, config)
    governor = PortfolioGovernor(api, control, PortfolioGovernorConfig(interval_seconds=0.05))
    engine = ExecutionEngine(api, control, config)
    runtime = TradingRuntime(
        api,
        control,
        engine,
        supervisor,
        recovery,
        governor,
        startup_stream_timeout_seconds=0.1,
        health_interval_seconds=0.02,
    )
    await runtime.start(["BTCUSDT"])
    try:
        outcome = await runtime.submit_signal(
            Signal(
                signal_id="runtime-ambiguous",
                symbol="BTCUSDT",
                action=Action.LONG,
                confidence=D("1"),
                created_at_ms=int(time.time() * 1000),
            )
        )
        assert outcome.status == "FAILED_UNCERTAIN"
        assert any("Immediate containment" in warning for warning in outcome.warnings)
        record = await control.get_execution("runtime-ambiguous")
        assert record is not None and record.stage is ExecutionStage.CLOSED
        assert (await control.risk_totals())["reserved"] == D("0")
        assert (await control.admission_status())[0] is False
    finally:
        await runtime.stop()
