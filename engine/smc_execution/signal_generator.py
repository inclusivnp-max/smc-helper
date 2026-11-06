from __future__ import annotations

import asyncio
import hashlib
import time
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

from .alpha_gate import GovernedSignalRouter
from .research_store import ResearchStore
from .shadow import ShadowBook
from .strategy import ResearchStrategy, SMCCompositeStrategy


@dataclass(frozen=True)
class SignalDaemonSettings:
    symbols: tuple[str, ...]
    interval: str = "5m"
    history_bars: int = 500
    poll_seconds: float = 5.0
    environment: str = "shadow"

    def __post_init__(self) -> None:
        if not self.symbols:
            raise ValueError("At least one symbol is required")
        if self.history_bars < 100:
            raise ValueError("history_bars must be >= 100")
        if self.poll_seconds < 1:
            raise ValueError("poll_seconds must be >= 1")
        if self.environment not in {"shadow", "testnet", "live"}:
            raise ValueError("environment must be shadow, testnet or live")


class StrategyManifestMismatch(RuntimeError):
    pass


class SMCSignalDaemon:
    """Closed-bar signal generator with durable provenance and no intrabar decisions."""

    def __init__(
        self,
        *,
        provider: Any,
        strategy: ResearchStrategy,
        run_id: str,
        settings: SignalDaemonSettings,
        router: GovernedSignalRouter | None = None,
        shadow_book: ShadowBook | None = None,
        on_event: Callable[[dict[str, Any]], Awaitable[None] | None] | None = None,
    ) -> None:
        self.provider = provider
        self.strategy = strategy
        self.run_id = run_id
        self.settings = settings
        self.router = router
        self.shadow_book = shadow_book
        self.on_event = on_event
        self._last_bar: dict[str, int] = {}
        self._stop = asyncio.Event()
        if settings.environment == "shadow" and shadow_book is None:
            raise ValueError("shadow environment requires ShadowBook")
        if settings.environment in {"testnet", "live"} and router is None:
            raise ValueError("execution environment requires GovernedSignalRouter")

    async def run(self) -> None:
        self._stop.clear()
        while not self._stop.is_set():
            await self.poll_once()
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.settings.poll_seconds)
            except TimeoutError:
                pass

    def stop(self) -> None:
        self._stop.set()

    async def poll_once(self) -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []
        for symbol in self.settings.symbols:
            try:
                bars = await self.provider.get_recent_bars(
                    symbol,
                    self.settings.interval,
                    limit=max(self.settings.history_bars, self.strategy.warmup_bars + 5),
                )
                if len(bars) < self.strategy.warmup_bars:
                    event = {
                        "type": "insufficient_history",
                        "symbol": symbol,
                        "bars": len(bars),
                    }
                    events.append(event)
                    await self._emit(event)
                    continue
                latest = bars[-1]
                if latest.close_time_ms <= self._last_bar.get(symbol, 0):
                    continue
                if self.settings.environment == "shadow":
                    assert self.shadow_book is not None
                    await self.shadow_book.process_closed_bar(latest, bars)
                decision = self.strategy.decide(bars)
                self._last_bar[symbol] = latest.close_time_ms
                if decision is None:
                    event = {
                        "type": "bar_processed",
                        "symbol": symbol,
                        "close_time_ms": latest.close_time_ms,
                        "signal": False,
                    }
                    events.append(event)
                    await self._emit(event)
                    continue
                signal_id = hashlib.sha256(
                    f"{self.run_id}|{symbol}|{decision.feature_time_ms}|{decision.action.value}".encode()
                ).hexdigest()[:32]
                payload = {
                    "signal_id": signal_id,
                    "symbol": symbol,
                    "action": decision.action.value,
                    "confidence": str(decision.confidence),
                    "created_at_ms": decision.feature_time_ms,
                    "reference_price": str(decision.reference_price),
                    "strategy_id": self.strategy.manifest.strategy_id,
                    "strategy_version": self.strategy.manifest.version,
                    "research_run_id": self.run_id,
                    "code_fingerprint": self.strategy.manifest.code_fingerprint,
                    "metadata": {
                        "reason": decision.reason,
                        "features": decision.features,
                        "generator": "SMCSignalDaemon",
                        "interval": self.settings.interval,
                    },
                }
                if self.settings.environment == "shadow":
                    assert self.shadow_book is not None
                    record = await self.shadow_book.register(
                        run_id=self.run_id,
                        manifest=self.strategy.manifest,
                        symbol=symbol,
                        decision=decision,
                    )
                    event = {
                        "type": "shadow_signal",
                        "symbol": symbol,
                        "signal_id": record["signal_id"],
                        "action": decision.action.value,
                        "confidence": str(decision.confidence),
                    }
                else:
                    assert self.router is not None
                    result = await self.router.submit(payload)
                    event = {
                        "type": "execution_result",
                        "symbol": symbol,
                        "signal_id": signal_id,
                        "status": result.status,
                        "warnings": list(result.warnings),
                    }
                events.append(event)
                await self._emit(event)
            except Exception as exc:
                event = {
                    "type": "generator_error",
                    "symbol": symbol,
                    "error": repr(exc),
                    "time_ms": int(time.time() * 1000),
                }
                events.append(event)
                await self._emit(event)
        return events

    async def _emit(self, event: dict[str, Any]) -> None:
        if self.on_event is None:
            return
        result = self.on_event(event)
        if asyncio.iscoroutine(result):
            await result


async def load_approved_smc_strategy(
    store: ResearchStore,
    run_id: str,
) -> SMCCompositeStrategy:
    report = await store.get_report(run_id)
    if report is None:
        raise KeyError(f"Unknown research run: {run_id}")
    manifest_data = report.get("manifest") or {}
    if manifest_data.get("strategy_id") != "smc_composite":
        raise StrategyManifestMismatch("Research run is not an SMC composite strategy")
    strategy = SMCCompositeStrategy(version=str(manifest_data.get("version", "1.0.0"))).with_parameters(
        manifest_data.get("parameters") or {}
    )
    expected = str(manifest_data.get("code_fingerprint") or "")
    if strategy.manifest.code_fingerprint != expected:
        raise StrategyManifestMismatch(
            "Installed strategy code/parameters do not match the approved research fingerprint"
        )
    return strategy
