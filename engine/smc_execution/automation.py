from __future__ import annotations

import asyncio
import time
from collections import deque
from dataclasses import dataclass
from typing import Any

from .alpha_gate import AlphaGateSettings, GovernedSignalRouter
from .binance_research import BinanceResearchDataClient
from .models import D
from .research_models import ApprovalStage
from .research_store import ResearchStore
from .runtime import TradingRuntime
from .shadow import ShadowBook, ShadowConfig
from .signal_generator import (
    SMCSignalDaemon,
    SignalDaemonSettings,
    load_approved_smc_strategy,
)


@dataclass(frozen=True)
class StrategyAutomationSettings:
    run_id: str
    symbols: tuple[str, ...]
    interval: str = "5m"
    environment: str = "shadow"
    poll_seconds: float = 5.0
    history_bars: int = 500
    max_consecutive_errors: int = 3


class StrategyAutomation:
    """Lifecycle owner for approved closed-bar signal generation."""

    def __init__(
        self,
        runtime: TradingRuntime,
        research_store: ResearchStore,
        settings: StrategyAutomationSettings,
    ) -> None:
        self.runtime = runtime
        self.store = research_store
        self.settings = settings
        self._provider: BinanceResearchDataClient | None = None
        self._daemon: SMCSignalDaemon | None = None
        self._task: asyncio.Task[Any] | None = None
        self._events: deque[dict[str, Any]] = deque(maxlen=200)
        self._consecutive_errors = 0
        self._started_at_ms: int | None = None

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    async def start(self) -> None:
        if self.running:
            return
        strategy = await load_approved_smc_strategy(self.store, self.settings.run_id)
        required = {
            "shadow": ApprovalStage.SHADOW,
            "testnet": ApprovalStage.TESTNET,
            "live": ApprovalStage.LIVE,
        }[self.settings.environment]
        approval = await self.store.latest_approval(
            strategy.manifest.strategy_id,
            strategy.manifest.version,
            self.settings.environment,
        )
        if approval is None or not approval.active or approval.run_id != self.settings.run_id:
            raise RuntimeError(
                f"No active {self.settings.environment} approval for run {self.settings.run_id}"
            )
        stage_rank = {
            ApprovalStage.DRAFT: 0,
            ApprovalStage.RESEARCH_PASSED: 1,
            ApprovalStage.SHADOW: 2,
            ApprovalStage.TESTNET: 3,
            ApprovalStage.LIVE: 4,
            ApprovalStage.REVOKED: -1,
        }
        if stage_rank[approval.stage] < stage_rank[required]:
            raise RuntimeError(
                f"Approval {approval.stage.value} is below required {required.value}"
            )
        report = await self.store.get_report(self.settings.run_id)
        if report is None:
            raise RuntimeError("Approved research report is missing")
        dataset = await self.store.get_dataset(str(report.get("dataset_id") or ""))
        if dataset is None:
            raise RuntimeError("Approved research dataset is missing")
        configured_symbols = tuple(dict.fromkeys(s.upper() for s in self.settings.symbols))
        if configured_symbols != (dataset.symbol,):
            raise RuntimeError(
                "An approval is bound to exactly one researched symbol; expected "
                f"{dataset.symbol}, got {configured_symbols}"
            )
        if self.settings.interval != dataset.interval:
            raise RuntimeError(
                f"Approval interval is {dataset.interval}, got {self.settings.interval}"
            )

        self._provider = BinanceResearchDataClient()
        await self._provider.__aenter__()
        router = None
        shadow_book = None
        if self.settings.environment == "shadow":
            simulation = report.get("simulation") or {}
            shadow_book = ShadowBook(
                self.store,
                ShadowConfig(
                    stop_atr_multiple=D(str(simulation.get("stop_atr_multiple", "1.5"))),
                    take_profit_atr_multiple=D(
                        str(simulation.get("take_profit_atr_multiple", "2.25"))
                    ),
                    entry_slippage_bps=D(
                        str(simulation.get("aggressive_fallback_bps", "5"))
                    ),
                    stop_slippage_bps=D(
                        str(simulation.get("stop_slippage_bps", "5"))
                    ),
                    round_trip_fee_bps=(
                        D(str(simulation.get("maker_fee_bps", "2")))
                        + D(str(simulation.get("taker_fee_bps", "5")))
                    ),
                    conservative_same_bar_exit=bool(
                        simulation.get("conservative_same_bar_exit", True)
                    ),
                    breakeven_activation_r=(
                        None
                        if simulation.get("breakeven_activation_r") is None
                        else D(str(simulation.get("breakeven_activation_r")))
                    ),
                    breakeven_lock_bps=D(
                        str(simulation.get("breakeven_lock_bps", "10"))
                    ),
                    trailing_activation_r=(
                        None
                        if simulation.get("trailing_activation_r") is None
                        else D(str(simulation.get("trailing_activation_r")))
                    ),
                    trailing_atr_multiple=(
                        None
                        if simulation.get("trailing_atr_multiple") is None
                        else D(str(simulation.get("trailing_atr_multiple")))
                    ),
                    max_holding_bars=int(simulation.get("max_holding_bars", 96)),
                ),
            )
        else:
            router = GovernedSignalRouter(
                self.runtime,
                self.store,
                AlphaGateSettings(environment=self.settings.environment),
            )
        self._daemon = SMCSignalDaemon(
            provider=self._provider,
            strategy=strategy,
            run_id=self.settings.run_id,
            settings=SignalDaemonSettings(
                symbols=self.settings.symbols,
                interval=self.settings.interval,
                history_bars=self.settings.history_bars,
                poll_seconds=self.settings.poll_seconds,
                environment=self.settings.environment,
            ),
            router=router,
            shadow_book=shadow_book,
            on_event=self._on_event,
        )
        self._started_at_ms = int(time.time() * 1000)
        self._task = asyncio.create_task(
            self._daemon.run(), name="approved-strategy-automation"
        )

    async def stop(self) -> None:
        if self._daemon is not None:
            self._daemon.stop()
        if self._task is not None:
            try:
                await asyncio.wait_for(self._task, timeout=10)
            except TimeoutError:
                self._task.cancel()
                await asyncio.gather(self._task, return_exceptions=True)
        self._task = None
        self._daemon = None
        if self._provider is not None:
            await self._provider.__aexit__(None, None, None)
        self._provider = None

    async def _on_event(self, event: dict[str, Any]) -> None:
        event = {**event, "observed_at_ms": int(time.time() * 1000)}
        self._events.appendleft(event)
        if event.get("type") == "generator_error":
            self._consecutive_errors += 1
            if (
                self.settings.environment in {"testnet", "live"}
                and self._consecutive_errors >= self.settings.max_consecutive_errors
            ):
                await self.runtime.control.halt_admission(
                    "approved strategy data source repeatedly failed"
                )
        else:
            self._consecutive_errors = 0

    def status(self) -> dict[str, Any]:
        task_error = None
        if self._task is not None and self._task.done() and not self._task.cancelled():
            try:
                task_error = repr(self._task.exception())
            except Exception as exc:  # pragma: no cover
                task_error = repr(exc)
        return {
            "enabled": True,
            "running": self.running,
            "run_id": self.settings.run_id,
            "environment": self.settings.environment,
            "symbols": self.settings.symbols,
            "interval": self.settings.interval,
            "started_at_ms": self._started_at_ms,
            "consecutive_errors": self._consecutive_errors,
            "task_error": task_error,
            "recent_events": list(self._events)[:20],
        }


@dataclass(frozen=True)
class ApprovedPortfolioAutomationSettings:
    run_ids: tuple[str, ...]
    environment: str = "shadow"
    poll_seconds: float = 5.0
    history_bars: int = 500
    max_consecutive_errors: int = 3

    def __post_init__(self) -> None:
        if not self.run_ids:
            raise ValueError("At least one approved research run is required")
        if len(set(self.run_ids)) != len(self.run_ids):
            raise ValueError("Approved run IDs must be unique")
        if self.environment not in {"shadow", "testnet", "live"}:
            raise ValueError("environment must be shadow, testnet or live")


class ApprovedPortfolioAutomation:
    """Runs one immutable approved strategy instance per researched market.

    A single-symbol research approval can never leak into another symbol. A
    portfolio is therefore composed explicitly from independently approved runs.
    """

    def __init__(
        self,
        runtime: TradingRuntime,
        research_store: ResearchStore,
        settings: ApprovedPortfolioAutomationSettings,
    ) -> None:
        self.runtime = runtime
        self.store = research_store
        self.settings = settings
        self._children: list[StrategyAutomation] = []
        self._start_error: str | None = None

    @property
    def running(self) -> bool:
        return bool(self._children) and all(child.running for child in self._children)

    async def start(self) -> None:
        if self.running:
            return
        await self.stop()
        started: list[StrategyAutomation] = []
        try:
            seen_markets: set[tuple[str, str]] = set()
            for run_id in self.settings.run_ids:
                report = await self.store.get_report(run_id)
                if report is None:
                    raise RuntimeError(f"Unknown approved run: {run_id}")
                dataset = await self.store.get_dataset(str(report.get("dataset_id") or ""))
                if dataset is None:
                    raise RuntimeError(f"Dataset is missing for run: {run_id}")
                market = (dataset.symbol, dataset.interval)
                if market in seen_markets:
                    raise RuntimeError(
                        f"Multiple active runs target the same market {market}; "
                        "choose exactly one approved run"
                    )
                seen_markets.add(market)
                child = StrategyAutomation(
                    self.runtime,
                    self.store,
                    StrategyAutomationSettings(
                        run_id=run_id,
                        symbols=(dataset.symbol,),
                        interval=dataset.interval,
                        environment=self.settings.environment,
                        poll_seconds=self.settings.poll_seconds,
                        history_bars=self.settings.history_bars,
                        max_consecutive_errors=self.settings.max_consecutive_errors,
                    ),
                )
                await child.start()
                started.append(child)
            self._children = started
            self._start_error = None
        except Exception as exc:
            self._children = started
            await self.stop()
            self._start_error = repr(exc)
            raise

    async def stop(self) -> None:
        children, self._children = self._children, []
        await asyncio.gather(
            *(child.stop() for child in reversed(children)),
            return_exceptions=True,
        )

    def status(self) -> dict[str, Any]:
        child_status = [child.status() for child in self._children]
        events: list[dict[str, Any]] = []
        for item in child_status:
            events.extend(item.get("recent_events") or [])
        events.sort(key=lambda item: int(item.get("observed_at_ms", 0)), reverse=True)
        symbols = tuple(
            symbol
            for item in child_status
            for symbol in tuple(item.get("symbols") or ())
        )
        return {
            "enabled": True,
            "running": self.running,
            "environment": self.settings.environment,
            "run_id": self.settings.run_ids[0] if len(self.settings.run_ids) == 1 else None,
            "run_ids": self.settings.run_ids,
            "run_count": len(self.settings.run_ids),
            "symbols": symbols,
            "start_error": self._start_error,
            "children": child_status,
            "recent_events": events[:50],
        }
