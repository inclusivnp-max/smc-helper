import asyncio
import time
from decimal import Decimal as D

import pytest

from smc_execution.config import ExecutionConfig
from smc_execution.control_plane import SQLiteControlPlane
from smc_execution.engine import ExecutionEngine
from smc_execution.models import (
    Action,
    ExecutionStage,
    OrderKind,
    PartialFillPolicy,
    Quote,
    Signal,
)

from .fakes import FakeExchange


def signal(signal_id="sig-1"):
    return Signal(
        signal_id=signal_id,
        symbol="BTCUSDT",
        action=Action.LONG,
        confidence=D("1"),
        created_at_ms=int(time.time() * 1000),
    )


@pytest.mark.asyncio
async def test_full_entry_is_atomically_risk_reserved_and_protected(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    config = ExecutionConfig(
        estimated_round_trip_fees_bps=D("0"),
        execution_risk_reserve_bps=D("0"),
        maker_timeout_ms=100,
        total_entry_timeout_ms=1000,
        protection_timeout_ms=1000,
    )
    engine = ExecutionEngine(api, control, config)
    result = await engine.execute_signal(signal())

    assert result.status == "FILLED_PROTECTED"
    assert result.executed_qty == result.requested_qty
    assert result.stop_order_id is not None
    record = await control.get_execution("sig-1")
    assert record is not None and record.stage is ExecutionStage.PROTECTED
    totals = await control.risk_totals()
    assert totals["committed"] > D("0")

    stop_request = next(r for r in api.requests if r.kind is OrderKind.ALGO)
    assert stop_request.close_position is True
    assert stop_request.quantity is None
    assert stop_request.reduce_only is False


@pytest.mark.asyncio
async def test_partial_fill_replacement_uses_only_remaining_quantity(tmp_path):
    api = FakeExchange()
    api.maker_fill_qty = D("1")
    control = SQLiteControlPlane(tmp_path / "state.db")
    config = ExecutionConfig(
        estimated_round_trip_fees_bps=D("0"),
        execution_risk_reserve_bps=D("0"),
        maker_timeout_ms=100,
        total_entry_timeout_ms=1000,
        partial_fill_policy=PartialFillPolicy.ACCEPT_AND_PROTECT,
    )
    engine = ExecutionEngine(api, control, config)
    result = await engine.execute_signal(signal("partial"))
    maker = next(r for r in api.requests if r.client_order_id.endswith("MKR"))
    ioc = next(r for r in api.requests if r.client_order_id.endswith("IOC"))
    assert ioc.quantity == maker.quantity - D("1")
    assert result.executed_qty == maker.quantity


@pytest.mark.asyncio
async def test_stop_rejection_forces_flatten_and_releases_risk(tmp_path):
    api = FakeExchange()
    api.reject_stop = True
    control = SQLiteControlPlane(tmp_path / "state.db")
    engine = ExecutionEngine(
        api,
        control,
        ExecutionConfig(
            estimated_round_trip_fees_bps=D("0"),
            execution_risk_reserve_bps=D("0"),
            maker_timeout_ms=100,
            total_entry_timeout_ms=1000,
        ),
    )
    result = await engine.execute_signal(signal("bad-stop"))
    assert result.status == "PROTECTION_FAILED_POSITION_CLOSED"
    assert api.position.is_flat
    totals = await control.risk_totals()
    assert totals["reserved"] == D("0")
    assert totals["committed"] == D("0")
    assert (await control.admission_status())[0] is False


@pytest.mark.asyncio
async def test_unconfirmed_stop_ack_forces_emergency_flatten(tmp_path):
    from smc_execution.errors import OrderNotFound

    class MissingStopExchange(FakeExchange):
        async def get_order(self, symbol, order_id, client_order_id, kind):
            if client_order_id.endswith("SL"):
                raise OrderNotFound(client_order_id)
            return await super().get_order(symbol, order_id, client_order_id, kind)

    api = MissingStopExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    engine = ExecutionEngine(
        api,
        control,
        ExecutionConfig(
            estimated_round_trip_fees_bps=D("0"),
            execution_risk_reserve_bps=D("0"),
            maker_timeout_ms=100,
            total_entry_timeout_ms=1000,
            protection_timeout_ms=80,
            default_io_timeout_ms=40,
        ),
    )
    result = await engine.execute_signal(signal("unconfirmed-stop"))
    assert result.status == "PROTECTION_FAILED_POSITION_CLOSED"
    assert api.position.is_flat
    assert (await control.admission_status())[0] is False


@pytest.mark.asyncio
async def test_exchange_timestamp_staleness_rejects_quote(tmp_path):
    api = FakeExchange()
    api.quote = Quote(
        symbol="BTCUSDT",
        bid=D("100"),
        ask=D("100.2"),
        bid_qty=D("1"),
        ask_qty=D("1"),
        exchange_time_ms=int(time.time() * 1000) - 10_000,
    )
    control = SQLiteControlPlane(tmp_path / "state.db")
    engine = ExecutionEngine(api, control, ExecutionConfig())
    result = await engine.execute_signal(signal("stale-quote"))
    assert result.status == "FAILED_SAFE"

@pytest.mark.asyncio
async def test_ambiguous_submit_halts_admission_and_keeps_risk_reserved(tmp_path):
    from smc_execution.errors import OrderNotFound

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
    engine = ExecutionEngine(
        api,
        control,
        ExecutionConfig(
            estimated_round_trip_fees_bps=D("0"),
            execution_risk_reserve_bps=D("0"),
            default_io_timeout_ms=10,
            cancel_reconcile_timeout_ms=40,
            maker_timeout_ms=100,
            total_entry_timeout_ms=500,
        ),
    )
    result = await engine.execute_signal(signal("ambiguous"))
    assert result.status == "FAILED_UNCERTAIN"
    assert (await control.admission_status())[0] is False
    totals = await control.risk_totals()
    assert totals["reserved"] > D("0")

@pytest.mark.asyncio
async def test_expected_maker_wait_timeout_proceeds_to_cancel_not_uncertain(tmp_path):
    class SlowUpdateExchange(FakeExchange):
        async def wait_order_update(
            self, symbol, order_id, client_order_id, kind, timeout_ms
        ):
            if client_order_id.endswith("MKR"):
                await asyncio.sleep(5)
            return await super().wait_order_update(
                symbol, order_id, client_order_id, kind, timeout_ms
            )

    api = SlowUpdateExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    engine = ExecutionEngine(
        api,
        control,
        ExecutionConfig(
            estimated_round_trip_fees_bps=D("0"),
            execution_risk_reserve_bps=D("0"),
            maker_timeout_ms=20,
            total_entry_timeout_ms=1000,
            default_io_timeout_ms=200,
        ),
    )
    outcome = await engine.execute_signal(signal("slow-maker-event"))
    assert outcome.status == "FILLED_PROTECTED"
    assert any(x.endswith("MKR") for x in api.cancelled)


def test_quote_age_uses_server_adjusted_age_when_available(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    engine = ExecutionEngine(api, control, ExecutionConfig())
    quote = Quote(
        symbol="BTCUSDT",
        bid=D("1000"),
        ask=D("1000.1"),
        bid_qty=D("1"),
        ask_qty=D("1"),
        exchange_time_ms=1,  # Deliberately incompatible with local wall clock.
        exchange_age_at_receive_ms=10,
    )
    engine._validate_rules_and_quote("BTCUSDT", api.rules, quote)
