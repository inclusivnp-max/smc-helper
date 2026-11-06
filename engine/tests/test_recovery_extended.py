import time
from decimal import Decimal as D

import pytest

from smc_execution.config import ExecutionConfig
from smc_execution.control_plane import SQLiteControlPlane
from smc_execution.errors import CriticalExecutionError
from smc_execution.models import (
    Action,
    ExecutionStage,
    PositionSnapshot,
    Signal,
)
from smc_execution.recovery import RecoveryManager
from smc_execution.supervisor import ProtectionSupervisor

from .fakes import FakeExchange
from .test_supervisor_recovery import protected_record


@pytest.mark.asyncio
async def test_recovery_reconstructs_protection_after_crash_post_fill(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    sig = Signal("crash-after-fill", "BTCUSDT", Action.LONG, D("1"), int(time.time() * 1000))
    await control.claim(sig)
    await control.reserve_risk(
        sig.signal_id, D("10"), max_total_risk=D("100"), external_open_risk=D("0")
    )
    await control.transition(
        sig.signal_id,
        None,
        ExecutionStage.ENTRY_OPEN,
        {
            "atr": "1",
            "position_mode": "ONEWAY",
            "rules": {
                "symbol": "BTCUSDT",
                "tick_size": "0.1",
                "step_size": "0.001",
                "min_qty": "0.001",
                "max_qty": "1000",
                "min_notional": "5",
                "max_notional": "100000",
                "status": "TRADING",
            },
        },
    )
    api.position = PositionSnapshot(
        symbol="BTCUSDT",
        position_mode=api.account.position_mode,
        position_side="BOTH",
        quantity=D("2"),
        entry_price=D("1000"),
        mark_price=D("1000"),
        update_time_ms=int(time.time() * 1000),
    )
    config = ExecutionConfig(
        estimated_round_trip_fees_bps=D("0"),
        execution_risk_reserve_bps=D("0"),
    )
    supervisor = ProtectionSupervisor(api, control, config)
    recovery = RecoveryManager(api, control, supervisor, config)
    report = await recovery.recover_startup()

    assert report.unresolved == 0
    record = await control.get_execution(sig.signal_id)
    assert record is not None and record.stage is ExecutionStage.PROTECTED
    assert record.payload["protection"]["stop_client_order_id"]
    assert (await control.admission_status())[0] is True


@pytest.mark.asyncio
async def test_recovery_halts_on_orphan_position(tmp_path):
    api = FakeExchange()
    api.position = PositionSnapshot(
        symbol="BTCUSDT",
        position_mode=api.account.position_mode,
        position_side="BOTH",
        quantity=D("1"),
        entry_price=D("1000"),
        mark_price=D("1000"),
        update_time_ms=int(time.time() * 1000),
    )
    control = SQLiteControlPlane(tmp_path / "state.db")
    supervisor = ProtectionSupervisor(api, control)
    recovery = RecoveryManager(api, control, supervisor)
    report = await recovery.recover_startup()
    assert report.unresolved == 1
    assert "orphan position" in " ".join(report.messages)
    assert (await control.admission_status())[0] is False


@pytest.mark.asyncio
async def test_supervisor_updates_committed_risk_after_position_reduction(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    await protected_record(control, api)
    api.position = PositionSnapshot(
        symbol="BTCUSDT",
        position_mode=api.account.position_mode,
        position_side="BOTH",
        quantity=D("1"),
        entry_price=D("100"),
        mark_price=D("100"),
        update_time_ms=int(time.time() * 1000),
    )
    config = ExecutionConfig(
        estimated_round_trip_fees_bps=D("0"),
        execution_risk_reserve_bps=D("0"),
    )
    await ProtectionSupervisor(api, control, config).reconcile_once()
    totals = await control.risk_totals()
    assert totals["committed"] == D("2")


@pytest.mark.asyncio
async def test_supervisor_rejects_one_way_direction_hijack(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    await protected_record(control, api)
    api.position = PositionSnapshot(
        symbol="BTCUSDT",
        position_mode=api.account.position_mode,
        position_side="BOTH",
        quantity=D("-2"),
        entry_price=D("100"),
        mark_price=D("100"),
        update_time_ms=int(time.time() * 1000),
    )
    with pytest.raises(CriticalExecutionError):
        await ProtectionSupervisor(api, control).reconcile_once()


@pytest.mark.asyncio
async def test_quantity_stop_is_resized_with_position(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    await protected_record(control, api)
    config = ExecutionConfig(
        stop_close_position=False,
        estimated_round_trip_fees_bps=D("0"),
        execution_risk_reserve_bps=D("0"),
    )
    await ProtectionSupervisor(api, control, config).reconcile_once()
    replacements = [r for r in api.requests if "SLR" in r.client_order_id]
    assert replacements and replacements[-1].quantity == D("2")
    assert "old-stop" in api.cancelled
