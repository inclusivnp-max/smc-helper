import time
from decimal import Decimal as D

import pytest

from smc_execution.config import ExecutionConfig
from smc_execution.control_plane import SQLiteControlPlane
from smc_execution.models import (
    Action,
    ExecutionStage,
    OrderKind,
    OrderRequest,
    PositionSnapshot,
    Signal,
    Side,
)
from smc_execution.recovery import RecoveryManager
from smc_execution.supervisor import ProtectionSupervisor

from .fakes import FakeExchange


async def protected_record(control: SQLiteControlPlane, api: FakeExchange):
    sig = Signal("protected", "BTCUSDT", Action.LONG, D("1"), int(time.time() * 1000))
    await control.claim(sig)
    api.position = PositionSnapshot(
        symbol="BTCUSDT",
        position_mode=api.account.position_mode,
        position_side="BOTH",
        quantity=D("2"),
        entry_price=D("100"),
        mark_price=D("100"),
        update_time_ms=int(time.time() * 1000),
    )
    stop = await api.place_order(
        OrderRequest(
            symbol="BTCUSDT",
            side=Side.SELL,
            order_type="STOP_MARKET",
            kind=OrderKind.ALGO,
            stop_price=D("98"),
            close_position=True,
            position_side="BOTH",
            client_order_id="old-stop",
        )
    )
    tp = await api.place_order(
        OrderRequest(
            symbol="BTCUSDT",
            side=Side.SELL,
            order_type="LIMIT",
            kind=OrderKind.REGULAR,
            quantity=D("3"),
            price=D("103"),
            time_in_force="GTC",
            reduce_only=True,
            position_side="BOTH",
            client_order_id="old-tp",
        )
    )
    await control.reserve_risk(
        "protected", D("4"), max_total_risk=D("100"), external_open_risk=D("0")
    )
    await control.commit_risk("protected", D("4"))
    await control.transition(
        "protected",
        None,
        ExecutionStage.PROTECTED,
        {
            "position_mode": "ONEWAY",
            "executed_qty": "3",
            "stop_price": "98",
            "take_profit_price": "103",
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
            "protection": {
                "symbol": "BTCUSDT",
                "action": "LONG",
                "stop_order_id": stop.order_id,
                "stop_client_order_id": stop.client_order_id,
                "stop_kind": "ALGO",
                "take_profit_order_id": tp.order_id,
                "take_profit_client_order_id": tp.client_order_id,
                "take_profit_kind": "REGULAR",
            },
        },
    )


@pytest.mark.asyncio
async def test_supervisor_resizes_tp_to_actual_position(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    await protected_record(control, api)
    supervisor = ProtectionSupervisor(api, control, ExecutionConfig())
    await supervisor.reconcile_once()
    replacements = [r for r in api.requests if "TPR" in r.client_order_id]
    assert replacements
    assert replacements[-1].quantity == D("2")
    assert "old-tp" in api.cancelled


@pytest.mark.asyncio
async def test_supervisor_closes_record_and_releases_risk_when_flat(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    await protected_record(control, api)
    api.position = PositionSnapshot(
        symbol="BTCUSDT",
        position_mode=api.account.position_mode,
        position_side="BOTH",
        quantity=D("0"),
        entry_price=D("0"),
        mark_price=D("100"),
        update_time_ms=int(time.time() * 1000),
    )
    supervisor = ProtectionSupervisor(api, control, ExecutionConfig())
    await supervisor.reconcile_once()
    record = await control.get_execution("protected")
    assert record is not None and record.stage is ExecutionStage.CLOSED
    totals = await control.risk_totals()
    assert totals["committed"] == D("0")


@pytest.mark.asyncio
async def test_recovery_aborts_pre_submit_execution_and_reopens_admission(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    sig = Signal("early", "BTCUSDT", Action.LONG, D("1"), int(time.time() * 1000))
    await control.claim(sig)
    await control.reserve_risk(
        "early", D("10"), max_total_risk=D("100"), external_open_risk=D("0")
    )
    await control.transition("early", None, ExecutionStage.RISK_RESERVED)
    supervisor = ProtectionSupervisor(api, control, ExecutionConfig())
    recovery = RecoveryManager(api, control, supervisor)
    report = await recovery.recover_startup()
    assert report.unresolved == 0
    record = await control.get_execution("early")
    assert record is not None and record.stage is ExecutionStage.ABORTED
    assert (await control.admission_status())[0] is True
