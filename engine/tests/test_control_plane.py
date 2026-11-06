import asyncio
from decimal import Decimal as D

import pytest

from smc_execution.control_plane import SQLiteControlPlane
from smc_execution.models import Action, ExecutionStage, Signal


@pytest.mark.asyncio
async def test_atomic_risk_reservation_under_concurrency(tmp_path):
    control = SQLiteControlPlane(tmp_path / "state.db")

    async def reserve(i: int):
        return await control.reserve_risk(
            f"r{i}", D("20"), max_total_risk=D("100"), external_open_risk=D("0")
        )

    results = await asyncio.gather(*(reserve(i) for i in range(20)))
    accepted = [item for item in results if item is not None]
    assert len(accepted) == 5
    totals = await control.risk_totals()
    assert totals["reserved"] == D("100")


@pytest.mark.asyncio
async def test_execution_transition_is_durable(tmp_path):
    control = SQLiteControlPlane(tmp_path / "state.db")
    signal = Signal("s1", "BTCUSDT", Action.LONG, D("1"), 1)
    record, created = await control.claim(signal)
    assert created and record.stage is ExecutionStage.CLAIMED
    await control.transition(
        "s1", {ExecutionStage.CLAIMED}, ExecutionStage.PRECHECKED, {"x": "1"}
    )
    reopened = SQLiteControlPlane(tmp_path / "state.db")
    record2 = await reopened.get_execution("s1")
    assert record2 is not None
    assert record2.stage is ExecutionStage.PRECHECKED
    assert record2.payload["x"] == "1"
