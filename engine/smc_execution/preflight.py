from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Sequence

from .models import OrderKind
from .ports import ExchangeAPI


@dataclass(frozen=True)
class PreflightReport:
    symbols_checked: tuple[str, ...]
    equity: Decimal
    available_balance: Decimal
    position_mode: str
    current_open_risk: Decimal
    regular_open_orders: int
    algo_open_orders: int
    user_stream_healthy: bool
    warnings: tuple[str, ...]


async def run_preflight(
    api: ExchangeAPI, symbols: Sequence[str]
) -> PreflightReport:
    warnings: list[str] = []
    for symbol in symbols:
        rules = await api.get_symbol_rules(symbol)
        quote = await api.get_quote(symbol)
        if rules.status != "TRADING":
            warnings.append(f"{symbol}: status={rules.status}")
        if quote.ask <= quote.bid:
            warnings.append(f"{symbol}: crossed/invalid quote")
    account = await api.get_account_snapshot()
    regular = await api.list_open_orders(None, OrderKind.REGULAR)
    algos = await api.list_open_orders(None, OrderKind.ALGO)
    healthy = await api.is_user_stream_healthy()
    if account.risk_halt:
        warnings.append(account.risk_halt_reason or "account risk_halt=true")
    if not healthy:
        warnings.append("private user stream is unhealthy")
    return PreflightReport(
        symbols_checked=tuple(symbols),
        equity=account.equity,
        available_balance=account.available_balance,
        position_mode=account.position_mode.value,
        current_open_risk=account.current_open_risk,
        regular_open_orders=len(regular),
        algo_open_orders=len(algos),
        user_stream_healthy=healthy,
        warnings=tuple(warnings),
    )
