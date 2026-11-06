from __future__ import annotations

import asyncio
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Any

from .binance_research import BinanceResearchDataClient, INTERVAL_MS
from .modes import DEFAULT_MODE_PROFILES, TradingMode
from .models import D
from .operator_store import OperatorStore
from .research_models import ApprovalStage, RunStatus, SimulationConfig
from .research_store import ResearchStore
from .validation import (
    WalkForwardConfig,
)


@dataclass(frozen=True)
class ResearchServiceSettings:
    allow_live_promotion: bool = False
    max_concurrent_runs: int = 1
    bootstrap_samples: int = 2_000
    minimum_dataset_bars: int = 10_000
    minimum_dataset_span_days: int = 90
    minimum_shadow_closed_trades: int = 20
    minimum_shadow_expectancy_r: D = D("0")
    maximum_download_span_days: int = 730


class ResearchCommandError(RuntimeError):
    pass


class ResearchService:
    def __init__(
        self,
        store: ResearchStore,
        audit: OperatorStore,
        settings: ResearchServiceSettings = ResearchServiceSettings(),
    ) -> None:
        self.store = store
        self.audit = audit
        self.settings = settings
        self._jobs: dict[str, dict[str, Any]] = {}
        self._tasks: dict[str, asyncio.Task[Any]] = {}
        self._data_jobs: dict[str, dict[str, Any]] = {}
        self._data_tasks: dict[str, asyncio.Task[Any]] = {}
        self._processes: dict[str, asyncio.subprocess.Process] = {}
        self._semaphore = asyncio.Semaphore(settings.max_concurrent_runs)
        self._download_semaphore = asyncio.Semaphore(1)
        self._lock = asyncio.Lock()
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return
        await self.store.initialize()
        await self.store.interrupt_incomplete_jobs()
        jobs = await self.store.list_jobs(limit=10_000)
        self._jobs = {
            str(item["run_id"]): dict(item)
            for item in jobs
            if item.get("job_type") == "RESEARCH_RUN" and item.get("run_id")
        }
        self._data_jobs = {
            str(item["job_id"]): dict(item)
            for item in jobs
            if item.get("job_type") == "DATASET_DOWNLOAD" and item.get("job_id")
        }
        self._initialized = True

    async def list_datasets(self, limit: int = 100):
        return await self.store.list_datasets(limit)

    async def list_data_jobs(self, limit: int = 100) -> list[dict[str, Any]]:
        jobs = sorted(
            self._data_jobs.values(),
            key=lambda item: int(item.get("created_at_ms", 0)),
            reverse=True,
        )
        return [dict(item) for item in jobs[:limit]]

    async def start_dataset_download(
        self,
        *,
        symbol: str,
        interval: str,
        start_time_ms: int,
        end_time_ms: int,
        include_mark_price: bool,
        include_funding: bool,
        include_open_interest: bool,
        dataset_id: str | None,
        actor: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        existing = await self.audit.find_idempotent(idempotency_key)
        if existing is not None:
            if existing["action"] != "RESEARCH_DOWNLOAD":
                raise ResearchCommandError(
                    "Idempotency-Key was already used for another command"
                )
            return existing["details"]
        symbol = symbol.upper().strip()
        if not symbol or len(symbol) > 30 or not symbol.isalnum():
            raise ResearchCommandError("Invalid Binance symbol")
        if interval not in INTERVAL_MS:
            raise ResearchCommandError(f"Unsupported interval: {interval}")
        if end_time_ms <= start_time_ms:
            raise ResearchCommandError("end_time_ms must be after start_time_ms")
        span_days = (end_time_ms - start_time_ms) / 86_400_000
        if span_days > self.settings.maximum_download_span_days:
            raise ResearchCommandError(
                f"Download span {span_days:.1f} days exceeds policy limit "
                f"{self.settings.maximum_download_span_days} days"
            )
        job_id = uuid.uuid4().hex
        job = {
            "job_id": job_id,
            "job_type": "DATASET_DOWNLOAD",
            "status": "PENDING",
            "symbol": symbol,
            "interval": interval,
            "start_time_ms": start_time_ms,
            "end_time_ms": end_time_ms,
            "dataset_id": dataset_id,
            "created_at_ms": int(time.time() * 1000),
            "updated_at_ms": int(time.time() * 1000),
            "progress": "queued",
            "include_mark_price": include_mark_price,
            "include_funding": include_funding,
            "include_open_interest": include_open_interest,
        }
        await self.store.save_job(job)
        await self.audit.append_audit(
            actor=actor,
            action="RESEARCH_DOWNLOAD",
            outcome="ACCEPTED",
            idempotency_key=idempotency_key,
            details=job,
        )
        async with self._lock:
            self._data_jobs[job_id] = job
            self._data_tasks[job_id] = asyncio.create_task(
                self._execute_dataset_download(
                    job_id=job_id,
                    symbol=symbol,
                    interval=interval,
                    start_time_ms=start_time_ms,
                    end_time_ms=end_time_ms,
                    include_mark_price=include_mark_price,
                    include_funding=include_funding,
                    include_open_interest=include_open_interest,
                    dataset_id=dataset_id,
                ),
                name=f"research-download:{job_id[:8]}",
            )
        return dict(job)

    async def _execute_dataset_download(self, *, job_id: str, **kwargs: Any) -> None:
        async with self._download_semaphore:
            await self._update_data_job(
                job_id,
                status="RUNNING",
                progress="downloading public Binance data",
            )
            try:
                async with BinanceResearchDataClient() as client:
                    dataset = await client.download_dataset(**kwargs)
                await self.store.save_dataset(dataset)
                await self._update_data_job(
                    job_id,
                    status="COMPLETED",
                    progress="stored",
                    dataset_id=dataset.dataset_id,
                    fingerprint=dataset.fingerprint,
                    rows=len(dataset.bars),
                    quality=dataset.quality.to_dict(),
                    completed_at_ms=int(time.time() * 1000),
                )
            except asyncio.CancelledError:
                await self._update_data_job(
                    job_id,
                    status="INTERRUPTED",
                    progress="cancelled during shutdown",
                    completed_at_ms=int(time.time() * 1000),
                )
                raise
            except Exception as exc:
                await self._update_data_job(
                    job_id,
                    status="FAILED",
                    progress="failed",
                    error=repr(exc),
                    completed_at_ms=int(time.time() * 1000),
                )
            finally:
                async with self._lock:
                    self._data_tasks.pop(job_id, None)

    async def list_runs(self, limit: int = 100):
        stored = await self.store.list_runs(limit)
        active = list(self._jobs.values())
        active_ids = {item["run_id"] for item in active}
        return active + [item for item in stored if item["run_id"] not in active_ids]

    async def get_report(self, run_id: str):
        report = await self.store.get_report(run_id)
        if report is not None:
            return report
        return self._jobs.get(run_id)

    async def list_approvals(self, limit: int = 100):
        return await self.store.list_approvals(limit)

    async def shadow_summary(self, run_id: str):
        return await self.store.shadow_summary(run_id)

    async def list_shadow(self, run_id: str | None = None, limit: int = 200):
        return await self.store.list_shadow(run_id, limit)

    async def start_run(
        self,
        *,
        dataset_id: str,
        mode: TradingMode,
        actor: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        existing = await self.audit.find_idempotent(idempotency_key)
        if existing is not None:
            if existing["action"] != "RESEARCH_RUN":
                raise ResearchCommandError(
                    "Idempotency-Key was already used for another command"
                )
            return existing["details"]
        dataset = await self.store.get_dataset(dataset_id)
        if dataset is None:
            raise ResearchCommandError(f"Unknown dataset: {dataset_id}")
        if not dataset.quality.passed:
            raise ResearchCommandError(
                "Dataset failed strict quality checks: "
                + "; ".join(dataset.quality.warnings)
            )
        if len(dataset.bars) < self.settings.minimum_dataset_bars:
            raise ResearchCommandError(
                f"Dataset has {len(dataset.bars)} bars; minimum is "
                f"{self.settings.minimum_dataset_bars}"
            )
        span_ms = dataset.bars[-1].close_time_ms - dataset.bars[0].open_time_ms
        minimum_span_ms = self.settings.minimum_dataset_span_days * 86_400_000
        if minimum_span_ms > 0 and span_ms < minimum_span_ms:
            actual_days = span_ms / 86_400_000
            raise ResearchCommandError(
                f"Dataset spans {actual_days:.1f} days; minimum is "
                f"{self.settings.minimum_dataset_span_days} days"
            )
        if mode is TradingMode.PAUSED:
            raise ResearchCommandError("PAUSED is not a simulation profile")
        run_id = uuid.uuid4().hex
        job = {
            "job_id": run_id,
            "job_type": "RESEARCH_RUN",
            "run_id": run_id,
            "dataset_id": dataset_id,
            "mode": mode.value,
            "status": RunStatus.PENDING.value,
            "created_at_ms": int(time.time() * 1000),
            "updated_at_ms": int(time.time() * 1000),
            "progress": "queued",
        }
        await self.store.save_job(job)
        await self.audit.append_audit(
            actor=actor,
            action="RESEARCH_RUN",
            outcome="ACCEPTED",
            idempotency_key=idempotency_key,
            details=job,
        )
        async with self._lock:
            self._jobs[run_id] = job
            task = asyncio.create_task(
                self._execute_run(run_id, dataset, mode),
                name=f"research-run:{run_id[:8]}",
            )
            self._tasks[run_id] = task
        return dict(job)

    async def _execute_run(self, run_id, dataset, mode: TradingMode) -> None:
        async with self._semaphore:
            await self._update_run_job(
                run_id,
                status=RunStatus.RUNNING.value,
                progress="isolated walk-forward worker",
            )
            process: asyncio.subprocess.Process | None = None
            try:
                process = await asyncio.create_subprocess_exec(
                    sys.executable,
                    "-m",
                    "smc_execution.research_worker",
                    "--db",
                    str(self.store.path.resolve()),
                    "--dataset-id",
                    dataset.dataset_id,
                    "--mode",
                    mode.value,
                    "--run-id",
                    run_id,
                    "--bootstrap-samples",
                    str(self.settings.bootstrap_samples),
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
                self._processes[run_id] = process
                stdout, stderr = await process.communicate()
                report = await self.store.get_report(run_id)
                if process.returncode != 0 or report is None:
                    detail = stderr.decode("utf-8", "replace")[-2000:].strip()
                    raise RuntimeError(
                        f"research worker exited {process.returncode}: {detail}"
                    )
                await self._update_run_job(
                    run_id,
                    dataset_id=dataset.dataset_id,
                    mode=mode.value,
                    status=report.get("status", RunStatus.FAILED.value),
                    created_at_ms=report.get("created_at_ms"),
                    completed_at_ms=report.get("completed_at_ms"),
                    gate_passed=(report.get("gate") or {}).get("passed"),
                    gate_score=(report.get("gate") or {}).get("score"),
                    progress="completed",
                    worker_stdout=stdout.decode("utf-8", "replace")[-1000:].strip(),
                )
            except asyncio.CancelledError:
                if process is not None and process.returncode is None:
                    process.terminate()
                    try:
                        await asyncio.wait_for(process.wait(), timeout=5)
                    except TimeoutError:
                        process.kill()
                        await process.wait()
                await self._update_run_job(
                    run_id,
                    status="INTERRUPTED",
                    progress="cancelled during shutdown",
                    completed_at_ms=int(time.time() * 1000),
                )
                raise
            except Exception as exc:
                await self._update_run_job(
                    run_id,
                    status=RunStatus.FAILED.value,
                    progress="failed",
                    error=repr(exc),
                    completed_at_ms=int(time.time() * 1000),
                )
            finally:
                self._processes.pop(run_id, None)
                async with self._lock:
                    self._tasks.pop(run_id, None)

    async def _update_data_job(self, job_id: str, **patch: Any) -> dict[str, Any]:
        updated = {
            **self._data_jobs[job_id],
            **patch,
            "updated_at_ms": int(time.time() * 1000),
        }
        self._data_jobs[job_id] = updated
        await self.store.save_job(updated)
        return updated

    async def _update_run_job(self, run_id: str, **patch: Any) -> dict[str, Any]:
        updated = {
            **self._jobs[run_id],
            **patch,
            "updated_at_ms": int(time.time() * 1000),
        }
        self._jobs[run_id] = updated
        await self.store.save_job(updated)
        return updated

    async def shutdown(self) -> None:
        tasks = tuple(self._tasks.values()) + tuple(self._data_tasks.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        for process in tuple(self._processes.values()):
            if process.returncode is None:
                process.terminate()
        self._tasks.clear()
        self._data_tasks.clear()
        self._processes.clear()

    async def promote(
        self,
        *,
        run_id: str,
        stage: ApprovalStage,
        actor: str,
        reason: str,
        idempotency_key: str,
        expires_at_ms: int | None = None,
    ) -> dict[str, Any]:
        existing = await self.audit.find_idempotent(idempotency_key)
        if existing is not None:
            if existing["action"] != "RESEARCH_PROMOTE":
                raise ResearchCommandError(
                    "Idempotency-Key was already used for another command"
                )
            return existing["details"]
        if stage is ApprovalStage.LIVE and not self.settings.allow_live_promotion:
            raise ResearchCommandError(
                "LIVE promotion is disabled by policy. Enable it only after an "
                "independent testnet/shadow review."
            )
        report = await self.store.get_report(run_id)
        if report is None:
            raise ResearchCommandError(f"Unknown research run: {run_id}")
        manifest = report.get("manifest") or {}
        simulation = report.get("simulation") or {}
        research_profile = str(simulation.get("profile") or "CUSTOM")
        if stage in {
            ApprovalStage.SHADOW,
            ApprovalStage.TESTNET,
            ApprovalStage.LIVE,
        } and research_profile not in {
            TradingMode.CAPITAL_PROTECTION.value,
            TradingMode.BALANCED.value,
            TradingMode.PROFITABILITY.value,
        }:
            raise ResearchCommandError(
                "Deployable approval requires a research run bound to a named risk profile"
            )
        strategy_id = str(manifest.get("strategy_id") or "")
        strategy_version = str(manifest.get("version") or "")
        if not strategy_id or not strategy_version:
            raise ResearchCommandError("Research report has no deployable manifest")
        if stage is ApprovalStage.SHADOW:
            prior = await self.store.latest_approval(
                strategy_id, strategy_version, "research"
            )
            if prior is None or prior.run_id != run_id or prior.stage is not ApprovalStage.RESEARCH_PASSED:
                raise ResearchCommandError(
                    "SHADOW requires a RESEARCH_PASSED approval for the same run"
                )
        elif stage is ApprovalStage.TESTNET:
            prior = await self.store.latest_approval(
                strategy_id, strategy_version, "shadow"
            )
            if prior is None or prior.run_id != run_id or prior.stage is not ApprovalStage.SHADOW:
                raise ResearchCommandError(
                    "TESTNET requires a SHADOW approval for the same run"
                )
            summary = await self.store.shadow_summary(run_id)
            if int(summary["closed"]) < self.settings.minimum_shadow_closed_trades:
                raise ResearchCommandError(
                    f"Shadow closed trades {summary['closed']} < "
                    f"{self.settings.minimum_shadow_closed_trades}"
                )
            if D(str(summary["expectancy_r"])) < self.settings.minimum_shadow_expectancy_r:
                raise ResearchCommandError(
                    "Shadow expectancy is below the configured threshold"
                )
        elif stage is ApprovalStage.LIVE:
            prior = await self.store.latest_approval(
                strategy_id, strategy_version, "testnet"
            )
            if prior is None or prior.run_id != run_id or prior.stage is not ApprovalStage.TESTNET:
                raise ResearchCommandError(
                    "LIVE requires a TESTNET approval for the same run"
                )

        environment = {
            ApprovalStage.RESEARCH_PASSED: "research",
            ApprovalStage.SHADOW: "shadow",
            ApprovalStage.TESTNET: "testnet",
            ApprovalStage.LIVE: "live",
            ApprovalStage.REVOKED: "live",
            ApprovalStage.DRAFT: "research",
        }[stage]
        try:
            approval = await self.store.promote(
                run_id=run_id,
                stage=stage,
                environment=environment,
                actor=actor,
                reason=reason,
                expires_at_ms=expires_at_ms,
            )
        except (KeyError, ValueError) as exc:
            raise ResearchCommandError(str(exc)) from exc
        details = approval.to_dict()
        await self.audit.append_audit(
            actor=actor,
            action="RESEARCH_PROMOTE",
            outcome="SUCCESS",
            idempotency_key=idempotency_key,
            details=details,
        )
        return details


def simulation_for_mode(mode: TradingMode) -> SimulationConfig:
    profile = DEFAULT_MODE_PROFILES[mode]
    execution = profile.execution_overrides
    protection = profile.dynamic_protection
    return SimulationConfig(
        profile=mode.value,
        risk_per_trade_pct=D(str(execution.get("risk_per_trade_pct", "0.003"))),
        max_position_notional=D(
            str(execution.get("max_position_notional", "2000"))
        ),
        stop_atr_multiple=D(str(execution.get("stop_atr_multiple", "1.5"))),
        take_profit_atr_multiple=D(
            str(execution.get("take_profit_atr_multiple", "2.25"))
        ),
        max_adverse_slippage_bps=D(
            str(execution.get("max_adverse_slippage_bps", "8"))
        ),
        min_confidence=D(str(execution.get("min_confidence", "0.70"))),
        minimum_reward_risk=D(
            str(execution.get("minimum_reward_risk", "1.25"))
        ),
        breakeven_activation_r=(
            protection.breakeven_activation_r if protection.enabled else None
        ),
        breakeven_lock_bps=protection.breakeven_lock_bps,
        trailing_activation_r=(
            protection.trailing_activation_r if protection.enabled else None
        ),
        trailing_atr_multiple=(
            protection.trailing_atr_multiple if protection.enabled else None
        ),
    )


def adaptive_walk_forward(rows: int) -> WalkForwardConfig:
    """Build rolling windows with enough independent OOS folds for governance."""
    train = max(800, int(rows * 0.45))
    test = max(200, int(rows * 0.10))
    purge = max(50, int(rows * 0.01))
    embargo = max(5, purge // 5)
    if train + purge + test > rows:
        train = max(500, int(rows * 0.60))
        remaining = rows - train - purge
        test = max(50, remaining)
    return WalkForwardConfig(
        train_bars=train,
        test_bars=test,
        step_bars=test,
        purge_bars=purge,
        embargo_bars=embargo,
        min_train_trades=10,
    )
