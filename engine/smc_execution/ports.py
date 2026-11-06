from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any, Protocol

from .models import (
    AccountSnapshot,
    Action,
    ExecutionRecord,
    ExecutionStage,
    Kline,
    OrderKind,
    OrderRequest,
    OrderSnapshot,
    PositionSnapshot,
    Quote,
    RiskReservation,
    Signal,
    SymbolRules,
    D,
)


class ExchangeAPI(Protocol):
    async def start(self, symbols: Sequence[str]) -> None: ...
    async def close(self) -> None: ...
    async def is_user_stream_healthy(self) -> bool: ...

    async def get_symbol_rules(self, symbol: str) -> SymbolRules: ...
    async def get_quote(self, symbol: str) -> Quote: ...
    async def get_account_snapshot(self) -> AccountSnapshot: ...
    async def get_closed_klines(
        self, symbol: str, interval: str, limit: int
    ) -> Sequence[Kline]: ...
    async def get_position(self, symbol: str, action: Action) -> PositionSnapshot: ...
    async def list_positions(self) -> Sequence[PositionSnapshot]: ...

    async def place_order(self, request: OrderRequest) -> OrderSnapshot: ...
    async def cancel_order(
        self,
        symbol: str,
        order_id: str,
        client_order_id: str,
        kind: OrderKind,
    ) -> OrderSnapshot: ...
    async def get_order(
        self,
        symbol: str,
        order_id: str | None,
        client_order_id: str,
        kind: OrderKind,
    ) -> OrderSnapshot: ...
    async def wait_order_update(
        self,
        symbol: str,
        order_id: str,
        client_order_id: str,
        kind: OrderKind,
        timeout_ms: int,
    ) -> OrderSnapshot | None: ...
    async def list_open_orders(
        self, symbol: str | None, kind: OrderKind
    ) -> Sequence[OrderSnapshot]: ...


class ControlPlane(Protocol):
    async def claim(self, signal: Signal) -> tuple[ExecutionRecord, bool]: ...
    async def get_execution(self, execution_id: str) -> ExecutionRecord | None: ...
    async def transition(
        self,
        execution_id: str,
        expected: set[ExecutionStage] | None,
        new_stage: ExecutionStage,
        patch: Mapping[str, Any] | None = None,
        result: Mapping[str, Any] | None = None,
    ) -> ExecutionRecord: ...
    async def list_nonterminal(self) -> Sequence[ExecutionRecord]: ...
    async def list_executions(self, limit: int = 100) -> Sequence[ExecutionRecord]: ...

    async def reserve_risk(
        self,
        reservation_id: str,
        amount: D,
        *,
        max_total_risk: D,
        external_open_risk: D,
    ) -> RiskReservation | None: ...
    async def commit_risk(self, reservation_id: str, amount: D) -> RiskReservation: ...
    async def release_risk(self, reservation_id: str) -> None: ...
    async def risk_totals(self) -> Mapping[str, D]: ...

    async def halt_admission(self, reason: str) -> None: ...
    async def resume_admission(self) -> None: ...
    async def admission_status(self) -> tuple[bool, str | None]: ...
    async def set_system_value(self, key: str, value: Mapping[str, Any]) -> None: ...
    async def get_system_value(self, key: str) -> Mapping[str, Any] | None: ...
