from __future__ import annotations

import asyncio
import json
import sqlite3
import time
from collections.abc import Mapping, Sequence
from contextlib import closing
from pathlib import Path
from typing import Any

from .errors import StateConflict
from .models import (
    Action,
    D,
    ExecutionRecord,
    ExecutionStage,
    RiskReservation,
    Signal,
    ZERO,
    encode_json,
)


def _now_ms() -> int:
    return int(time.time() * 1000)


def _dumps(value: Mapping[str, Any] | None) -> str | None:
    if value is None:
        return None
    return json.dumps(encode_json(value), separators=(",", ":"), sort_keys=True)


def _loads(value: str | None) -> dict[str, Any] | None:
    return None if value is None else json.loads(value)


class SQLiteControlPlane:
    """
    Durable execution saga + atomic portfolio-risk ledger.

    SQLite is a safe single-node reference implementation. For several engine
    processes use PostgreSQL SERIALIZABLE transactions or the included Redis Lua
    design, but keep this exact atomicity boundary.
    """

    SCHEMA_VERSION = 1

    def __init__(self, path: str | Path) -> None:
        self.path = str(path)
        self._init_lock = asyncio.Lock()
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return
        async with self._init_lock:
            if self._initialized:
                return
            await asyncio.to_thread(self._initialize_sync)
            self._initialized = True

    def _connect(self) -> sqlite3.Connection:
        con = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        con.row_factory = sqlite3.Row
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("PRAGMA synchronous=FULL")
        con.execute("PRAGMA foreign_keys=ON")
        con.execute("PRAGMA busy_timeout=30000")
        return con

    def _initialize_sync(self) -> None:
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as con:
            version = int(con.execute("PRAGMA user_version").fetchone()[0])
            if version > self.SCHEMA_VERSION:
                raise RuntimeError(
                    f"Execution DB schema {version} is newer than supported "
                    f"{self.SCHEMA_VERSION}"
                )
            con.executescript(
                """
                CREATE TABLE IF NOT EXISTS executions (
                    execution_id TEXT PRIMARY KEY,
                    symbol TEXT NOT NULL,
                    action TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    result_json TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_executions_stage
                    ON executions(stage, updated_at_ms);

                CREATE TABLE IF NOT EXISTS risk_reservations (
                    reservation_id TEXT PRIMARY KEY,
                    amount TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('RESERVED','COMMITTED')),
                    updated_at_ms INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS system_flags (
                    key TEXT PRIMARY KEY,
                    value_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                );
                """
            )
            con.execute(f"PRAGMA user_version={self.SCHEMA_VERSION}")

    async def claim(self, signal: Signal) -> tuple[ExecutionRecord, bool]:
        await self.initialize()
        return await asyncio.to_thread(self._claim_sync, signal)

    def _claim_sync(self, signal: Signal) -> tuple[ExecutionRecord, bool]:
        now = _now_ms()
        payload = {
            "signal": {
                "signal_id": signal.signal_id,
                "symbol": signal.symbol,
                "action": signal.action.value,
                "confidence": str(signal.confidence),
                "created_at_ms": signal.created_at_ms,
                "reference_price": (
                    None if signal.reference_price is None else str(signal.reference_price)
                ),
                "strategy_id": signal.strategy_id,
                "strategy_version": signal.strategy_version,
                "research_run_id": signal.research_run_id,
                "code_fingerprint": signal.code_fingerprint,
                "metadata": encode_json(dict(signal.metadata)),
            }
        }
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            row = con.execute(
                "SELECT * FROM executions WHERE execution_id=?", (signal.signal_id,)
            ).fetchone()
            if row is not None:
                con.execute("COMMIT")
                return self._row_to_record(row), False
            con.execute(
                """
                INSERT INTO executions(
                    execution_id,symbol,action,stage,version,
                    created_at_ms,updated_at_ms,payload_json,result_json
                ) VALUES(?,?,?,?,?,?,?,?,NULL)
                """,
                (
                    signal.signal_id,
                    signal.symbol,
                    signal.action.value,
                    ExecutionStage.CLAIMED.value,
                    1,
                    now,
                    now,
                    _dumps(payload),
                ),
            )
            row = con.execute(
                "SELECT * FROM executions WHERE execution_id=?", (signal.signal_id,)
            ).fetchone()
            con.execute("COMMIT")
            assert row is not None
            return self._row_to_record(row), True

    async def get_execution(self, execution_id: str) -> ExecutionRecord | None:
        await self.initialize()
        return await asyncio.to_thread(self._get_execution_sync, execution_id)

    def _get_execution_sync(self, execution_id: str) -> ExecutionRecord | None:
        with closing(self._connect()) as con:
            row = con.execute(
                "SELECT * FROM executions WHERE execution_id=?", (execution_id,)
            ).fetchone()
            return None if row is None else self._row_to_record(row)

    async def transition(
        self,
        execution_id: str,
        expected: set[ExecutionStage] | None,
        new_stage: ExecutionStage,
        patch: Mapping[str, Any] | None = None,
        result: Mapping[str, Any] | None = None,
    ) -> ExecutionRecord:
        await self.initialize()
        return await asyncio.to_thread(
            self._transition_sync, execution_id, expected, new_stage, patch, result
        )

    def _transition_sync(
        self,
        execution_id: str,
        expected: set[ExecutionStage] | None,
        new_stage: ExecutionStage,
        patch: Mapping[str, Any] | None,
        result: Mapping[str, Any] | None,
    ) -> ExecutionRecord:
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            row = con.execute(
                "SELECT * FROM executions WHERE execution_id=?", (execution_id,)
            ).fetchone()
            if row is None:
                con.execute("ROLLBACK")
                raise StateConflict(f"execution {execution_id!r} does not exist")
            current = ExecutionStage(row["stage"])
            if expected is not None and current not in expected:
                con.execute("ROLLBACK")
                raise StateConflict(
                    f"execution {execution_id}: expected {sorted(x.value for x in expected)}, "
                    f"found {current.value}"
                )
            payload = _loads(row["payload_json"]) or {}
            if patch:
                payload.update(encode_json(dict(patch)))
            version = int(row["version"]) + 1
            now = _now_ms()
            result_json = row["result_json"] if result is None else _dumps(result)
            con.execute(
                """
                UPDATE executions
                SET stage=?,version=?,updated_at_ms=?,payload_json=?,result_json=?
                WHERE execution_id=?
                """,
                (
                    new_stage.value,
                    version,
                    now,
                    _dumps(payload),
                    result_json,
                    execution_id,
                ),
            )
            updated = con.execute(
                "SELECT * FROM executions WHERE execution_id=?", (execution_id,)
            ).fetchone()
            con.execute("COMMIT")
            assert updated is not None
            return self._row_to_record(updated)

    async def list_nonterminal(self) -> Sequence[ExecutionRecord]:
        await self.initialize()
        return await asyncio.to_thread(self._list_nonterminal_sync)

    def _list_nonterminal_sync(self) -> list[ExecutionRecord]:
        terminal = tuple(stage.value for stage in ExecutionStage if stage.terminal)
        placeholders = ",".join("?" for _ in terminal)
        with closing(self._connect()) as con:
            rows = con.execute(
                f"SELECT * FROM executions WHERE stage NOT IN ({placeholders}) "
                "ORDER BY updated_at_ms",
                terminal,
            ).fetchall()
            return [self._row_to_record(row) for row in rows]

    async def list_executions(self, limit: int = 100) -> Sequence[ExecutionRecord]:
        await self.initialize()
        bounded = max(1, min(int(limit), 1000))
        return await asyncio.to_thread(self._list_executions_sync, bounded)

    def _list_executions_sync(self, limit: int) -> list[ExecutionRecord]:
        with closing(self._connect()) as con:
            rows = con.execute(
                "SELECT * FROM executions ORDER BY updated_at_ms DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [self._row_to_record(row) for row in rows]

    async def reserve_risk(
        self,
        reservation_id: str,
        amount: D,
        *,
        max_total_risk: D,
        external_open_risk: D,
    ) -> RiskReservation | None:
        await self.initialize()
        return await asyncio.to_thread(
            self._reserve_risk_sync,
            reservation_id,
            amount,
            max_total_risk,
            external_open_risk,
        )

    def _reserve_risk_sync(
        self,
        reservation_id: str,
        amount: D,
        max_total_risk: D,
        external_open_risk: D,
    ) -> RiskReservation | None:
        if amount <= ZERO:
            return None
        now = _now_ms()
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            existing = con.execute(
                "SELECT * FROM risk_reservations WHERE reservation_id=?",
                (reservation_id,),
            ).fetchone()
            if existing is not None:
                con.execute("COMMIT")
                return self._row_to_reservation(existing)

            rows = con.execute(
                "SELECT amount,state FROM risk_reservations"
            ).fetchall()
            reserved = sum(
                (D(row["amount"]) for row in rows if row["state"] == "RESERVED"),
                ZERO,
            )
            committed = sum(
                (D(row["amount"]) for row in rows if row["state"] == "COMMITTED"),
                ZERO,
            )
            effective = max(external_open_risk, committed) + reserved
            if effective + amount > max_total_risk:
                con.execute("ROLLBACK")
                return None
            con.execute(
                "INSERT INTO risk_reservations VALUES(?,?,?,?)",
                (reservation_id, str(amount), "RESERVED", now),
            )
            con.execute("COMMIT")
            return RiskReservation(reservation_id, amount, "RESERVED", now)

    async def commit_risk(self, reservation_id: str, amount: D) -> RiskReservation:
        await self.initialize()
        return await asyncio.to_thread(self._commit_risk_sync, reservation_id, amount)

    def _commit_risk_sync(self, reservation_id: str, amount: D) -> RiskReservation:
        now = _now_ms()
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            row = con.execute(
                "SELECT * FROM risk_reservations WHERE reservation_id=?",
                (reservation_id,),
            ).fetchone()
            if row is None:
                con.execute("ROLLBACK")
                raise StateConflict(f"risk reservation {reservation_id!r} does not exist")
            con.execute(
                "UPDATE risk_reservations SET amount=?,state='COMMITTED',updated_at_ms=? "
                "WHERE reservation_id=?",
                (str(max(ZERO, amount)), now, reservation_id),
            )
            con.execute("COMMIT")
            return RiskReservation(reservation_id, max(ZERO, amount), "COMMITTED", now)

    async def release_risk(self, reservation_id: str) -> None:
        await self.initialize()
        await asyncio.to_thread(self._release_risk_sync, reservation_id)

    def _release_risk_sync(self, reservation_id: str) -> None:
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            con.execute(
                "DELETE FROM risk_reservations WHERE reservation_id=?",
                (reservation_id,),
            )
            con.execute("COMMIT")

    async def risk_totals(self) -> Mapping[str, D]:
        await self.initialize()
        return await asyncio.to_thread(self._risk_totals_sync)

    def _risk_totals_sync(self) -> Mapping[str, D]:
        with closing(self._connect()) as con:
            rows = con.execute("SELECT amount,state FROM risk_reservations").fetchall()
        return {
            "reserved": sum(
                (D(row["amount"]) for row in rows if row["state"] == "RESERVED"),
                ZERO,
            ),
            "committed": sum(
                (D(row["amount"]) for row in rows if row["state"] == "COMMITTED"),
                ZERO,
            ),
        }

    async def set_system_value(self, key: str, value: Mapping[str, Any]) -> None:
        await self.initialize()
        await asyncio.to_thread(self._set_flag_sync, key, value)

    async def get_system_value(self, key: str) -> Mapping[str, Any] | None:
        await self.initialize()
        return await asyncio.to_thread(self._get_flag_sync, key)

    async def halt_admission(self, reason: str) -> None:
        await self.initialize()
        await asyncio.to_thread(self._set_flag_sync, "admission", {"open": False, "reason": reason})

    async def resume_admission(self) -> None:
        await self.initialize()
        await asyncio.to_thread(self._set_flag_sync, "admission", {"open": True, "reason": None})

    async def admission_status(self) -> tuple[bool, str | None]:
        await self.initialize()
        value = await asyncio.to_thread(self._get_flag_sync, "admission")
        if value is None:
            return True, None
        return bool(value.get("open", False)), value.get("reason")

    def _set_flag_sync(self, key: str, value: Mapping[str, Any]) -> None:
        now = _now_ms()
        with closing(self._connect()) as con:
            con.execute("BEGIN IMMEDIATE")
            con.execute(
                """
                INSERT INTO system_flags(key,value_json,updated_at_ms) VALUES(?,?,?)
                ON CONFLICT(key) DO UPDATE SET
                    value_json=excluded.value_json,
                    updated_at_ms=excluded.updated_at_ms
                """,
                (key, _dumps(value), now),
            )
            con.execute("COMMIT")

    def _get_flag_sync(self, key: str) -> dict[str, Any] | None:
        with closing(self._connect()) as con:
            row = con.execute(
                "SELECT value_json FROM system_flags WHERE key=?", (key,)
            ).fetchone()
        return None if row is None else _loads(row["value_json"])

    @staticmethod
    def _row_to_record(row: sqlite3.Row) -> ExecutionRecord:
        return ExecutionRecord(
            execution_id=row["execution_id"],
            symbol=row["symbol"],
            action=Action(row["action"]),
            stage=ExecutionStage(row["stage"]),
            version=int(row["version"]),
            created_at_ms=int(row["created_at_ms"]),
            updated_at_ms=int(row["updated_at_ms"]),
            payload=_loads(row["payload_json"]) or {},
            result=_loads(row["result_json"]),
        )

    @staticmethod
    def _row_to_reservation(row: sqlite3.Row) -> RiskReservation:
        return RiskReservation(
            reservation_id=row["reservation_id"],
            amount=D(row["amount"]),
            state=row["state"],
            updated_at_ms=int(row["updated_at_ms"]),
        )
