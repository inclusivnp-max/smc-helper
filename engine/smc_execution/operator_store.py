from __future__ import annotations

import asyncio
import json
import sqlite3
import time
from collections.abc import Mapping, Sequence
from contextlib import closing
from pathlib import Path
from typing import Any

from .models import encode_json


def _now_ms() -> int:
    return int(time.time() * 1000)


class OperatorStore:
    """Durable audit trail and lightweight dashboard telemetry."""

    SCHEMA_VERSION = 1

    def __init__(self, path: str | Path) -> None:
        self.path = str(path)
        self._initialized = False
        self._lock = asyncio.Lock()

    def _connect(self) -> sqlite3.Connection:
        con = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        con.row_factory = sqlite3.Row
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("PRAGMA synchronous=FULL")
        con.execute("PRAGMA busy_timeout=30000")
        return con

    async def initialize(self) -> None:
        if self._initialized:
            return
        async with self._lock:
            if self._initialized:
                return
            await asyncio.to_thread(self._initialize_sync)
            self._initialized = True

    def _initialize_sync(self) -> None:
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as con:
            version = int(con.execute("PRAGMA user_version").fetchone()[0])
            if version > self.SCHEMA_VERSION:
                raise RuntimeError(
                    f"Operator DB schema {version} is newer than supported "
                    f"{self.SCHEMA_VERSION}"
                )
            con.executescript(
                """
                CREATE TABLE IF NOT EXISTS operator_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts_ms INTEGER NOT NULL,
                    actor TEXT NOT NULL,
                    action TEXT NOT NULL,
                    outcome TEXT NOT NULL,
                    idempotency_key TEXT,
                    details_json TEXT NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS uq_operator_idempotency
                    ON operator_audit(idempotency_key)
                    WHERE idempotency_key IS NOT NULL;
                CREATE INDEX IF NOT EXISTS idx_operator_audit_ts
                    ON operator_audit(ts_ms DESC);

                CREATE TABLE IF NOT EXISTS telemetry (
                    ts_ms INTEGER PRIMARY KEY,
                    equity TEXT NOT NULL,
                    available_balance TEXT NOT NULL,
                    open_risk TEXT NOT NULL,
                    reserved_risk TEXT NOT NULL,
                    committed_risk TEXT NOT NULL,
                    unrealized_pnl TEXT NOT NULL,
                    admission_open INTEGER NOT NULL,
                    mode TEXT NOT NULL,
                    user_stream_healthy INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_telemetry_ts
                    ON telemetry(ts_ms DESC);
                """
            )
            con.execute(f"PRAGMA user_version={self.SCHEMA_VERSION}")

    async def claim_command(
        self,
        *,
        actor: str,
        action: str,
        idempotency_key: str,
    ) -> tuple[dict[str, Any], bool]:
        await self.initialize()
        return await asyncio.to_thread(
            self._claim_command_sync, actor, action, idempotency_key
        )

    def _claim_command_sync(
        self, actor: str, action: str, idempotency_key: str
    ) -> tuple[dict[str, Any], bool]:
        ts_ms = _now_ms()
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            row = con.execute(
                "SELECT * FROM operator_audit WHERE idempotency_key=?",
                (idempotency_key,),
            ).fetchone()
            if row is not None:
                con.execute("COMMIT")
                return self._audit_row(row), False
            cur = con.execute(
                """
                INSERT INTO operator_audit(
                    ts_ms,actor,action,outcome,idempotency_key,details_json
                ) VALUES(?,?,?,?,?,?)
                """,
                (ts_ms, actor, action, "IN_PROGRESS", idempotency_key, "{}"),
            )
            row = con.execute(
                "SELECT * FROM operator_audit WHERE id=?", (cur.lastrowid,)
            ).fetchone()
            con.execute("COMMIT")
            assert row is not None
            return self._audit_row(row), True

    async def complete_command(
        self,
        *,
        idempotency_key: str,
        outcome: str,
        details: Mapping[str, Any],
    ) -> dict[str, Any]:
        await self.initialize()
        return await asyncio.to_thread(
            self._complete_command_sync,
            idempotency_key,
            outcome,
            dict(details),
        )

    def _complete_command_sync(
        self, idempotency_key: str, outcome: str, details: dict[str, Any]
    ) -> dict[str, Any]:
        payload = json.dumps(encode_json(details), separators=(",", ":"), sort_keys=True)
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            row = con.execute(
                "SELECT * FROM operator_audit WHERE idempotency_key=?",
                (idempotency_key,),
            ).fetchone()
            if row is None:
                con.execute("ROLLBACK")
                raise RuntimeError("Command idempotency record does not exist")
            con.execute(
                "UPDATE operator_audit SET outcome=?,details_json=? WHERE idempotency_key=?",
                (outcome, payload, idempotency_key),
            )
            updated = con.execute(
                "SELECT * FROM operator_audit WHERE idempotency_key=?",
                (idempotency_key,),
            ).fetchone()
            con.execute("COMMIT")
            assert updated is not None
            return self._audit_row(updated)

    async def append_audit(
        self,
        *,
        actor: str,
        action: str,
        outcome: str,
        details: Mapping[str, Any],
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        await self.initialize()
        return await asyncio.to_thread(
            self._append_audit_sync,
            actor,
            action,
            outcome,
            dict(details),
            idempotency_key,
        )

    def _append_audit_sync(
        self,
        actor: str,
        action: str,
        outcome: str,
        details: dict[str, Any],
        idempotency_key: str | None,
    ) -> dict[str, Any]:
        payload = json.dumps(encode_json(details), separators=(",", ":"), sort_keys=True)
        ts_ms = _now_ms()
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            if idempotency_key:
                row = con.execute(
                    "SELECT * FROM operator_audit WHERE idempotency_key=?",
                    (idempotency_key,),
                ).fetchone()
                if row is not None:
                    con.execute("COMMIT")
                    return self._audit_row(row)
            cur = con.execute(
                """
                INSERT INTO operator_audit(
                    ts_ms,actor,action,outcome,idempotency_key,details_json
                ) VALUES(?,?,?,?,?,?)
                """,
                (ts_ms, actor, action, outcome, idempotency_key, payload),
            )
            row = con.execute(
                "SELECT * FROM operator_audit WHERE id=?", (cur.lastrowid,)
            ).fetchone()
            con.execute("COMMIT")
            assert row is not None
            return self._audit_row(row)

    async def find_idempotent(self, idempotency_key: str) -> dict[str, Any] | None:
        await self.initialize()
        return await asyncio.to_thread(self._find_idempotent_sync, idempotency_key)

    def _find_idempotent_sync(self, idempotency_key: str) -> dict[str, Any] | None:
        with closing(self._connect()) as con:
            row = con.execute(
                "SELECT * FROM operator_audit WHERE idempotency_key=?",
                (idempotency_key,),
            ).fetchone()
        return None if row is None else self._audit_row(row)

    async def list_audit(self, limit: int = 100) -> Sequence[dict[str, Any]]:
        await self.initialize()
        bounded = max(1, min(int(limit), 1000))
        return await asyncio.to_thread(self._list_audit_sync, bounded)

    def _list_audit_sync(self, limit: int) -> list[dict[str, Any]]:
        with closing(self._connect()) as con:
            rows = con.execute(
                "SELECT * FROM operator_audit ORDER BY ts_ms DESC LIMIT ?", (limit,)
            ).fetchall()
        return [self._audit_row(row) for row in rows]

    async def record_telemetry(self, values: Mapping[str, Any]) -> None:
        await self.initialize()
        await asyncio.to_thread(self._record_telemetry_sync, dict(values))

    def _record_telemetry_sync(self, values: dict[str, Any]) -> None:
        ts_ms = int(values.get("ts_ms") or _now_ms())
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            con.execute(
                """
                INSERT OR REPLACE INTO telemetry(
                    ts_ms,equity,available_balance,open_risk,reserved_risk,
                    committed_risk,unrealized_pnl,admission_open,mode,
                    user_stream_healthy
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    ts_ms,
                    str(values.get("equity", "0")),
                    str(values.get("available_balance", "0")),
                    str(values.get("open_risk", "0")),
                    str(values.get("reserved_risk", "0")),
                    str(values.get("committed_risk", "0")),
                    str(values.get("unrealized_pnl", "0")),
                    1 if values.get("admission_open") else 0,
                    str(values.get("mode", "UNKNOWN")),
                    1 if values.get("user_stream_healthy") else 0,
                ),
            )
            con.execute("COMMIT")

    async def telemetry_history(
        self, *, limit: int = 720, since_ms: int | None = None
    ) -> Sequence[dict[str, Any]]:
        await self.initialize()
        bounded = max(1, min(int(limit), 10000))
        return await asyncio.to_thread(self._telemetry_history_sync, bounded, since_ms)

    def _telemetry_history_sync(
        self, limit: int, since_ms: int | None
    ) -> list[dict[str, Any]]:
        with closing(self._connect()) as con:
            if since_ms is None:
                rows = con.execute(
                    "SELECT * FROM telemetry ORDER BY ts_ms DESC LIMIT ?", (limit,)
                ).fetchall()
            else:
                rows = con.execute(
                    "SELECT * FROM telemetry WHERE ts_ms>=? ORDER BY ts_ms DESC LIMIT ?",
                    (since_ms, limit),
                ).fetchall()
        result = [self._telemetry_row(row) for row in rows]
        result.reverse()
        return result

    async def prune_telemetry(self, before_ms: int) -> int:
        await self.initialize()
        return await asyncio.to_thread(self._prune_sync, before_ms)

    def _prune_sync(self, before_ms: int) -> int:
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            cur = con.execute("DELETE FROM telemetry WHERE ts_ms<?", (before_ms,))
            con.execute("COMMIT")
            return int(cur.rowcount)

    @staticmethod
    def _audit_row(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": int(row["id"]),
            "ts_ms": int(row["ts_ms"]),
            "actor": row["actor"],
            "action": row["action"],
            "outcome": row["outcome"],
            "idempotency_key": row["idempotency_key"],
            "details": json.loads(row["details_json"]),
        }

    @staticmethod
    def _telemetry_row(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "ts_ms": int(row["ts_ms"]),
            "equity": str(row["equity"]),
            "available_balance": str(row["available_balance"]),
            "open_risk": str(row["open_risk"]),
            "reserved_risk": str(row["reserved_risk"]),
            "committed_risk": str(row["committed_risk"]),
            "unrealized_pnl": str(row["unrealized_pnl"]),
            "admission_open": bool(row["admission_open"]),
            "mode": row["mode"],
            "user_stream_healthy": bool(row["user_stream_healthy"]),
        }
