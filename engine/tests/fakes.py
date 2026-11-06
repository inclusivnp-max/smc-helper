from __future__ import annotations

import asyncio
import time
from collections.abc import Sequence
from dataclasses import replace
from decimal import Decimal as D

from smc_execution.models import (
    ZERO,
    AccountSnapshot,
    Action,
    Kline,
    OrderKind,
    OrderRequest,
    OrderSnapshot,
    OrderStatus,
    PositionMode,
    PositionSnapshot,
    Quote,
    Side,
    SymbolRules,
)


class FakeExchange:
    def __init__(self) -> None:
        self.healthy = True
        self.rules = SymbolRules(
            symbol="BTCUSDT",
            tick_size=D("0.1"),
            step_size=D("0.001"),
            min_qty=D("0.001"),
            max_qty=D("1000"),
            min_notional=D("5"),
            max_notional=D("100000"),
        )
        self.quote = self.fresh_quote()
        self.account = AccountSnapshot(
            equity=D("10000"),
            available_balance=D("10000"),
            max_new_position_notional=D("100000"),
            position_mode=PositionMode.ONEWAY,
        )
        self.position = PositionSnapshot(
            symbol="BTCUSDT",
            position_mode=PositionMode.ONEWAY,
            position_side="BOTH",
            quantity=ZERO,
            entry_price=ZERO,
            mark_price=D("1000.1"),
            update_time_ms=int(time.time() * 1000),
        )
        self.orders: dict[tuple[OrderKind, str], OrderSnapshot] = {}
        self.requests: list[OrderRequest] = []
        self.cancelled: list[str] = []
        self.maker_fill_qty = ZERO
        self.reject_stop = False
        self.reject_tp = False
        self._counter = 0

    @staticmethod
    def fresh_quote() -> Quote:
        return Quote(
            symbol="BTCUSDT",
            bid=D("1000.0"),
            ask=D("1000.1"),
            bid_qty=D("20"),
            ask_qty=D("10"),
            exchange_time_ms=int(time.time() * 1000),
            sequence_id=1,
        )

    async def start(self, symbols: Sequence[str]) -> None:
        return None

    async def close(self) -> None:
        return None

    async def is_user_stream_healthy(self) -> bool:
        return self.healthy

    async def get_symbol_rules(self, symbol: str) -> SymbolRules:
        return self.rules

    async def get_quote(self, symbol: str) -> Quote:
        return self.quote

    async def get_account_snapshot(self) -> AccountSnapshot:
        return self.account

    async def get_closed_klines(self, symbol: str, interval: str, limit: int):
        now = int(time.time() * 1000)
        rows = []
        close = D("1000")
        for i in range(limit):
            rows.append(
                Kline(
                    open_time_ms=now - (limit - i) * 300_000,
                    close_time_ms=now - (limit - i - 1) * 300_000 - 1,
                    high=close + D("0.5"),
                    low=close - D("0.5"),
                    close=close,
                    is_closed=True,
                )
            )
        return rows

    async def get_position(self, symbol: str, action: Action) -> PositionSnapshot:
        return self.position

    async def list_positions(self):
        return [self.position]

    async def place_order(self, request: OrderRequest) -> OrderSnapshot:
        self.requests.append(request)
        self._counter += 1
        order_id = str(self._counter)
        status = OrderStatus.NEW
        executed = ZERO
        avg = ZERO
        cumulative = ZERO

        if request.client_order_id.endswith("MKR"):
            status = OrderStatus.NEW
        elif request.client_order_id.endswith("IOC"):
            status = OrderStatus.FILLED
            executed = request.quantity or ZERO
            avg = request.price or self.quote.ask
            cumulative = executed * avg
            self._apply_entry(request, executed, avg)
        elif request.client_order_id.endswith("SL") or "SLR" in request.client_order_id:
            status = OrderStatus.REJECTED if self.reject_stop else OrderStatus.NEW
        elif request.client_order_id.endswith("TP") or "TPR" in request.client_order_id:
            status = OrderStatus.REJECTED if self.reject_tp else OrderStatus.NEW
        elif request.client_order_id.endswith("EIOC"):
            status = OrderStatus.FILLED
            executed = request.quantity or ZERO
            avg = request.price or self.quote.bid
            cumulative = executed * avg
            self._apply_exit(executed)
        elif request.client_order_id.endswith("EMKT"):
            status = OrderStatus.FILLED
            executed = request.quantity or ZERO
            avg = self.quote.bid
            cumulative = executed * avg
            self._apply_exit(executed)
        elif request.order_type == "MARKET" and request.client_order_id.startswith("op-"):
            status = OrderStatus.FILLED
            executed = request.quantity or ZERO
            avg = self.quote.bid if request.side is Side.SELL else self.quote.ask
            cumulative = executed * avg
            self._apply_exit(executed)

        snapshot = OrderSnapshot(
            order_id=order_id,
            client_order_id=request.client_order_id,
            symbol=request.symbol,
            side=request.side,
            status=status,
            orig_qty=request.quantity or ZERO,
            executed_qty=executed,
            cumulative_quote_qty=cumulative,
            avg_price=avg,
            update_time_ms=int(time.time() * 1000),
            kind=request.kind,
            order_type=request.order_type,
            price=request.price or ZERO,
            stop_price=request.stop_price or ZERO,
        )
        self.orders[(request.kind, request.client_order_id)] = snapshot
        return snapshot

    async def cancel_order(
        self,
        symbol: str,
        order_id: str,
        client_order_id: str,
        kind: OrderKind,
    ) -> OrderSnapshot:
        self.cancelled.append(client_order_id)
        current = self.orders[(kind, client_order_id)]
        updated = replace(
            current,
            status=OrderStatus.CANCELED,
            update_time_ms=int(time.time() * 1000),
        )
        self.orders[(kind, client_order_id)] = updated
        return updated

    async def get_order(
        self,
        symbol: str,
        order_id: str | None,
        client_order_id: str,
        kind: OrderKind,
    ) -> OrderSnapshot:
        return self.orders[(kind, client_order_id)]

    async def wait_order_update(
        self,
        symbol: str,
        order_id: str,
        client_order_id: str,
        kind: OrderKind,
        timeout_ms: int,
    ) -> OrderSnapshot | None:
        current = self.orders[(kind, client_order_id)]
        if client_order_id.endswith("MKR") and current.status is OrderStatus.NEW:
            executed = min(self.maker_fill_qty, current.orig_qty)
            avg = current.price if executed > ZERO else ZERO
            terminal = replace(
                current,
                status=OrderStatus.CANCELED,
                executed_qty=executed,
                cumulative_quote_qty=executed * avg,
                avg_price=avg,
                update_time_ms=int(time.time() * 1000),
            )
            self.orders[(kind, client_order_id)] = terminal
            if executed > ZERO:
                self._apply_entry_request_snapshot(terminal)
            return terminal
        await asyncio.sleep(0)
        return current if current.status.terminal else None

    async def list_open_orders(self, symbol: str | None, kind: OrderKind):
        return [
            order
            for (order_kind, _), order in self.orders.items()
            if order_kind is kind
            and order.status.active
            and (symbol is None or order.symbol == symbol)
        ]

    def _apply_entry(self, request: OrderRequest, qty: D, price: D) -> None:
        signed = qty if request.side is Side.BUY else -qty
        self.position = replace(
            self.position,
            quantity=self.position.quantity + signed,
            entry_price=price,
            update_time_ms=int(time.time() * 1000),
        )

    def _apply_entry_request_snapshot(self, order: OrderSnapshot) -> None:
        signed = order.executed_qty if order.side is Side.BUY else -order.executed_qty
        self.position = replace(
            self.position,
            quantity=self.position.quantity + signed,
            entry_price=order.avg_price,
            update_time_ms=int(time.time() * 1000),
        )

    def _apply_exit(self, qty: D) -> None:
        current = self.position.quantity
        if current > ZERO:
            new_qty = max(ZERO, current - qty)
        elif current < ZERO:
            new_qty = min(ZERO, current + qty)
        else:
            new_qty = ZERO
        self.position = replace(
            self.position,
            quantity=new_qty,
            entry_price=ZERO if new_qty == ZERO else self.position.entry_price,
            update_time_ms=int(time.time() * 1000),
        )
