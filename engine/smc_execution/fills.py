from __future__ import annotations

from dataclasses import dataclass, field

from .models import D, ONE, OrderSnapshot, ZERO


@dataclass
class FillAccumulator:
    requested_qty: D
    executed_qty: D = ZERO
    cumulative_quote_qty: D = ZERO
    maker_qty: D = ZERO
    taker_qty: D = ZERO
    seen_orders: set[tuple[str, str]] = field(default_factory=set)

    def add_terminal_snapshot(self, order: OrderSnapshot, *, maker: bool) -> None:
        key = (order.kind.value, order.order_id)
        if key in self.seen_orders:
            return
        if not order.status.terminal:
            raise ValueError("only reconciled terminal snapshots may be accumulated")
        self.seen_orders.add(key)
        self.executed_qty += order.executed_qty
        quote_qty = order.cumulative_quote_qty
        if quote_qty <= ZERO and order.avg_price > ZERO and order.executed_qty > ZERO:
            quote_qty = order.avg_price * order.executed_qty
        self.cumulative_quote_qty += quote_qty
        if maker:
            self.maker_qty += order.executed_qty
        else:
            self.taker_qty += order.executed_qty

    @property
    def remaining_qty(self) -> D:
        return max(ZERO, self.requested_qty - self.executed_qty)

    @property
    def fill_ratio(self) -> D:
        if self.requested_qty <= ZERO:
            return ZERO
        return min(ONE, self.executed_qty / self.requested_qty)

    @property
    def avg_price(self) -> D | None:
        if self.executed_qty <= ZERO or self.cumulative_quote_qty <= ZERO:
            return None
        return self.cumulative_quote_qty / self.executed_qty
