from __future__ import annotations

from pathlib import Path

import pytest

from smc_execution.models import Action, D
from smc_execution.operator_store import OperatorStore
from smc_execution.research_data import ResearchDataset, validate_bars
from smc_execution.research_models import ApprovalStage, SignalDecision
from smc_execution.research_service import (
    ResearchCommandError,
    ResearchService,
    ResearchServiceSettings,
)
from smc_execution.research_store import ResearchStore
from smc_execution.shadow import ShadowBook, ShadowConfig
from smc_execution.strategy import SMCCompositeStrategy

from .test_research import make_bars, passed_report


@pytest.mark.asyncio
async def test_shadow_book_opens_on_next_bar_and_closes(tmp_path: Path) -> None:
    store = ResearchStore(tmp_path / "research.db")
    await store.initialize()
    bars = list(make_bars(25))
    strategy = SMCCompositeStrategy()
    decision = SignalDecision(
        action=Action.LONG,
        confidence=D("0.9"),
        reason="test",
        feature_time_ms=bars[10].close_time_ms,
        reference_price=bars[10].close,
        features={},
    )
    book = ShadowBook(
        store,
        ShadowConfig(
            stop_atr_multiple=D("0.5"),
            take_profit_atr_multiple=D("0.5"),
        ),
    )
    await book.register(
        run_id="run-shadow",
        manifest=strategy.manifest,
        symbol="BTCUSDT",
        decision=decision,
    )
    await book.process_closed_bar(bars[11], bars[:12])
    active = await store.list_active_shadow("BTCUSDT")
    if active:
        item = active[0]
        entry = D(str(item["entry_price"]))
        # Force the next bar through the take level.
        original = bars[12]
        from smc_execution.research_models import ResearchBar

        bars[12] = ResearchBar(
            **{
                **original.__dict__,
                "high": entry + D("10"),
                "low": min(original.low, entry - D("0.01")),
            }
        )
        await book.process_closed_bar(bars[12], bars[:13])
    summary = await store.shadow_summary("run-shadow")
    assert summary["signals"] == 1
    assert summary["closed"] == 1


@pytest.mark.asyncio
async def test_research_service_requires_shadow_evidence_before_testnet(tmp_path: Path) -> None:
    research_store = ResearchStore(tmp_path / "research.db")
    audit_store = OperatorStore(tmp_path / "operator.db")
    await research_store.initialize()
    await audit_store.initialize()
    bars = make_bars(30)
    dataset = ResearchDataset(
        "stored",
        "BTCUSDT",
        "5m",
        bars,
        "stored-fingerprint",
        validate_bars(bars),
        "unit-test",
    )
    await research_store.save_dataset(dataset)
    report = passed_report(dataset.dataset_id, dataset.fingerprint)
    await research_store.save_report(report)
    service = ResearchService(
        research_store,
        audit_store,
        ResearchServiceSettings(
            minimum_dataset_bars=10,
            minimum_shadow_closed_trades=1,
        ),
    )
    await service.initialize()
    await service.promote(
        run_id=report.run_id,
        stage=ApprovalStage.RESEARCH_PASSED,
        actor="tester",
        reason="research passed",
        idempotency_key="research-pass-key",
    )
    await service.promote(
        run_id=report.run_id,
        stage=ApprovalStage.SHADOW,
        actor="tester",
        reason="start shadow",
        idempotency_key="shadow-pass-key",
    )
    with pytest.raises(ResearchCommandError, match="Shadow closed trades"):
        await service.promote(
            run_id=report.run_id,
            stage=ApprovalStage.TESTNET,
            actor="tester",
            reason="too early",
            idempotency_key="testnet-too-early",
        )


class _StaticProvider:
    def __init__(self, bars):
        self.bars = tuple(bars)
        self.calls = 0

    async def get_recent_bars(self, symbol, interval, *, limit):
        self.calls += 1
        return self.bars[-limit:]


class _AlwaysLongStrategy:
    def __init__(self):
        from smc_execution.research_models import StrategyManifest

        self._manifest = StrategyManifest.create("daemon_test", "1.0", {})

    @property
    def manifest(self):
        return self._manifest

    @property
    def warmup_bars(self):
        return 20

    def with_parameters(self, parameters):
        return self

    def decide(self, history):
        from smc_execution.research_models import SignalDecision

        bar = history[-1]
        return SignalDecision(
            action=Action.LONG,
            confidence=D("0.90"),
            reason="unit-test",
            feature_time_ms=bar.close_time_ms,
            reference_price=bar.close,
            features={"closed_bar": True},
        )


@pytest.mark.asyncio
async def test_signal_daemon_deduplicates_same_closed_bar(tmp_path: Path) -> None:
    from smc_execution.signal_generator import SMCSignalDaemon, SignalDaemonSettings

    store = ResearchStore(tmp_path / "research.db")
    await store.initialize()
    bars = make_bars(120)
    provider = _StaticProvider(bars)
    daemon = SMCSignalDaemon(
        provider=provider,
        strategy=_AlwaysLongStrategy(),
        run_id="daemon-run",
        settings=SignalDaemonSettings(
            symbols=("BTCUSDT",),
            history_bars=100,
            poll_seconds=1,
            environment="shadow",
        ),
        shadow_book=ShadowBook(store),
    )
    first = await daemon.poll_once()
    second = await daemon.poll_once()
    assert [event["type"] for event in first] == ["shadow_signal"]
    assert second == []
    records = await store.list_shadow("daemon-run")
    assert len(records) == 1


@pytest.mark.asyncio
async def test_research_data_download_job_is_durable(monkeypatch, tmp_path: Path) -> None:
    from smc_execution import research_service as module

    research_store = ResearchStore(tmp_path / "download-research.db")
    audit_store = OperatorStore(tmp_path / "download-operator.db")
    await research_store.initialize()
    await audit_store.initialize()
    dataset = ResearchDataset(
        "downloaded",
        "BTCUSDT",
        "5m",
        make_bars(30),
        "downloaded-fingerprint",
        validate_bars(make_bars(30)),
        "fake-binance",
    )

    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        async def download_dataset(self, **kwargs):
            assert kwargs["symbol"] == "BTCUSDT"
            return dataset

    monkeypatch.setattr(module, "BinanceResearchDataClient", FakeClient)
    service = ResearchService(
        research_store,
        audit_store,
        ResearchServiceSettings(
            minimum_dataset_bars=10,
            minimum_dataset_span_days=0,
        ),
    )
    await service.initialize()
    job = await service.start_dataset_download(
        symbol="BTCUSDT",
        interval="5m",
        start_time_ms=1,
        end_time_ms=2,
        include_mark_price=True,
        include_funding=True,
        include_open_interest=True,
        dataset_id=None,
        actor="tester",
        idempotency_key="download-job-key-0001",
    )
    for _ in range(50):
        jobs = await service.list_data_jobs()
        current = next(item for item in jobs if item["job_id"] == job["job_id"])
        if current["status"] in {"COMPLETED", "FAILED"}:
            break
        await __import__("asyncio").sleep(0.01)
    assert current["status"] == "COMPLETED"
    assert (await research_store.get_dataset("downloaded")) is not None
    await service.shutdown()

    restarted = ResearchService(research_store, audit_store)
    await restarted.initialize()
    durable = await restarted.list_data_jobs()
    restored = next(item for item in durable if item["job_id"] == job["job_id"])
    assert restored["status"] == "COMPLETED"
    assert restored["fingerprint"] == dataset.fingerprint


@pytest.mark.asyncio
async def test_shadow_state_is_durable_and_time_exit_matches_policy(tmp_path: Path) -> None:
    from smc_execution.research_models import SignalDecision

    store = ResearchStore(tmp_path / "shadow-state.db")
    await store.initialize()
    bars = list(make_bars(25))
    strategy = SMCCompositeStrategy()
    decision = SignalDecision(
        action=Action.LONG,
        confidence=D("0.9"),
        reason="time-exit-test",
        feature_time_ms=bars[10].close_time_ms,
        reference_price=bars[10].close,
        features={},
    )
    book = ShadowBook(
        store,
        ShadowConfig(
            stop_atr_multiple=D("10"),
            take_profit_atr_multiple=D("10"),
            max_holding_bars=2,
            breakeven_activation_r=None,
            trailing_activation_r=None,
        ),
    )
    await book.register(
        run_id="durable-shadow",
        manifest=strategy.manifest,
        symbol="BTCUSDT",
        decision=decision,
    )
    await book.process_closed_bar(bars[11], bars[:12])
    open_items = await store.list_active_shadow("BTCUSDT")
    assert len(open_items) == 1
    import json

    state = json.loads(open_items[0]["state_json"])
    assert state["holding_bars"] == 1
    # Reprocessing the same bar is idempotent and must not age the trade twice.
    await book.process_closed_bar(bars[11], bars[:12])
    state_again = json.loads((await store.list_active_shadow("BTCUSDT"))[0]["state_json"])
    assert state_again["holding_bars"] == 1

    await book.process_closed_bar(bars[12], bars[:13])
    records = await store.list_shadow("durable-shadow")
    assert records[0]["status"] == "CLOSED"
    assert records[0]["exit_reason"] == "TIME_EXIT"


@pytest.mark.asyncio
async def test_shadow_gap_stop_is_conservative_and_durable(tmp_path: Path) -> None:
    from smc_execution.research_models import ResearchBar, SignalDecision

    store = ResearchStore(tmp_path / "shadow-gap.db")
    await store.initialize()
    bars = list(make_bars(25))
    strategy = SMCCompositeStrategy()
    decision = SignalDecision(
        action=Action.LONG,
        confidence=D("0.9"),
        reason="gap-test",
        feature_time_ms=bars[10].close_time_ms,
        reference_price=bars[10].close,
        features={},
    )
    book = ShadowBook(
        store,
        ShadowConfig(
            stop_atr_multiple=D("0.5"),
            take_profit_atr_multiple=D("10"),
            stop_slippage_bps=D("10"),
            breakeven_activation_r=None,
            trailing_activation_r=None,
        ),
    )
    await book.register(
        run_id="shadow-gap",
        manifest=strategy.manifest,
        symbol="BTCUSDT",
        decision=decision,
    )
    await book.process_closed_bar(bars[11], bars[:12])
    item = (await store.list_active_shadow("BTCUSDT"))[0]
    stop = D(str(item["stop_price"]))
    gap_open = stop - D("2")
    original = bars[12]
    bars[12] = ResearchBar(
        **{
            **original.__dict__,
            "open": gap_open,
            "high": max(gap_open, stop - D("0.1")),
            "low": gap_open - D("1"),
            "close": gap_open - D("0.5"),
        }
    )
    await book.process_closed_bar(bars[12], bars[:13])
    closed = (await store.list_shadow("shadow-gap"))[0]
    assert closed["exit_reason"] == "STOP_GAP"
    assert D(str(closed["exit_price"])) < gap_open
    assert D(str(closed["r_multiple"])) < D("-1")
