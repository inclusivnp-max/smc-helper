from __future__ import annotations

import time
from dataclasses import replace
from decimal import Decimal as D

import pytest
import httpx

from smc_execution.config import ExecutionConfig
from smc_execution.control_plane import SQLiteControlPlane
from smc_execution.engine import ExecutionEngine
from smc_execution.governor import PortfolioGovernor, PortfolioGovernorConfig
from smc_execution.models import (
    Action,
    ExecutionRecord,
    ExecutionStage,
    PositionMode,
)
from smc_execution.modes import (
    DEFAULT_MODE_PROFILES,
    ModeSwitchRejected,
    TradingMode,
    TradingModeController,
)
from smc_execution.operator_app import create_operator_app
from smc_execution.operator_service import (
    OperatorCommandError,
    OperatorService,
    OperatorSettings,
)
from smc_execution.operator_store import OperatorStore
from smc_execution.recovery import RecoveryManager
from smc_execution.runtime import TradingRuntime
from smc_execution.supervisor import ProtectionSupervisor

from .fakes import FakeExchange


def build_stack(tmp_path):
    api = FakeExchange()
    control = SQLiteControlPlane(tmp_path / "state.db")
    execution = ExecutionConfig()
    governor_config = PortfolioGovernorConfig(
        max_open_risk_pct=execution.max_portfolio_risk_pct
    )
    engine = ExecutionEngine(api, control, execution)
    supervisor = ProtectionSupervisor(api, control, execution)
    recovery = RecoveryManager(api, control, supervisor, execution)
    governor = PortfolioGovernor(api, control, governor_config)
    runtime = TradingRuntime(api, control, engine, supervisor, recovery, governor)
    modes = TradingModeController(
        control,
        api,
        engine,
        supervisor,
        recovery,
        governor,
        base_execution_config=execution,
        base_governor_config=governor_config,
    )
    service = OperatorService(
        runtime,
        modes,
        OperatorStore(tmp_path / "operator.db"),
        OperatorSettings(
            token="test-operator-token-with-more-than-20-chars",
            symbols=("BTCUSDT",),
            testnet=True,
            telemetry_interval_seconds=60,
        ),
    )
    return api, control, engine, supervisor, recovery, governor, runtime, modes, service


@pytest.mark.asyncio
async def test_mode_switch_applies_profile_and_requires_flat_state(tmp_path):
    api, control, engine, supervisor, _, governor, _, modes, _ = build_stack(tmp_path)
    await modes.initialize()
    assert modes.current.mode is TradingMode.CAPITAL_PROTECTION
    assert engine.config.risk_per_trade_pct == D("0.0015")
    assert supervisor.dynamic_protection.breakeven_activation_r == D("0.80")

    await modes.switch(TradingMode.PROFITABILITY, actor="tester")
    assert engine.config.risk_per_trade_pct == D("0.005")
    assert governor.config.max_daily_loss_pct == D("0.02")
    admission, _ = await control.admission_status()
    assert not admission

    api.position = replace(
        api.position,
        quantity=D("0.01"),
        entry_price=D("1000"),
        mark_price=D("1010"),
    )
    with pytest.raises(ModeSwitchRejected):
        await modes.switch(TradingMode.BALANCED, actor="tester")


@pytest.mark.asyncio
async def test_dynamic_protection_only_tightens_stop(tmp_path):
    _, _, _, supervisor, _, _, _, _, _ = build_stack(tmp_path)
    supervisor.dynamic_protection = DEFAULT_MODE_PROFILES[
        TradingMode.CAPITAL_PROTECTION
    ].dynamic_protection
    record = ExecutionRecord(
        execution_id="signal-1",
        symbol="BTCUSDT",
        action=Action.LONG,
        stage=ExecutionStage.PROTECTED,
        version=3,
        created_at_ms=int(time.time() * 1000),
        updated_at_ms=int(time.time() * 1000),
        payload={
            "initial_stop_price": "990",
            "stop_price": "990",
            "atr": "5",
        },
    )
    rules = FakeExchange().rules
    adjusted, patch = supervisor._dynamic_stop_price(
        record=record,
        action=Action.LONG,
        entry_price=D("1000"),
        mark_price=D("1009"),
        current_stop=D("990"),
        rules=rules,
    )
    assert adjusted >= D("1001.2")
    assert patch["dynamic_stop"]["reason"] == "BREAKEVEN"

    unchanged, patch = supervisor._dynamic_stop_price(
        record=record,
        action=Action.LONG,
        entry_price=D("1000"),
        mark_price=D("1004"),
        current_stop=adjusted,
        rules=rules,
    )
    assert unchanged == adjusted
    assert patch == {}


@pytest.mark.asyncio
async def test_operator_api_auth_idempotency_and_static_ui(tmp_path):
    *_, service = build_stack(tmp_path)
    app = create_operator_app(service, start_runtime=False)
    token = service.settings.token
    headers = {"Authorization": f"Bearer {token}", "X-Operator-Actor": "qa"}

    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            assert (await client.get("/api/v1/status")).status_code == 401
            status = await client.get("/api/v1/status", headers=headers)
            assert status.status_code == 200
            assert status.json()["mode"]["mode"] == "CAPITAL_PROTECTION"

            root = await client.get("/")
            assert root.status_code == 200
            assert "Execution Control" in root.text

            command_headers = {**headers, "Idempotency-Key": "same-command-0001"}
            first = await client.post(
                "/api/v1/commands/halt",
                headers=command_headers,
                json={"reason": "test halt", "confirmation": "HALT"},
            )
            second = await client.post(
                "/api/v1/commands/halt",
                headers=command_headers,
                json={"reason": "different text", "confirmation": "HALT"},
            )
            assert first.status_code == 200
            assert second.status_code == 200
            assert first.json() == second.json()

@pytest.mark.asyncio
async def test_operator_flatten_is_two_phase_and_leaves_admission_closed(tmp_path):
    api, control, *_, service = build_stack(tmp_path)
    api.position = replace(
        api.position,
        position_mode=PositionMode.ONEWAY,
        position_side="BOTH",
        quantity=D("0.01"),
        entry_price=D("1000"),
        mark_price=D("1010"),
    )
    app = create_operator_app(service, start_runtime=False)
    headers = {
        "Authorization": f"Bearer {service.settings.token}",
        "X-Operator-Actor": "qa",
    }
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            plan_response = await client.post(
                "/api/v1/commands/flatten/plan", headers=headers, json={"symbol": None}
            )
            assert plan_response.status_code == 200
            plan = plan_response.json()
            confirm = await client.post(
                "/api/v1/commands/flatten/confirm",
                headers={**headers, "Idempotency-Key": "flatten-command-0001"},
                json={
                    "plan_id": plan["plan_id"],
                    "confirmation_phrase": plan["confirmation_phrase"],
                },
            )
            assert confirm.status_code == 200
            assert confirm.json()["status"] == "FLATTENED"
            assert api.position.is_flat

    admission_open, _ = await control.admission_status()
    assert not admission_open


@pytest.mark.asyncio
async def test_stop_replacement_never_cancels_only_stop_before_new_is_active(tmp_path):
    from smc_execution.errors import CriticalExecutionError
    from smc_execution.models import OrderKind, OrderRequest, Side

    api, _, _, supervisor, _, _, _, _, _ = build_stack(tmp_path)
    existing = await api.place_order(
        OrderRequest(
            symbol="BTCUSDT",
            side=Side.SELL,
            order_type="STOP_MARKET",
            kind=OrderKind.ALGO,
            client_order_id="existing-SL",
            stop_price=D("990"),
            close_position=True,
            position_side="BOTH",
        )
    )
    api.reject_stop = True
    record = ExecutionRecord(
        execution_id="signal-stop-replace",
        symbol="BTCUSDT",
        action=Action.LONG,
        stage=ExecutionStage.PROTECTED,
        version=4,
        created_at_ms=int(time.time() * 1000),
        updated_at_ms=int(time.time() * 1000),
        payload={},
    )
    with pytest.raises(CriticalExecutionError):
        await supervisor._ensure_stop(
            record=record,
            action=Action.LONG,
            mode=PositionMode.ONEWAY,
            stop_price=D("995"),
            target_qty=D("0.01"),
            rules=api.rules,
            protection={
                "stop_order_id": existing.order_id,
                "stop_client_order_id": existing.client_order_id,
            },
        )
    assert existing.client_order_id not in api.cancelled
    assert api.orders[(OrderKind.ALGO, existing.client_order_id)].status.active

@pytest.mark.asyncio
async def test_status_snapshot_cache_prevents_per_client_exchange_fanout(tmp_path):
    api, *_, service = build_stack(tmp_path)
    calls = 0
    original = api.get_account_snapshot

    async def counted():
        nonlocal calls
        calls += 1
        return await original()

    api.get_account_snapshot = counted  # type: ignore[method-assign]
    await service.initialize()
    await service.status_snapshot()
    await service.status_snapshot()
    assert calls == 1
    service.invalidate_snapshot()
    await service.status_snapshot()
    assert calls == 2


@pytest.mark.asyncio
async def test_operator_order_reads_are_symbol_scoped_and_cached(tmp_path):
    api, *_, service = build_stack(tmp_path)
    calls: list[tuple[str | None, object]] = []
    original = api.list_open_orders

    async def counted(symbol, kind):
        calls.append((symbol, kind))
        return await original(symbol, kind)

    api.list_open_orders = counted  # type: ignore[method-assign]
    await service.initialize()
    await service.status_snapshot()
    await service.status_snapshot(force=True)

    assert calls
    assert all(symbol == "BTCUSDT" for symbol, _ in calls)
    # One request per order kind; forced status refresh reuses the slower order cache.
    assert len(calls) == 2


@pytest.mark.asyncio
async def test_idempotency_key_cannot_cross_operator_actions(tmp_path):
    *_, service = build_stack(tmp_path)
    await service.initialize()
    key = "cross-action-key-0001"
    await service.halt(actor="qa", reason="first", idempotency_key=key)
    with pytest.raises(OperatorCommandError, match="different operator action"):
        await service.reconcile(
            actor="qa", resume_on_success=False, idempotency_key=key
        )


@pytest.mark.asyncio
async def test_paused_mode_preserves_effective_runtime_policy(tmp_path):
    _, control, engine, supervisor, _, _, _, modes, _ = build_stack(tmp_path)
    await modes.initialize()
    await modes.switch(TradingMode.BALANCED, actor="qa")
    balanced_config = engine.config
    balanced_dynamic = supervisor.dynamic_protection

    await modes.switch(TradingMode.PAUSED, actor="qa")

    assert modes.current.mode is TradingMode.PAUSED
    assert modes.active_profile.mode is TradingMode.BALANCED
    assert engine.config is balanced_config
    assert supervisor.dynamic_protection is balanced_dynamic
    admission, _ = await control.admission_status()
    assert not admission

@pytest.mark.asyncio
async def test_paused_mode_restores_last_active_policy_after_restart(tmp_path):
    *_, modes, _ = build_stack(tmp_path)
    await modes.initialize()
    await modes.switch(TradingMode.PROFITABILITY, actor="qa")
    await modes.switch(TradingMode.PAUSED, actor="qa")

    _, _, engine2, supervisor2, _, _, _, modes2, _ = build_stack(tmp_path)
    await modes2.initialize()

    assert modes2.current.mode is TradingMode.PAUSED
    assert modes2.active_profile.mode is TradingMode.PROFITABILITY
    assert engine2.config.risk_per_trade_pct == D("0.005")
    assert supervisor2.dynamic_protection.breakeven_activation_r == D("1.50")


@pytest.mark.asyncio
async def test_operator_research_api_is_authenticated_and_governed(tmp_path):
    from smc_execution.research_data import ResearchDataset, validate_bars
    from smc_execution.research_models import ApprovalStage
    from smc_execution.research_service import ResearchService, ResearchServiceSettings
    from smc_execution.research_store import ResearchStore
    from tests.test_research import make_bars, passed_report

    *_, service = build_stack(tmp_path)
    research_store = ResearchStore(tmp_path / "research.db")
    await research_store.initialize()
    bars = make_bars(30)
    dataset = ResearchDataset(
        "ui-dataset",
        "BTCUSDT",
        "5m",
        bars,
        "ui-dataset-fingerprint",
        validate_bars(bars),
        "unit-test",
    )
    await research_store.save_dataset(dataset)
    report = passed_report(dataset.dataset_id, dataset.fingerprint)
    await research_store.save_report(report)
    service.research = ResearchService(
        research_store,
        service.store,
        ResearchServiceSettings(minimum_dataset_bars=10),
    )
    app = create_operator_app(service, start_runtime=False)
    headers = {
        "Authorization": f"Bearer {service.settings.token}",
        "X-Operator-Actor": "research-qa",
    }
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            assert (await client.get("/api/v1/research/datasets")).status_code == 401
            datasets = await client.get("/api/v1/research/datasets", headers=headers)
            assert datasets.status_code == 200
            assert datasets.json()["items"][0]["dataset_id"] == dataset.dataset_id

            detail = await client.get(
                f"/api/v1/research/runs/{report.run_id}", headers=headers
            )
            assert detail.status_code == 200
            assert detail.json()["gate"]["passed"] is True

            bad = await client.post(
                f"/api/v1/research/runs/{report.run_id}/promote",
                headers={**headers, "Idempotency-Key": "research-promote-bad-01"},
                json={
                    "stage": ApprovalStage.RESEARCH_PASSED.value,
                    "reason": "validated unit test",
                    "confirmation": "wrong",
                },
            )
            assert bad.status_code == 400

            promoted = await client.post(
                f"/api/v1/research/runs/{report.run_id}/promote",
                headers={**headers, "Idempotency-Key": "research-promote-good-01"},
                json={
                    "stage": ApprovalStage.RESEARCH_PASSED.value,
                    "reason": "validated unit test",
                    "confirmation": "PROMOTE RESEARCH_PASSED",
                },
            )
            assert promoted.status_code == 200
            assert promoted.json()["stage"] == "RESEARCH_PASSED"


@pytest.mark.asyncio
async def test_operator_proxy_mode_rejects_non_https_requests(tmp_path):
    *_, service = build_stack(tmp_path)
    app = create_operator_app(
        service,
        start_runtime=False,
        require_https_proxy=True,
    )
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            assert (await client.get("/healthz")).status_code == 200
            refused = await client.get("/api/v1/status")
            assert refused.status_code == 426
            proxied = await client.get(
                "/api/v1/status", headers={"X-Forwarded-Proto": "https"}
            )
            assert proxied.status_code == 401
