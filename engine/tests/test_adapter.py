from decimal import Decimal as D

import pytest

from smc_execution.binance_usdm import BinanceUSDMAdapter, BinanceUSDMConfig
from smc_execution.errors import AmbiguousSubmitError, ExchangeReject, OrderNotFound
from smc_execution.models import (
    OrderKind,
    OrderRequest,
    PositionMode,
    Side,
    SymbolRules,
)


def test_current_websocket_url_split_is_used():
    live = BinanceUSDMConfig("k", "s", testnet=False)
    demo = BinanceUSDMConfig("k", "s", testnet=True)
    assert live.public_ws == "wss://fstream.binance.com/public"
    assert live.user_ws == "wss://fstream.binance.com/private"
    assert demo.public_ws.endswith("/public")
    assert demo.user_ws.endswith("/private")


def test_algo_stop_is_routed_with_close_position_fields():
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    request = OrderRequest(
        symbol="BTCUSDT",
        side=Side.SELL,
        order_type="STOP_MARKET",
        client_order_id="smc-stop",
        kind=OrderKind.ALGO,
        quantity=None,
        stop_price=D("999.9"),
        position_side="BOTH",
        close_position=True,
        working_type="MARK_PRICE",
        price_protect=False,
    )
    params = adapter._order_params(request)
    assert params["algoType"] == "CONDITIONAL"
    assert params["type"] == "STOP_MARKET"
    assert params["triggerPrice"] == D("999.9")
    assert params["clientAlgoId"] == "smc-stop"
    assert params["closePosition"] is True
    assert params["quantity"] is None


@pytest.mark.asyncio
async def test_query_transport_failure_never_authorizes_blind_retry(monkeypatch):
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    request = OrderRequest(
        symbol="BTCUSDT",
        side=Side.BUY,
        order_type="LIMIT",
        client_order_id="smc-ambiguous",
        quantity=D("0.001"),
        price=D("1000"),
        time_in_force="GTC",
    )
    submitted = False

    async def broken_query(*args, **kwargs):
        raise ConnectionError("query path unavailable")

    async def should_not_submit(*args, **kwargs):
        nonlocal submitted
        submitted = True
        return {}

    monkeypatch.setattr(adapter, "get_order", broken_query)
    monkeypatch.setattr(adapter, "_request", should_not_submit)
    with pytest.raises(AmbiguousSubmitError):
        await adapter._query_before_retry(request, "/fapi/v1/order", TimeoutError())
    assert submitted is False


@pytest.mark.asyncio
async def test_repeated_not_found_never_authorizes_automatic_resubmit(monkeypatch):
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    request = OrderRequest(
        symbol="BTCUSDT",
        side=Side.BUY,
        order_type="LIMIT",
        client_order_id="smc-eventual-consistency",
        quantity=D("0.001"),
        price=D("1000"),
        time_in_force="GTC",
    )
    submit_calls = 0

    async def not_found(*args, **kwargs):
        raise OrderNotFound("not visible yet")

    async def no_delay(*args, **kwargs):
        return None

    async def submit(*args, **kwargs):
        nonlocal submit_calls
        submit_calls += 1
        return {}

    monkeypatch.setattr(adapter, "get_order", not_found)
    monkeypatch.setattr("smc_execution.binance_usdm.asyncio.sleep", no_delay)
    monkeypatch.setattr(adapter, "_request", submit)
    with pytest.raises(AmbiguousSubmitError, match="automatic retry is prohibited"):
        await adapter._query_before_retry(request, "/fapi/v1/order", TimeoutError())
    assert submit_calls == 0


def test_role_specific_credentials_are_selected_without_repr_leakage():
    config = BinanceUSDMConfig(
        "fallback-key",
        "fallback-secret",
        trade_api_key="trade-key",
        trade_api_secret="trade-secret",
        user_data_api_key="user-key",
        user_data_api_secret="user-secret",
        monitor_api_key="monitor-key",
        monitor_api_secret="monitor-secret",
    )
    assert config.credentials("trade") == ("trade-key", "trade-secret")
    assert config.credentials("user_data") == ("user-key", "user-secret")
    assert config.credentials("monitor") == ("monitor-key", "monitor-secret")
    rendered = repr(config)
    for secret in (
        "fallback-key",
        "fallback-secret",
        "trade-key",
        "trade-secret",
        "user-key",
        "user-secret",
        "monitor-key",
        "monitor-secret",
    ):
        assert secret not in rendered


@pytest.mark.asyncio
async def test_binance_timeout_response_is_an_ambiguous_order_result():
    class Response:
        status = 503
        headers = {}

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        async def text(self):
            return '{"code":-1007,"msg":"execution status unknown"}'

    class Session:
        closed = False

        def request(self, *args, **kwargs):
            return Response()

    adapter = BinanceUSDMAdapter(
        BinanceUSDMConfig(
            "monitor-key",
            "monitor-secret",
            trade_api_key="trade-key",
            trade_api_secret="trade-secret",
        )
    )
    adapter._session = Session()
    with pytest.raises(AmbiguousSubmitError, match="unknown execution result"):
        await adapter._request(
            "POST",
            "/fapi/v1/order",
            {"symbol": "BTCUSDT"},
            signed=True,
            credential_role="trade",
            is_order=True,
        )


@pytest.mark.asyncio
async def test_pre_submit_contract_checks_filters_mode_and_brackets(monkeypatch):
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    rules = SymbolRules(
        symbol="BTCUSDT",
        tick_size=D("0.1"),
        step_size=D("0.001"),
        min_qty=D("0.001"),
        max_qty=D("100"),
        min_notional=D("1"),
        status="TRADING",
        market_step_size=D("0.01"),
        market_min_qty=D("0.01"),
        market_max_qty=D("50"),
    )
    bracket_checks = []

    async def fresh(symbol):
        return rules

    async def refresh_mode():
        adapter._position_mode = PositionMode.ONEWAY

    async def bracket(symbol, notional):
        bracket_checks.append((symbol, notional))

    monkeypatch.setattr(adapter, "_fresh_symbol_rules", fresh)
    monkeypatch.setattr(adapter, "_refresh_position_mode", refresh_mode)
    monkeypatch.setattr(adapter, "_validate_leverage_bracket", bracket)

    valid = OrderRequest(
        symbol="BTCUSDT",
        side=Side.BUY,
        order_type="LIMIT",
        client_order_id="contract-valid-1",
        quantity=D("0.010"),
        price=D("100.0"),
        time_in_force="GTC",
        position_side="BOTH",
    )
    await adapter._validate_order_request(valid)
    assert bracket_checks == [("BTCUSDT", D("1.0000"))]

    with pytest.raises(ExchangeReject, match="stepSize"):
        await adapter._validate_order_request(
            OrderRequest(
                **{**valid.__dict__, "client_order_id": "bad-step", "quantity": D("0.0105")}
            )
        )
    with pytest.raises(ExchangeReject, match="MARKET|stepSize"):
        await adapter._validate_order_request(
            OrderRequest(
                symbol="BTCUSDT",
                side=Side.BUY,
                order_type="MARKET",
                client_order_id="bad-market-step",
                quantity=D("0.005"),
                position_side="BOTH",
            )
        )


@pytest.mark.asyncio
async def test_pre_submit_contract_rejects_non_trading_and_hedge_reduce_only(monkeypatch):
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    halted = SymbolRules(
        symbol="BTCUSDT",
        tick_size=D("0.1"),
        step_size=D("0.001"),
        min_qty=D("0.001"),
        max_qty=D("100"),
        min_notional=D("1"),
        status="HALT",
    )

    async def halted_rules(symbol):
        return halted

    async def hedge_mode():
        adapter._position_mode = PositionMode.HEDGE

    monkeypatch.setattr(adapter, "_fresh_symbol_rules", halted_rules)
    monkeypatch.setattr(adapter, "_refresh_position_mode", hedge_mode)
    request = OrderRequest(
        symbol="BTCUSDT",
        side=Side.SELL,
        order_type="LIMIT",
        client_order_id="halted-order",
        quantity=D("0.01"),
        price=D("100"),
        time_in_force="GTC",
        position_side="LONG",
        reduce_only=True,
    )
    with pytest.raises(ExchangeReject, match="status=HALT"):
        await adapter._validate_order_request(request)

    trading = halted.__class__(**{**halted.__dict__, "status": "TRADING"})

    async def trading_rules(symbol):
        return trading

    monkeypatch.setattr(adapter, "_fresh_symbol_rules", trading_rules)
    with pytest.raises(ExchangeReject, match="reduceOnly"):
        await adapter._validate_order_request(request)


@pytest.mark.asyncio
async def test_compact_algo_update_is_parsed_and_published():
    from smc_execution.models import OrderStatus

    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    await adapter._handle_user_event(
        {
            "e": "ALGO_UPDATE",
            "E": 1_700_000_000_123,
            "o": {
                "caid": "stop-1",
                "aid": 42,
                "s": "BTCUSDT",
                "S": "SELL",
                "ps": "BOTH",
                "o": "STOP_MARKET",
                "q": "0.010",
                "aq": "0.004",
                "ap": "999.5",
                "tp": "995.0",
                "p": "0",
                "X": "TRIGGERED",
            },
        }
    )
    snapshot = adapter._order_cache[(OrderKind.ALGO, "stop-1")]
    assert snapshot.order_id == "42"
    assert snapshot.status is OrderStatus.TRIGGERED
    assert snapshot.order_type == "STOP_MARKET"
    assert snapshot.executed_qty == D("0.004")
    assert snapshot.stop_price == D("995.0")
    assert snapshot.update_time_ms == 1_700_000_000_123


@pytest.mark.asyncio
async def test_open_order_endpoint_weights_follow_symbol_scope(monkeypatch):
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    calls = []

    async def fake_request(method, path, params=None, **kwargs):
        calls.append((path, params, kwargs["weight"]))
        return []

    monkeypatch.setattr(adapter, "_request", fake_request)
    await adapter.list_open_orders(None, OrderKind.REGULAR)
    await adapter.list_open_orders("BTCUSDT", OrderKind.REGULAR)
    await adapter.list_open_orders(None, OrderKind.ALGO)
    await adapter.list_open_orders("BTCUSDT", OrderKind.ALGO)
    assert [call[2] for call in calls] == [40, 1, 40, 1]


@pytest.mark.asyncio
async def test_account_snapshot_uses_global_algo_weight_and_replaces_positions(monkeypatch):
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    calls = []
    position_rows = [
        {
            "symbol": "BTCUSDT",
            "positionSide": "BOTH",
            "positionAmt": "0.01",
            "entryPrice": "1000",
            "markPrice": "1010",
        }
    ]

    async def fake_request(method, path, params=None, **kwargs):
        calls.append((path, kwargs["weight"]))
        if path == "/fapi/v3/account":
            return {
                "totalMarginBalance": "10000",
                "availableBalance": "9000",
                "canTrade": True,
            }
        if path == "/fapi/v3/positionRisk":
            return list(position_rows)
        if path == "/fapi/v1/openAlgoOrders":
            return [
                {
                    "symbol": "BTCUSDT",
                    "positionSide": "BOTH",
                    "side": "SELL",
                    "orderType": "STOP_MARKET",
                    "algoStatus": "NEW",
                    "triggerPrice": "990",
                }
            ]
        raise AssertionError(path)

    monkeypatch.setattr(adapter, "_request", fake_request)
    await adapter.get_account_snapshot()
    assert ("/fapi/v1/openAlgoOrders", 40) in calls
    assert ("BTCUSDT", "BOTH") in adapter._positions

    adapter._account_cache = None
    position_rows.clear()
    await adapter.get_account_snapshot()
    assert adapter._positions == {}


def test_open_risk_requires_correct_exit_side_and_accepts_trailing_stop():
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    positions = [
        {
            "symbol": "BTCUSDT",
            "positionSide": "BOTH",
            "positionAmt": "2",
            "entryPrice": "100",
            "markPrice": "120",
        }
    ]
    wrong_side = [
        {
            "symbol": "BTCUSDT",
            "positionSide": "BOTH",
            "side": "BUY",
            "orderType": "STOP_MARKET",
            "algoStatus": "NEW",
            "triggerPrice": "110",
        }
    ]
    risk, unprotected = adapter._estimate_open_risk(positions, wrong_side)
    assert risk == D("240")
    assert unprotected == {"BTCUSDT:BOTH"}

    trailing = [
        {
            "symbol": "BTCUSDT",
            "positionSide": "BOTH",
            "side": "SELL",
            "orderType": "STOP_MARKET",
            "algoStatus": "NEW",
            "triggerPrice": "110",
        }
    ]
    risk, unprotected = adapter._estimate_open_risk(positions, trailing)
    assert risk == D("20")
    assert not unprotected


def test_book_ticker_rejects_cross_market_and_out_of_order_updates():
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    adapter._symbols.add("BTCUSDT")
    assert adapter._ingest_book_ticker(
        {"e": "bookTicker", "s": "BTCUSDT", "b": "100", "B": "2", "a": "101", "A": "3", "u": 10, "st": 1, "E": 1000}
    )
    assert not adapter._ingest_book_ticker(
        {"e": "bookTicker", "s": "BTCUSDT", "b": "90", "B": "2", "a": "91", "A": "3", "u": 9, "st": 1, "E": 1001}
    )
    assert not adapter._ingest_book_ticker(
        {"e": "bookTicker", "s": "BTCUSDT", "b": "200", "B": "2", "a": "201", "A": "3", "u": 11, "st": 2, "E": 1002}
    )
    assert adapter._quotes["BTCUSDT"].bid == D("100")
    assert adapter._quotes["BTCUSDT"].sequence_id == 10


@pytest.mark.asyncio
async def test_order_cache_never_regresses_executed_quantity_or_terminal_state():
    from smc_execution.models import OrderSnapshot, OrderStatus

    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s"))
    partial = OrderSnapshot(
        order_id="1",
        client_order_id="entry-1",
        symbol="BTCUSDT",
        side=Side.BUY,
        status=OrderStatus.PARTIALLY_FILLED,
        orig_qty=D("1"),
        executed_qty=D("0.5"),
        cumulative_quote_qty=D("50"),
        avg_price=D("100"),
        update_time_ms=100,
    )
    stale = OrderSnapshot(
        order_id="1",
        client_order_id="entry-1",
        symbol="BTCUSDT",
        side=Side.BUY,
        status=OrderStatus.NEW,
        orig_qty=D("1"),
        executed_qty=D("0"),
        cumulative_quote_qty=D("0"),
        avg_price=D("0"),
        update_time_ms=101,
    )
    terminal = OrderSnapshot(
        order_id="1",
        client_order_id="entry-1",
        symbol="BTCUSDT",
        side=Side.BUY,
        status=OrderStatus.CANCELED,
        orig_qty=D("1"),
        executed_qty=D("0.5"),
        cumulative_quote_qty=D("50"),
        avg_price=D("100"),
        update_time_ms=102,
    )
    regressive_same_time = OrderSnapshot(
        order_id="1",
        client_order_id="entry-1",
        symbol="BTCUSDT",
        side=Side.BUY,
        status=OrderStatus.NEW,
        orig_qty=D("1"),
        executed_qty=D("0.5"),
        cumulative_quote_qty=D("50"),
        avg_price=D("100"),
        update_time_ms=102,
    )
    await adapter._publish_order(partial)
    await adapter._publish_order(stale)
    assert adapter._order_cache[(OrderKind.REGULAR, "entry-1")].executed_qty == D("0.5")
    await adapter._publish_order(terminal)
    await adapter._publish_order(regressive_same_time)
    assert adapter._order_cache[(OrderKind.REGULAR, "entry-1")].status is OrderStatus.CANCELED


def test_private_stream_url_uses_new_query_based_route():
    adapter = BinanceUSDMAdapter(BinanceUSDMConfig("k", "s", testnet=False))
    url = adapter._private_stream_url("listen-123")
    assert url.startswith("wss://fstream.binance.com/private/ws?")
    assert "listenKey=listen-123" in url
    assert "ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ACCOUNT_CONFIG_UPDATE/ALGO_UPDATE/MARGIN_CALL" in url
    assert "/ws/listen-123" not in url


def test_credentials_are_redacted_from_config_repr():
    config = BinanceUSDMConfig("visible-key", "super-secret")
    rendered = repr(config)
    assert "visible-key" not in rendered
    assert "super-secret" not in rendered
