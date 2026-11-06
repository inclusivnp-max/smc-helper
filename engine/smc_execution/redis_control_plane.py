from __future__ import annotations

import json
import time
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any
from decimal import ROUND_CEILING, ROUND_FLOOR

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


class RedisControlPlane:
    """Horizontally scalable control plane using Redis Lua atomic sections."""

    def __init__(self, redis_url: str, *, prefix: str = "smc") -> None:
        try:
            import redis.asyncio as redis
        except ImportError as exc:
            raise RuntimeError(
                "RedisControlPlane requires the optional dependency: pip install redis"
            ) from exc
        self.redis = redis.from_url(redis_url, decode_responses=True)
        self.prefix = prefix.rstrip(":")
        root = Path(__file__).resolve().parent / "redis_lua"
        self._claim_script = (root / "claim_execution.lua").read_text()
        self._reserve_script = (root / "reserve_risk.lua").read_text()
        self._commit_script = (root / "commit_risk.lua").read_text()
        self._transition_script = (root / "transition_execution.lua").read_text()
        self._risk_scale = D("1000000")

    def _execution_key(self, execution_id: str) -> str:
        return f"{self.prefix}:execution:{execution_id}"

    @property
    def _nonterminal_key(self) -> str:
        return f"{self.prefix}:executions:nonterminal"

    @property
    def _risk_key(self) -> str:
        return f"{self.prefix}:risk"

    async def claim(self, signal: Signal) -> tuple[ExecutionRecord, bool]:
        key = self._execution_key(signal.signal_id)
        now = int(time.time() * 1000)
        payload = {
            "signal": {
                "signal_id": signal.signal_id,
                "symbol": signal.symbol,
                "action": signal.action.value,
                "confidence": str(signal.confidence),
                "created_at_ms": signal.created_at_ms,
                "reference_price": None
                if signal.reference_price is None
                else str(signal.reference_price),
                "strategy_id": signal.strategy_id,
                "strategy_version": signal.strategy_version,
                "research_run_id": signal.research_run_id,
                "code_fingerprint": signal.code_fingerprint,
                "metadata": encode_json(dict(signal.metadata)),
            }
        }
        created = await self.redis.eval(
            self._claim_script,
            2,
            key,
            self._nonterminal_key,
            signal.signal_id,
            signal.symbol,
            signal.action.value,
            ExecutionStage.CLAIMED.value,
            "1",
            str(now),
            str(now),
            json.dumps(encode_json(payload), separators=(",", ":")),
        )
        record = await self.get_execution(signal.signal_id)
        if record is None:
            raise StateConflict(f"Could not read execution {signal.signal_id}")
        return record, bool(created)

    async def get_execution(self, execution_id: str) -> ExecutionRecord | None:
        raw = await self.redis.hgetall(self._execution_key(execution_id))
        return None if not raw else self._decode_record(raw)

    async def transition(
        self,
        execution_id: str,
        expected: set[ExecutionStage] | None,
        new_stage: ExecutionStage,
        patch: Mapping[str, Any] | None = None,
        result: Mapping[str, Any] | None = None,
    ) -> ExecutionRecord:
        expected_csv = "*" if expected is None else ",".join(x.value for x in expected)
        now = int(time.time() * 1000)
        try:
            response = await self.redis.eval(
                self._transition_script,
                2,
                self._execution_key(execution_id),
                self._nonterminal_key,
                execution_id,
                expected_csv,
                new_stage.value,
                json.dumps(encode_json(dict(patch or {})), separators=(",", ":")),
                ""
                if result is None
                else json.dumps(encode_json(dict(result)), separators=(",", ":")),
                "1" if new_stage.terminal else "0",
                str(now),
            )
        except Exception as exc:
            if "STATE_CONFLICT" in str(exc) or "EXECUTION_NOT_FOUND" in str(exc):
                raise StateConflict(str(exc)) from exc
            raise
        raw = dict(zip(response[0::2], response[1::2], strict=True))
        return self._decode_record(raw)

    async def list_nonterminal(self) -> Sequence[ExecutionRecord]:
        ids = sorted(await self.redis.smembers(self._nonterminal_key))
        if not ids:
            return ()
        pipe = self.redis.pipeline()
        for execution_id in ids:
            pipe.hgetall(self._execution_key(execution_id))
        rows = await pipe.execute()
        return tuple(self._decode_record(row) for row in rows if row)

    async def list_executions(self, limit: int = 100) -> Sequence[ExecutionRecord]:
        bounded = max(1, min(int(limit), 1000))
        keys: list[str] = []
        async for key in self.redis.scan_iter(match=f"{self.prefix}:execution:*", count=200):
            keys.append(str(key))
            if len(keys) >= 5000:
                break
        if not keys:
            return ()
        pipe = self.redis.pipeline()
        for key in keys:
            pipe.hgetall(key)
        rows = await pipe.execute()
        records = [self._decode_record(row) for row in rows if row]
        records.sort(key=lambda item: item.updated_at_ms, reverse=True)
        return tuple(records[:bounded])

    async def reserve_risk(
        self,
        reservation_id: str,
        amount: D,
        *,
        max_total_risk: D,
        external_open_risk: D,
    ) -> RiskReservation | None:
        amount_units = self._to_units_up(amount)
        max_units = self._to_units_down(max_total_risk)
        external_units = self._to_units_up(external_open_risk)
        raw = await self.redis.eval(
            self._reserve_script,
            1,
            self._risk_key,
            reservation_id,
            str(amount_units),
            str(max_units),
            str(external_units),
        )
        if raw is None:
            return None
        item = json.loads(raw)
        stored_amount = D(str(item["amount_units"])) / self._risk_scale
        return RiskReservation(
            reservation_id,
            stored_amount,
            item["state"],
            int(item.get("updated_at_ms", int(time.time() * 1000))),
        )

    async def commit_risk(self, reservation_id: str, amount: D) -> RiskReservation:
        units = self._to_units_up(max(ZERO, amount))
        try:
            raw = await self.redis.eval(
                self._commit_script,
                1,
                self._risk_key,
                reservation_id,
                str(units),
            )
        except Exception as exc:
            if "RISK_RESERVATION_NOT_FOUND" in str(exc):
                raise StateConflict(
                    f"Missing risk reservation {reservation_id}"
                ) from exc
            raise
        item = json.loads(raw)
        return RiskReservation(
            reservation_id,
            D(str(item["amount_units"])) / self._risk_scale,
            item["state"],
            int(item["updated_at_ms"]),
        )

    async def release_risk(self, reservation_id: str) -> None:
        await self.redis.hdel(self._risk_key, reservation_id)

    async def risk_totals(self) -> Mapping[str, D]:
        values = await self.redis.hvals(self._risk_key)
        reserved = committed = ZERO
        for raw in values:
            item = json.loads(raw)
            amount = D(str(item["amount_units"])) / self._risk_scale
            if item["state"] == "RESERVED":
                reserved += amount
            elif item["state"] == "COMMITTED":
                committed += amount
        return {"reserved": reserved, "committed": committed}

    async def halt_admission(self, reason: str) -> None:
        await self.set_system_value("admission", {"open": False, "reason": reason})

    async def resume_admission(self) -> None:
        await self.set_system_value("admission", {"open": True, "reason": None})

    async def admission_status(self) -> tuple[bool, str | None]:
        value = await self.get_system_value("admission")
        if value is None:
            return True, None
        return bool(value.get("open", False)), value.get("reason")

    async def set_system_value(self, key: str, value: Mapping[str, Any]) -> None:
        await self.redis.set(
            f"{self.prefix}:system:{key}",
            json.dumps(encode_json(dict(value)), separators=(",", ":")),
        )

    async def get_system_value(self, key: str) -> Mapping[str, Any] | None:
        raw = await self.redis.get(f"{self.prefix}:system:{key}")
        return None if raw is None else json.loads(raw)

    def _to_units_up(self, amount: D) -> int:
        return int((max(ZERO, amount) * self._risk_scale).to_integral_value(rounding=ROUND_CEILING))

    def _to_units_down(self, amount: D) -> int:
        return int((max(ZERO, amount) * self._risk_scale).to_integral_value(rounding=ROUND_FLOOR))

    @staticmethod
    def _decode_record(raw: Mapping[str, str]) -> ExecutionRecord:
        result_raw = raw.get("result_json") or ""
        return ExecutionRecord(
            execution_id=raw["execution_id"],
            symbol=raw["symbol"],
            action=Action(raw["action"]),
            stage=ExecutionStage(raw["stage"]),
            version=int(raw["version"]),
            created_at_ms=int(raw["created_at_ms"]),
            updated_at_ms=int(raw["updated_at_ms"]),
            payload=json.loads(raw.get("payload_json") or "{}"),
            result=None if not result_raw else json.loads(result_raw),
        )
