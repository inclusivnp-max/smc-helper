from __future__ import annotations

import asyncio
import json
import sqlite3
import time
import zlib
from contextlib import closing
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping

from .models import D, encode_json
from .research_data import DataQualityReport, ResearchDataset
from .research_models import (
    METRICS_ALGORITHM_VERSION,
    ApprovalStage,
    ResearchBar,
    ResearchReport,
)


@dataclass(frozen=True)
class StrategyApproval:
    strategy_id: str
    strategy_version: str
    code_fingerprint: str
    dataset_fingerprint: str
    run_id: str
    stage: ApprovalStage
    environment: str
    actor: str
    reason: str
    created_at_ms: int
    expires_at_ms: int | None = None

    @property
    def active(self) -> bool:
        return (
            self.stage is not ApprovalStage.REVOKED
            and (self.expires_at_ms is None or self.expires_at_ms > int(time.time() * 1000))
        )

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


class ResearchStore:
    SCHEMA_VERSION = 2

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = asyncio.Lock()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA busy_timeout=30000")
        return connection

    async def initialize(self) -> None:
        async with self._lock:
            await asyncio.to_thread(self._initialize_sync)

    def _initialize_sync(self) -> None:
        with closing(self._connect()) as connection:
            version = int(connection.execute("PRAGMA user_version").fetchone()[0])
            if version > self.SCHEMA_VERSION:
                raise RuntimeError(
                    f"Research DB schema {version} is newer than supported "
                    f"{self.SCHEMA_VERSION}"
                )
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS research_datasets (
                    dataset_id TEXT PRIMARY KEY,
                    symbol TEXT NOT NULL,
                    interval_name TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    rows_count INTEGER NOT NULL,
                    start_time_ms INTEGER NOT NULL,
                    end_time_ms INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    quality_json TEXT NOT NULL,
                    bars_blob BLOB NOT NULL,
                    created_at_ms INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS idx_research_dataset_fingerprint
                    ON research_datasets(fingerprint);

                CREATE TABLE IF NOT EXISTS research_runs (
                    run_id TEXT PRIMARY KEY,
                    dataset_id TEXT NOT NULL,
                    strategy_id TEXT NOT NULL,
                    strategy_version TEXT NOT NULL,
                    code_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL,
                    gate_passed INTEGER,
                    gate_score TEXT,
                    report_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER,
                    FOREIGN KEY(dataset_id) REFERENCES research_datasets(dataset_id)
                );
                CREATE INDEX IF NOT EXISTS idx_research_runs_created
                    ON research_runs(created_at_ms DESC);

                CREATE TABLE IF NOT EXISTS research_jobs (
                    job_id TEXT PRIMARY KEY,
                    job_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    completed_at_ms INTEGER
                );
                CREATE INDEX IF NOT EXISTS idx_research_jobs_created
                    ON research_jobs(created_at_ms DESC);

                CREATE TABLE IF NOT EXISTS strategy_approvals (
                    approval_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    strategy_id TEXT NOT NULL,
                    strategy_version TEXT NOT NULL,
                    code_fingerprint TEXT NOT NULL,
                    dataset_fingerprint TEXT NOT NULL,
                    run_id TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    environment TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER,
                    FOREIGN KEY(run_id) REFERENCES research_runs(run_id)
                );
                CREATE INDEX IF NOT EXISTS idx_strategy_approvals_lookup
                    ON strategy_approvals(strategy_id, strategy_version, environment, created_at_ms DESC);


                CREATE TABLE IF NOT EXISTS shadow_trades (
                    signal_id TEXT PRIMARY KEY,
                    run_id TEXT NOT NULL,
                    strategy_id TEXT NOT NULL,
                    strategy_version TEXT NOT NULL,
                    code_fingerprint TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    action TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    signal_time_ms INTEGER NOT NULL,
                    reference_price TEXT NOT NULL,
                    status TEXT NOT NULL,
                    entry_time_ms INTEGER,
                    entry_price TEXT,
                    stop_price TEXT,
                    take_profit_price TEXT,
                    exit_time_ms INTEGER,
                    exit_price TEXT,
                    r_multiple TEXT,
                    exit_reason TEXT,
                    state_json TEXT NOT NULL DEFAULT '{}',
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_shadow_open
                    ON shadow_trades(symbol, status, signal_time_ms);
                CREATE INDEX IF NOT EXISTS idx_shadow_run
                    ON shadow_trades(run_id, status, updated_at_ms DESC);
                """
            )
            shadow_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(shadow_trades)").fetchall()
            }
            if "state_json" not in shadow_columns:
                connection.execute(
                    "ALTER TABLE shadow_trades ADD COLUMN state_json TEXT NOT NULL DEFAULT '{}'"
                )
            connection.execute(f"PRAGMA user_version={self.SCHEMA_VERSION}")

    async def save_job(self, job: Mapping[str, Any]) -> None:
        async with self._lock:
            await asyncio.to_thread(self._save_job_sync, dict(job))

    def _save_job_sync(self, job: dict[str, Any]) -> None:
        job_id = str(job.get("job_id") or job.get("run_id") or "").strip()
        job_type = str(job.get("job_type") or "").strip()
        status = str(job.get("status") or "").strip()
        if not job_id or not job_type or not status:
            raise ValueError("Durable research job requires id, job_type and status")
        created_at_ms = int(job.get("created_at_ms") or int(time.time() * 1000))
        updated_at_ms = int(job.get("updated_at_ms") or int(time.time() * 1000))
        completed_raw = job.get("completed_at_ms")
        completed_at_ms = None if completed_raw is None else int(completed_raw)
        normalized = {
            **job,
            "job_id": job_id,
            "job_type": job_type,
            "status": status,
            "created_at_ms": created_at_ms,
            "updated_at_ms": updated_at_ms,
        }
        payload = json.dumps(
            encode_json(normalized), sort_keys=True, separators=(",", ":")
        )
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """
                INSERT INTO research_jobs(
                    job_id,job_type,status,payload_json,created_at_ms,
                    updated_at_ms,completed_at_ms
                ) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(job_id) DO UPDATE SET
                    job_type=excluded.job_type,
                    status=excluded.status,
                    payload_json=excluded.payload_json,
                    updated_at_ms=excluded.updated_at_ms,
                    completed_at_ms=excluded.completed_at_ms
                """,
                (
                    job_id,
                    job_type,
                    status,
                    payload,
                    created_at_ms,
                    updated_at_ms,
                    completed_at_ms,
                ),
            )
            connection.execute("COMMIT")

    async def list_jobs(
        self, *, job_type: str | None = None, limit: int = 1000
    ) -> list[dict[str, Any]]:
        async with self._lock:
            return await asyncio.to_thread(self._list_jobs_sync, job_type, limit)

    def _list_jobs_sync(
        self, job_type: str | None, limit: int
    ) -> list[dict[str, Any]]:
        bounded = max(1, min(int(limit), 10_000))
        with closing(self._connect()) as connection:
            if job_type is None:
                rows = connection.execute(
                    "SELECT payload_json FROM research_jobs "
                    "ORDER BY created_at_ms DESC LIMIT ?",
                    (bounded,),
                ).fetchall()
            else:
                rows = connection.execute(
                    "SELECT payload_json FROM research_jobs WHERE job_type=? "
                    "ORDER BY created_at_ms DESC LIMIT ?",
                    (job_type, bounded),
                ).fetchall()
        return [json.loads(row["payload_json"]) for row in rows]

    async def interrupt_incomplete_jobs(self) -> int:
        async with self._lock:
            return await asyncio.to_thread(self._interrupt_incomplete_jobs_sync)

    def _interrupt_incomplete_jobs_sync(self) -> int:
        now = int(time.time() * 1000)
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                "SELECT job_id,payload_json FROM research_jobs "
                "WHERE status IN ('PENDING','RUNNING')"
            ).fetchall()
            for row in rows:
                payload = json.loads(row["payload_json"])
                payload.update(
                    {
                        "status": "INTERRUPTED",
                        "progress": "interrupted by service restart",
                        "completed_at_ms": now,
                        "updated_at_ms": now,
                    }
                )
                connection.execute(
                    """
                    UPDATE research_jobs
                    SET status='INTERRUPTED',payload_json=?,updated_at_ms=?,completed_at_ms=?
                    WHERE job_id=?
                    """,
                    (
                        json.dumps(
                            encode_json(payload), sort_keys=True, separators=(",", ":")
                        ),
                        now,
                        now,
                        row["job_id"],
                    ),
                )
            connection.execute("COMMIT")
        return len(rows)

    async def save_dataset(self, dataset: ResearchDataset) -> None:
        async with self._lock:
            await asyncio.to_thread(self._save_dataset_sync, dataset)

    def _save_dataset_sync(self, dataset: ResearchDataset) -> None:
        payload = json.dumps(
            [bar.to_dict() for bar in dataset.bars],
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        blob = zlib.compress(payload, level=9)
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                existing_id = connection.execute(
                    "SELECT dataset_id, fingerprint FROM research_datasets "
                    "WHERE dataset_id = ?",
                    (dataset.dataset_id,),
                ).fetchone()
                if existing_id is not None:
                    if existing_id["fingerprint"] == dataset.fingerprint:
                        connection.commit()
                        return
                    raise ValueError(
                        f"Dataset ID {dataset.dataset_id!r} is immutable and already "
                        "points to another fingerprint"
                    )
                existing_fingerprint = connection.execute(
                    "SELECT dataset_id FROM research_datasets WHERE fingerprint = ?",
                    (dataset.fingerprint,),
                ).fetchone()
                if existing_fingerprint is not None:
                    raise ValueError(
                        "This immutable dataset fingerprint is already stored as "
                        f"{existing_fingerprint['dataset_id']!r}"
                    )
                connection.execute(
                    """
                    INSERT INTO research_datasets(
                        dataset_id, symbol, interval_name, fingerprint, rows_count,
                        start_time_ms, end_time_ms, source, quality_json, bars_blob,
                        created_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        dataset.dataset_id,
                        dataset.symbol,
                        dataset.interval,
                        dataset.fingerprint,
                        len(dataset.bars),
                        dataset.bars[0].open_time_ms,
                        dataset.bars[-1].close_time_ms,
                        dataset.source,
                        json.dumps(dataset.quality.to_dict(), sort_keys=True),
                        blob,
                        int(time.time() * 1000),
                    ),
                )
                connection.commit()
            except Exception:
                connection.rollback()
                raise

    async def get_dataset(self, dataset_id: str) -> ResearchDataset | None:
        async with self._lock:
            return await asyncio.to_thread(self._get_dataset_sync, dataset_id)

    def _get_dataset_sync(self, dataset_id: str) -> ResearchDataset | None:
        with closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT * FROM research_datasets WHERE dataset_id = ?", (dataset_id,)
            ).fetchone()
        if row is None:
            return None
        bars_payload = json.loads(zlib.decompress(row["bars_blob"]).decode("utf-8"))
        bars = tuple(_bar_from_dict(item) for item in bars_payload)
        quality_data = json.loads(row["quality_json"])
        quality = DataQualityReport(
            rows=int(quality_data["rows"]),
            duplicate_timestamps=int(quality_data["duplicate_timestamps"]),
            out_of_order_rows=int(quality_data["out_of_order_rows"]),
            gaps=int(quality_data["gaps"]),
            inconsistent_intervals=int(quality_data["inconsistent_intervals"]),
            unavailable_future_features=int(quality_data["unavailable_future_features"]),
            warnings=tuple(quality_data.get("warnings", ())),
        )
        return ResearchDataset(
            dataset_id=row["dataset_id"],
            symbol=row["symbol"],
            interval=row["interval_name"],
            bars=bars,
            fingerprint=row["fingerprint"],
            quality=quality,
            source=row["source"],
        )

    async def list_datasets(self, limit: int = 100) -> list[dict[str, Any]]:
        async with self._lock:
            return await asyncio.to_thread(self._list_datasets_sync, limit)

    def _list_datasets_sync(self, limit: int) -> list[dict[str, Any]]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """
                SELECT dataset_id, symbol, interval_name, fingerprint, rows_count,
                       start_time_ms, end_time_ms, source, quality_json, created_at_ms
                FROM research_datasets ORDER BY created_at_ms DESC LIMIT ?
                """,
                (limit,),
            ).fetchall()
        result = []
        for row in rows:
            result.append(
                {
                    "dataset_id": row["dataset_id"],
                    "symbol": row["symbol"],
                    "interval": row["interval_name"],
                    "fingerprint": row["fingerprint"],
                    "rows": row["rows_count"],
                    "start_time_ms": row["start_time_ms"],
                    "end_time_ms": row["end_time_ms"],
                    "source": row["source"],
                    "quality": json.loads(row["quality_json"]),
                    "created_at_ms": row["created_at_ms"],
                }
            )
        return result

    async def save_report(self, report: ResearchReport) -> None:
        async with self._lock:
            await asyncio.to_thread(self._save_report_sync, report)

    def _save_report_sync(self, report: ResearchReport) -> None:
        gate_passed = None if report.gate is None else int(report.gate.passed)
        gate_score = None if report.gate is None else str(report.gate.score)
        report_json = json.dumps(report.to_dict(), sort_keys=True)
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                existing = connection.execute(
                    "SELECT report_json FROM research_runs WHERE run_id = ?",
                    (report.run_id,),
                ).fetchone()
                if existing is not None:
                    if existing["report_json"] == report_json:
                        connection.commit()
                        return
                    raise ValueError(
                        f"Research run {report.run_id!r} is immutable and already exists"
                    )
                connection.execute(
                    """
                    INSERT INTO research_runs(
                        run_id, dataset_id, strategy_id, strategy_version,
                        code_fingerprint, status, gate_passed, gate_score,
                        report_json, created_at_ms, completed_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        report.run_id,
                        report.dataset_id,
                        report.manifest.strategy_id,
                        report.manifest.version,
                        report.manifest.code_fingerprint,
                        report.status.value,
                        gate_passed,
                        gate_score,
                        report_json,
                        report.created_at_ms,
                        report.completed_at_ms,
                    ),
                )
                connection.commit()
            except Exception:
                connection.rollback()
                raise

    async def get_report(self, run_id: str) -> dict[str, Any] | None:
        async with self._lock:
            return await asyncio.to_thread(self._get_report_sync, run_id)

    def _get_report_sync(self, run_id: str) -> dict[str, Any] | None:
        with closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT report_json FROM research_runs WHERE run_id = ?", (run_id,)
            ).fetchone()
        return None if row is None else json.loads(row["report_json"])

    async def list_runs(self, limit: int = 100) -> list[dict[str, Any]]:
        async with self._lock:
            return await asyncio.to_thread(self._list_runs_sync, limit)

    def _list_runs_sync(self, limit: int) -> list[dict[str, Any]]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """
                SELECT run_id, dataset_id, strategy_id, strategy_version,
                       code_fingerprint, status, gate_passed, gate_score,
                       created_at_ms, completed_at_ms
                FROM research_runs ORDER BY created_at_ms DESC LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [dict(row) for row in rows]

    async def promote(
        self,
        *,
        run_id: str,
        stage: ApprovalStage,
        environment: str,
        actor: str,
        reason: str,
        expires_at_ms: int | None = None,
    ) -> StrategyApproval:
        async with self._lock:
            return await asyncio.to_thread(
                self._promote_sync,
                run_id,
                stage,
                environment,
                actor,
                reason,
                expires_at_ms,
            )

    def _promote_sync(
        self,
        run_id: str,
        stage: ApprovalStage,
        environment: str,
        actor: str,
        reason: str,
        expires_at_ms: int | None,
    ) -> StrategyApproval:
        now = int(time.time() * 1000)
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                run = connection.execute(
                    "SELECT * FROM research_runs WHERE run_id = ?", (run_id,)
                ).fetchone()
                if run is None:
                    raise KeyError(f"Unknown research run: {run_id}")
                report = json.loads(run["report_json"])
                gate = report.get("gate") or {}
                # Authorising a run is gated on its evidence; retiring one must
                # never be, or a pre-upgrade approval could not be revoked.
                authorising = stage in {
                    ApprovalStage.RESEARCH_PASSED,
                    ApprovalStage.SHADOW,
                    ApprovalStage.TESTNET,
                    ApprovalStage.LIVE,
                }
                stored_version = int(report.get("metrics_algorithm_version") or 0)
                if authorising and stored_version < METRICS_ALGORITHM_VERSION:
                    raise ValueError(
                        f"Research run {run_id} was produced by metrics algorithm "
                        f"version {stored_version}; this build requires "
                        f"{METRICS_ALGORITHM_VERSION}. The stored gate decision was "
                        "computed under different metric semantics and cannot be "
                        "recomputed from the report, so the run must be repeated."
                    )
                if authorising and not gate.get("passed", False):
                    raise ValueError("Research run did not pass the alpha gate")
                if stage is ApprovalStage.LIVE and environment.lower() != "live":
                    raise ValueError("LIVE approval requires environment='live'")
                approval = StrategyApproval(
                    strategy_id=run["strategy_id"],
                    strategy_version=run["strategy_version"],
                    code_fingerprint=run["code_fingerprint"],
                    dataset_fingerprint=report["dataset_fingerprint"],
                    run_id=run_id,
                    stage=stage,
                    environment=environment.lower(),
                    actor=actor,
                    reason=reason,
                    created_at_ms=now,
                    expires_at_ms=expires_at_ms,
                )
                connection.execute(
                    """
                    INSERT INTO strategy_approvals(
                        strategy_id, strategy_version, code_fingerprint,
                        dataset_fingerprint, run_id, stage, environment,
                        actor, reason, created_at_ms, expires_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        approval.strategy_id,
                        approval.strategy_version,
                        approval.code_fingerprint,
                        approval.dataset_fingerprint,
                        approval.run_id,
                        approval.stage.value,
                        approval.environment,
                        approval.actor,
                        approval.reason,
                        approval.created_at_ms,
                        approval.expires_at_ms,
                    ),
                )
                connection.commit()
                return approval
            except Exception:
                connection.rollback()
                raise

    async def latest_approval(
        self,
        strategy_id: str,
        strategy_version: str,
        environment: str,
    ) -> StrategyApproval | None:
        async with self._lock:
            return await asyncio.to_thread(
                self._latest_approval_sync,
                strategy_id,
                strategy_version,
                environment,
            )

    def _latest_approval_sync(
        self, strategy_id: str, strategy_version: str, environment: str
    ) -> StrategyApproval | None:
        with closing(self._connect()) as connection:
            row = connection.execute(
                """
                SELECT * FROM strategy_approvals
                WHERE strategy_id = ? AND strategy_version = ? AND environment = ?
                ORDER BY created_at_ms DESC, approval_id DESC LIMIT 1
                """,
                (strategy_id, strategy_version, environment.lower()),
            ).fetchone()
        if row is None:
            return None
        return StrategyApproval(
            strategy_id=row["strategy_id"],
            strategy_version=row["strategy_version"],
            code_fingerprint=row["code_fingerprint"],
            dataset_fingerprint=row["dataset_fingerprint"],
            run_id=row["run_id"],
            stage=ApprovalStage(row["stage"]),
            environment=row["environment"],
            actor=row["actor"],
            reason=row["reason"],
            created_at_ms=row["created_at_ms"],
            expires_at_ms=row["expires_at_ms"],
        )

    async def list_approvals(self, limit: int = 100) -> list[dict[str, Any]]:
        async with self._lock:
            return await asyncio.to_thread(self._list_approvals_sync, limit)

    def _list_approvals_sync(self, limit: int) -> list[dict[str, Any]]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """
                SELECT * FROM strategy_approvals
                ORDER BY created_at_ms DESC, approval_id DESC LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [
            StrategyApproval(
                strategy_id=row["strategy_id"],
                strategy_version=row["strategy_version"],
                code_fingerprint=row["code_fingerprint"],
                dataset_fingerprint=row["dataset_fingerprint"],
                run_id=row["run_id"],
                stage=ApprovalStage(row["stage"]),
                environment=row["environment"],
                actor=row["actor"],
                reason=row["reason"],
                created_at_ms=row["created_at_ms"],
                expires_at_ms=row["expires_at_ms"],
            ).to_dict()
            for row in rows
        ]



    async def create_shadow_signal(self, values: Mapping[str, Any]) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(self._create_shadow_signal_sync, dict(values))

    def _create_shadow_signal_sync(self, values: dict[str, Any]) -> dict[str, Any]:
        now = int(time.time() * 1000)
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                connection.execute(
                    """
                    INSERT OR IGNORE INTO shadow_trades(
                        signal_id, run_id, strategy_id, strategy_version,
                        code_fingerprint, symbol, action, confidence,
                        signal_time_ms, reference_price, status,
                        created_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """,
                    (
                        values["signal_id"], values["run_id"], values["strategy_id"],
                        values["strategy_version"], values["code_fingerprint"],
                        values["symbol"], values["action"], str(values["confidence"]),
                        int(values["signal_time_ms"]), str(values["reference_price"]), now, now,
                    ),
                )
                row = connection.execute(
                    "SELECT * FROM shadow_trades WHERE signal_id=?", (values["signal_id"],)
                ).fetchone()
                connection.commit()
                assert row is not None
                return dict(row)
            except Exception:
                connection.rollback()
                raise

    async def list_active_shadow(self, symbol: str | None = None) -> list[dict[str, Any]]:
        async with self._lock:
            return await asyncio.to_thread(self._list_active_shadow_sync, symbol)

    def _list_active_shadow_sync(self, symbol: str | None) -> list[dict[str, Any]]:
        query = "SELECT * FROM shadow_trades WHERE status IN ('PENDING','OPEN')"
        params: tuple[Any, ...] = ()
        if symbol is not None:
            query += " AND symbol=?"
            params = (symbol.upper(),)
        query += " ORDER BY signal_time_ms"
        with closing(self._connect()) as connection:
            rows = connection.execute(query, params).fetchall()
        return [dict(row) for row in rows]

    async def activate_shadow(
        self,
        signal_id: str,
        *,
        entry_time_ms: int,
        entry_price: D,
        stop_price: D,
        take_profit_price: D,
        state: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(
                self._activate_shadow_sync,
                signal_id,
                entry_time_ms,
                entry_price,
                stop_price,
                take_profit_price,
                dict(state or {}),
            )

    def _activate_shadow_sync(
        self, signal_id: str, entry_time_ms: int, entry_price: D, stop_price: D,
        take_profit_price: D, state: Mapping[str, Any]
    ) -> dict[str, Any]:
        now = int(time.time() * 1000)
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                cursor = connection.execute(
                    """
                    UPDATE shadow_trades SET status='OPEN', entry_time_ms=?, entry_price=?,
                        stop_price=?, take_profit_price=?, state_json=?, updated_at_ms=?
                    WHERE signal_id=? AND status='PENDING'
                    """,
                    (entry_time_ms, str(entry_price), str(stop_price), str(take_profit_price),
                     json.dumps(encode_json(dict(state)), sort_keys=True), now, signal_id),
                )
                if cursor.rowcount != 1:
                    raise ValueError(f"Shadow signal is not pending: {signal_id}")
                row = connection.execute(
                    "SELECT * FROM shadow_trades WHERE signal_id=?", (signal_id,)
                ).fetchone()
                connection.commit()
                assert row is not None
                return dict(row)
            except Exception:
                connection.rollback()
                raise

    async def update_shadow_state(
        self,
        signal_id: str,
        *,
        stop_price: D,
        state: Mapping[str, Any],
    ) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(
                self._update_shadow_state_sync, signal_id, stop_price, dict(state)
            )

    def _update_shadow_state_sync(
        self, signal_id: str, stop_price: D, state: Mapping[str, Any]
    ) -> dict[str, Any]:
        now = int(time.time() * 1000)
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                cursor = connection.execute(
                    """
                    UPDATE shadow_trades SET stop_price=?, state_json=?, updated_at_ms=?
                    WHERE signal_id=? AND status='OPEN'
                    """,
                    (
                        str(stop_price),
                        json.dumps(encode_json(dict(state)), sort_keys=True),
                        now,
                        signal_id,
                    ),
                )
                if cursor.rowcount != 1:
                    raise ValueError(f"Shadow trade is not open: {signal_id}")
                row = connection.execute(
                    "SELECT * FROM shadow_trades WHERE signal_id=?", (signal_id,)
                ).fetchone()
                connection.commit()
                assert row is not None
                return dict(row)
            except Exception:
                connection.rollback()
                raise

    async def close_shadow(
        self,
        signal_id: str,
        *,
        exit_time_ms: int,
        exit_price: D,
        r_multiple: D,
        exit_reason: str,
    ) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(
                self._close_shadow_sync,
                signal_id,
                exit_time_ms,
                exit_price,
                r_multiple,
                exit_reason,
            )

    def _close_shadow_sync(
        self, signal_id: str, exit_time_ms: int, exit_price: D, r_multiple: D, exit_reason: str
    ) -> dict[str, Any]:
        now = int(time.time() * 1000)
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                cursor = connection.execute(
                    """
                    UPDATE shadow_trades SET status='CLOSED', exit_time_ms=?, exit_price=?,
                        r_multiple=?, exit_reason=?, updated_at_ms=?
                    WHERE signal_id=? AND status='OPEN'
                    """,
                    (exit_time_ms, str(exit_price), str(r_multiple), exit_reason, now, signal_id),
                )
                if cursor.rowcount != 1:
                    raise ValueError(f"Shadow trade is not open: {signal_id}")
                row = connection.execute(
                    "SELECT * FROM shadow_trades WHERE signal_id=?", (signal_id,)
                ).fetchone()
                connection.commit()
                assert row is not None
                return dict(row)
            except Exception:
                connection.rollback()
                raise

    async def shadow_summary(self, run_id: str) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(self._shadow_summary_sync, run_id)

    def _shadow_summary_sync(self, run_id: str) -> dict[str, Any]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                "SELECT status, r_multiple FROM shadow_trades WHERE run_id=?", (run_id,)
            ).fetchall()
        closed = [D(str(row["r_multiple"])) for row in rows if row["status"] == "CLOSED" and row["r_multiple"] is not None]
        return {
            "run_id": run_id,
            "signals": len(rows),
            "pending": sum(1 for row in rows if row["status"] == "PENDING"),
            "open": sum(1 for row in rows if row["status"] == "OPEN"),
            "closed": len(closed),
            "expectancy_r": str(sum(closed, D("0")) / D(str(len(closed)))) if closed else "0",
            "win_rate": str(D(str(sum(1 for value in closed if value > D("0")))) / D(str(len(closed)))) if closed else "0",
        }

    async def list_shadow(self, run_id: str | None = None, limit: int = 200) -> list[dict[str, Any]]:
        async with self._lock:
            return await asyncio.to_thread(self._list_shadow_sync, run_id, limit)

    def _list_shadow_sync(self, run_id: str | None, limit: int) -> list[dict[str, Any]]:
        query = "SELECT * FROM shadow_trades"
        params: list[Any] = []
        if run_id is not None:
            query += " WHERE run_id=?"
            params.append(run_id)
        query += " ORDER BY updated_at_ms DESC LIMIT ?"
        params.append(max(1, min(limit, 2000)))
        with closing(self._connect()) as connection:
            rows = connection.execute(query, tuple(params)).fetchall()
        return [dict(row) for row in rows]


def _bar_from_dict(value: Mapping[str, Any]) -> ResearchBar:
    def optional_decimal(key: str) -> D | None:
        return None if value.get(key) is None else D(str(value[key]))

    def optional_int(key: str) -> int | None:
        return None if value.get(key) is None else int(value[key])

    return ResearchBar(
        symbol=str(value["symbol"]),
        interval=str(value["interval"]),
        open_time_ms=int(value["open_time_ms"]),
        close_time_ms=int(value["close_time_ms"]),
        open=D(str(value["open"])),
        high=D(str(value["high"])),
        low=D(str(value["low"])),
        close=D(str(value["close"])),
        volume=D(str(value["volume"])),
        quote_volume=D(str(value.get("quote_volume", "0"))),
        trades=int(value.get("trades", 0)),
        taker_buy_base=D(str(value.get("taker_buy_base", "0"))),
        taker_buy_quote=D(str(value.get("taker_buy_quote", "0"))),
        mark_close=optional_decimal("mark_close"),
        open_interest=optional_decimal("open_interest"),
        funding_rate=optional_decimal("funding_rate"),
        open_interest_available_at_ms=optional_int("open_interest_available_at_ms"),
        funding_available_at_ms=optional_int("funding_available_at_ms"),
    )
