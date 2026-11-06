from __future__ import annotations

import asyncio
from collections.abc import Mapping, Sequence
from dataclasses import replace
from typing import Any

from .engine import ExecutionEngine
from .governor import PortfolioGovernor
from .models import ExecutionResult, ReconciliationReport, Signal
from .ports import ControlPlane, ExchangeAPI
from .recovery import RecoveryManager
from .supervisor import ProtectionSupervisor


class TradingRuntime:
    """Lifecycle owner for adapter, recovery, supervisors and signal admission."""

    def __init__(
        self,
        exchange_api: ExchangeAPI,
        control_plane: ControlPlane,
        engine: ExecutionEngine,
        protection_supervisor: ProtectionSupervisor,
        recovery_manager: RecoveryManager,
        portfolio_governor: PortfolioGovernor,
        *,
        startup_stream_timeout_seconds: float = 10.0,
        health_interval_seconds: float = 0.5,
    ) -> None:
        self.api = exchange_api
        self.control = control_plane
        self.engine = engine
        self.supervisor = protection_supervisor
        self.recovery = recovery_manager
        self.governor = portfolio_governor
        self.startup_stream_timeout_seconds = startup_stream_timeout_seconds
        self.health_interval_seconds = health_interval_seconds
        self._tasks: list[asyncio.Task[Any]] = []
        self._started = False
        self.last_reconciliation_report: ReconciliationReport | None = None
        self._stopping = asyncio.Event()


    @property
    def is_started(self) -> bool:
        return self._started

    async def start(self, symbols: Sequence[str]) -> ReconciliationReport:
        if self._started:
            raise RuntimeError("TradingRuntime is already started")
        self._stopping.clear()
        await self.control.halt_admission("runtime startup")
        try:
            await self.api.start(symbols)
            await self._wait_for_user_stream()
            report = await self.recovery.recover_startup()
            self.last_reconciliation_report = report
            self._tasks = [
                asyncio.create_task(self.supervisor.run(), name="protection-supervisor"),
                asyncio.create_task(self.governor.run(), name="portfolio-governor"),
                asyncio.create_task(self._health_loop(), name="runtime-health-monitor"),
            ]
            self._started = True
            return report
        except Exception:
            await self.control.halt_admission("runtime startup failed")
            await self.api.close()
            raise

    async def submit_signal(
        self, signal: Signal | Mapping[str, Any]
    ) -> ExecutionResult:
        if not self._started:
            raise RuntimeError("TradingRuntime has not been started")
        result = await self.engine.execute_signal(signal)
        if result.status in {"FAILED_SAFE", "FAILED_UNCERTAIN"}:
            await self.governor.record_api_failure(result.status)
        else:
            await self.governor.record_api_success()
        if result.status == "FAILED_UNCERTAIN":
            try:
                report = await self.recovery.recover_startup(resume_on_success=False)
                result = replace(
                    result,
                    warnings=result.warnings
                    + (
                        f"Immediate containment: repaired={report.repaired}, "
                        f"closed={report.closed}, unresolved={report.unresolved}",
                    ),
                )
            except Exception as exc:
                await self.control.halt_admission(
                    f"immediate containment failed: {exc!r}"
                )
                result = replace(
                    result,
                    warnings=result.warnings
                    + (f"Immediate containment failed: {exc!r}",),
                )
        return result

    async def stop(self) -> None:
        if not self._started:
            return
        self._stopping.set()
        self.supervisor.stop()
        self.governor.stop()
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks.clear()
        await self.api.close()
        self._started = False

    async def _wait_for_user_stream(self) -> None:
        deadline = asyncio.get_running_loop().time() + self.startup_stream_timeout_seconds
        while asyncio.get_running_loop().time() < deadline:
            if await self.api.is_user_stream_healthy():
                return
            await asyncio.sleep(0.1)
        await self.control.halt_admission("private user stream did not become healthy")
        raise RuntimeError("private user stream did not become healthy")

    async def _health_loop(self) -> None:
        while not self._stopping.is_set():
            try:
                if not await self.api.is_user_stream_healthy():
                    # Deliberately no automatic resume. A reconnect must be followed
                    # by reconciliation before an operator/restart reopens admission.
                    await self.control.halt_admission(
                        "runtime health monitor: private user stream unhealthy"
                    )
                for task in self._tasks:
                    if task is asyncio.current_task():
                        continue
                    if task.done() and not task.cancelled():
                        await self.control.halt_admission(
                            f"runtime health monitor: task stopped: {task.get_name()}"
                        )
            except Exception as exc:
                await self.control.halt_admission(
                    f"runtime health monitor failure: {exc!r}"
                )
            try:
                await asyncio.wait_for(
                    self._stopping.wait(), timeout=self.health_interval_seconds
                )
            except TimeoutError:
                pass
