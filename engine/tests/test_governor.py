from decimal import Decimal as D
import datetime as dt

import pytest

from smc_execution.control_plane import SQLiteControlPlane
from smc_execution.governor import PortfolioGovernor, PortfolioGovernorConfig
from smc_execution.models import AccountSnapshot

from .fakes import FakeExchange


@pytest.mark.asyncio
async def test_daily_loss_kill_switch_is_durable(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    governor = PortfolioGovernor(
        api,
        control,
        PortfolioGovernorConfig(max_daily_loss_pct=D("0.02")),
    )
    first = await governor.evaluate_once()
    assert not first.halt
    api.account = AccountSnapshot(
        equity=D("9700"),
        available_balance=D("9700"),
        max_new_position_notional=D("10000"),
        position_mode=api.account.position_mode,
    )
    second = await governor.evaluate_once()
    assert second.halt
    open_, reason = await control.admission_status()
    assert not open_
    assert "daily loss" in (reason or "")


@pytest.mark.asyncio
async def test_api_error_streak_halts_admission(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    governor = PortfolioGovernor(
        api,
        control,
        PortfolioGovernorConfig(max_api_error_streak=2),
    )
    await governor.record_api_failure("x")
    assert (await control.admission_status())[0]
    await governor.record_api_failure("y")
    assert not (await control.admission_status())[0]


@pytest.mark.asyncio
async def test_invalid_daily_baseline_is_reanchored(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    today = dt.datetime.now(dt.timezone.utc).date().isoformat()
    await control.set_system_value(
        "portfolio_governor",
        {
            "utc_day": today,
            "day_start_equity": "0E-8",
            "high_water_equity": "0E-8",
        },
    )
    governor = PortfolioGovernor(api, control)

    decision = await governor.evaluate_once()

    assert decision.halt is False
    assert decision.daily_loss_pct == D("0")
    state = await control.get_system_value("portfolio_governor")
    assert D(state["day_start_equity"]) == D("10000")
