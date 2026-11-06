"""Opt-in Binance USD-M Demo/Testnet contract checks.

These tests are never allowed to use the live endpoint.  Read-only checks require
``RUN_BINANCE_TESTNET_CONTRACT=1``.  The order lifecycle check additionally requires
``RUN_BINANCE_TESTNET_ORDER_CONTRACT=1`` and a clean dedicated Testnet account.
"""

from __future__ import annotations

import asyncio
import os
import time
import uuid

import pytest

from smc_execution.binance_usdm import BinanceUSDMAdapter, BinanceUSDMConfig
from smc_execution.environment import env_secret
from smc_execution.models import (
    ZERO,
    D,
    OrderKind,
    OrderRequest,
    PositionMode,
    Side,
    ceil_to_step,
    floor_to_step,
)


pytestmark = [
    pytest.mark.binance_contract,
    pytest.mark.skipif(
        os.getenv("RUN_BINANCE_TESTNET_CONTRACT") != "1",
        reason="set RUN_BINANCE_TESTNET_CONTRACT=1 for Binance Testnet",
    ),
]


def _adapter() -> BinanceUSDMAdapter:
    trade_key = env_secret("BINANCE_TRADE_API_KEY", legacy_name="BINANCE_API_KEY")
    trade_secret = env_secret(
        "BINANCE_TRADE_API_SECRET", legacy_name="BINANCE_API_SECRET"
    )
    user_key = env_secret(
        "BINANCE_USER_DATA_API_KEY", legacy_name="BINANCE_API_KEY"
    )
    user_secret = env_secret(
        "BINANCE_USER_DATA_API_SECRET", legacy_name="BINANCE_API_SECRET"
    )
    monitor_key = env_secret(
        "BINANCE_MONITOR_API_KEY", legacy_name="BINANCE_API_KEY"
    )
    monitor_secret = env_secret(
        "BINANCE_MONITOR_API_SECRET", legacy_name="BINANCE_API_SECRET"
    )
    return BinanceUSDMAdapter(
        BinanceUSDMConfig(
            api_key=monitor_key,
            api_secret=monitor_secret,
            testnet=True,
            trade_api_key=trade_key,
            trade_api_secret=trade_secret,
            user_data_api_key=user_key,
            user_data_api_secret=user_secret,
            monitor_api_key=monitor_key,
            monitor_api_secret=monitor_secret,
        )
    )


async def _wait_for_private_stream(adapter: BinanceUSDMAdapter) -> None:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if await adapter.is_user_stream_healthy():
            return
        await asyncio.sleep(0.1)
    pytest.fail("Binance Testnet private stream did not become healthy")


async def _flatten_symbol(adapter: BinanceUSDMAdapter, symbol: str) -> None:
    for position in await adapter.list_positions():
        if position.symbol != symbol or position.is_flat:
            continue
        is_long = (
            position.position_side == "LONG"
            if position.position_mode is PositionMode.HEDGE
            else position.quantity > ZERO
        )
        await adapter.place_order(
            OrderRequest(
                symbol=symbol,
                side=Side.SELL if is_long else Side.BUY,
                order_type="MARKET",
                client_order_id=f"smc-cleanup-{uuid.uuid4().hex[:20]}",
                quantity=position.abs_qty,
                position_side=(
                    position.position_side
                    if position.position_mode is PositionMode.HEDGE
                    else "BOTH"
                ),
                reduce_only=position.position_mode is PositionMode.ONEWAY,
            )
        )


@pytest.mark.asyncio
async def test_testnet_read_and_stream_contract():
    symbol = os.getenv("BINANCE_CONTRACT_SYMBOL", "BTCUSDT").upper()
    adapter = _adapter()
    await adapter.start((symbol,))
    try:
        await _wait_for_private_stream(adapter)
        rules = await adapter.get_symbol_rules(symbol)
        quote = await adapter.get_quote(symbol)
        account = await adapter.get_account_snapshot()
        positions = await adapter.list_positions()
        regular = await adapter.list_open_orders(symbol, OrderKind.REGULAR)
        algos = await adapter.list_open_orders(symbol, OrderKind.ALGO)
        assert rules.symbol == symbol and rules.tick_size > ZERO
        assert rules.step_size > ZERO and rules.status == "TRADING"
        assert quote.symbol == symbol and quote.ask > quote.bid > ZERO
        assert account.equity >= ZERO and account.available_balance >= ZERO
        assert all(item.symbol == symbol for item in positions if item.symbol == symbol)
        assert all(item.kind is OrderKind.REGULAR for item in regular)
        assert all(item.kind is OrderKind.ALGO for item in algos)
    finally:
        await adapter.close()


@pytest.mark.asyncio
async def test_testnet_maker_query_cancel_contract():
    if os.getenv("RUN_BINANCE_TESTNET_ORDER_CONTRACT") != "1":
        pytest.skip("set RUN_BINANCE_TESTNET_ORDER_CONTRACT=1 for order mutations")
    symbol = os.getenv("BINANCE_CONTRACT_SYMBOL", "BTCUSDT").upper()
    adapter = _adapter()
    await adapter.start((symbol,))
    order = None
    try:
        await _wait_for_private_stream(adapter)
        positions = [
            item
            for item in await adapter.list_positions()
            if item.symbol == symbol and not item.is_flat
        ]
        regular = await adapter.list_open_orders(symbol, OrderKind.REGULAR)
        algos = await adapter.list_open_orders(symbol, OrderKind.ALGO)
        if positions or regular or algos:
            pytest.fail("Contract account must start flat with no open orders")

        rules = await adapter.get_symbol_rules(symbol)
        quote = await adapter.get_quote(symbol)
        price = floor_to_step(quote.bid * D("0.50"), rules.tick_size)
        quantity = ceil_to_step(
            max(rules.min_qty, rules.min_notional / price), rules.step_size
        )
        if quantity > rules.max_qty:
            pytest.skip("Symbol minimum order exceeds the contract test policy")
        account = await adapter.get_account_snapshot()
        position_side = (
            "LONG" if account.position_mode is PositionMode.HEDGE else "BOTH"
        )
        order = await adapter.place_order(
            OrderRequest(
                symbol=symbol,
                side=Side.BUY,
                order_type="LIMIT",
                client_order_id=f"smc-contract-{uuid.uuid4().hex[:20]}",
                quantity=quantity,
                price=price,
                time_in_force="GTX",
                position_side=position_side,
                self_trade_prevention_mode="EXPIRE_TAKER",
            )
        )
        queried = await adapter.get_order(
            symbol, order.order_id, order.client_order_id, OrderKind.REGULAR
        )
        assert queried.client_order_id == order.client_order_id
        assert queried.status.active
        canceled = await adapter.cancel_order(
            symbol, queried.order_id, queried.client_order_id, OrderKind.REGULAR
        )
        assert canceled.status.terminal
    finally:
        if order is not None:
            try:
                latest = await adapter.get_order(
                    symbol, order.order_id, order.client_order_id, OrderKind.REGULAR
                )
                if latest.status.active:
                    await adapter.cancel_order(
                        symbol,
                        latest.order_id,
                        latest.client_order_id,
                        OrderKind.REGULAR,
                    )
            except Exception:
                pass
        try:
            await _flatten_symbol(adapter, symbol)
        finally:
            await adapter.close()
