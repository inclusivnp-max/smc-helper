from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import random
import re
import time
from collections import deque
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, replace
from decimal import Decimal
from typing import Any
from urllib.parse import urlencode

import aiohttp
import websockets

from .errors import AmbiguousSubmitError, ExchangeReject, OrderNotFound
from .models import (
    ZERO,
    AccountSnapshot,
    Action,
    D,
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

log = logging.getLogger(__name__)

_CLIENT_ORDER_ID = re.compile(r"^[.A-Za-z0-9_:/-]{1,36}$")


@dataclass(frozen=True)
class BinanceUSDMConfig:
    api_key: str = field(repr=False)
    api_secret: str = field(repr=False)
    testnet: bool = True
    recv_window_ms: int = 5_000
    request_timeout_seconds: float = 5.0
    max_leverage_for_sizing: D = D("3")
    margin_utilization_cap: D = D("0.50")
    max_new_notional_cap: D = D("5000")
    account_cache_ttl_ms: int = 250
    rules_cache_ttl_seconds: int = 900
    brackets_cache_ttl_seconds: int = 300
    listen_key_keepalive_seconds: int = 30 * 60
    public_ws_base: str | None = None
    user_ws_base: str | None = None
    trade_api_key: str | None = field(default=None, repr=False)
    trade_api_secret: str | None = field(default=None, repr=False)
    user_data_api_key: str | None = field(default=None, repr=False)
    user_data_api_secret: str | None = field(default=None, repr=False)
    monitor_api_key: str | None = field(default=None, repr=False)
    monitor_api_secret: str | None = field(default=None, repr=False)

    def credentials(self, role: str) -> tuple[str, str]:
        pairs = {
            "trade": (self.trade_api_key, self.trade_api_secret),
            "user_data": (self.user_data_api_key, self.user_data_api_secret),
            "monitor": (self.monitor_api_key, self.monitor_api_secret),
        }
        if role not in pairs:
            raise ValueError(f"Unknown Binance credential role: {role}")
        key, secret = pairs[role]
        key = key or self.api_key
        secret = secret or self.api_secret
        if not key or not secret:
            raise RuntimeError(f"Missing Binance credentials for role {role}")
        return key, secret

    @property
    def rest_base(self) -> str:
        return (
            "https://testnet.binancefuture.com"
            if self.testnet
            else "https://fapi.binance.com"
        )

    @property
    def public_ws(self) -> str:
        if self.public_ws_base:
            return self.public_ws_base.rstrip("/")
        root = (
            "wss://stream.binancefuture.com"
            if self.testnet
            else "wss://fstream.binance.com"
        )
        return root + "/public"

    @property
    def user_ws(self) -> str:
        if self.user_ws_base:
            return self.user_ws_base.rstrip("/")
        root = (
            "wss://stream.binancefuture.com"
            if self.testnet
            else "wss://fstream.binance.com"
        )
        return root + "/private"


class HeaderAwareRateLimiter:
    """Local sliding windows plus server-header backpressure."""

    def __init__(self) -> None:
        self.request_weight_limit = 2400
        self.order_10s_limit = 100
        self.order_1m_limit = 1200
        self.normal_utilization = 0.80
        self._weights: deque[tuple[float, int]] = deque()
        self._orders: deque[float] = deque()
        self._pause_until = 0.0
        self._lock = asyncio.Lock()

    def configure(self, rate_limits: Sequence[Mapping[str, Any]]) -> None:
        for item in rate_limits:
            kind = item.get("rateLimitType")
            interval = item.get("interval")
            num = int(item.get("intervalNum", 1))
            limit = int(item.get("limit", 0))
            if kind == "REQUEST_WEIGHT" and interval == "MINUTE" and num == 1:
                self.request_weight_limit = limit
            elif kind == "ORDERS" and interval == "SECOND" and num == 10:
                self.order_10s_limit = limit
            elif kind == "ORDERS" and interval == "MINUTE" and num == 1:
                self.order_1m_limit = limit

    async def acquire(
        self, *, weight: int = 1, is_order: bool = False, emergency: bool = False
    ) -> None:
        while True:
            async with self._lock:
                now = time.monotonic()
                self._trim(now)
                pause = self._pause_until - now
                weight_cap = self.request_weight_limit * (
                    1.0 if emergency else self.normal_utilization
                )
                order_10_cap = self.order_10s_limit * (
                    1.0 if emergency else self.normal_utilization
                )
                order_60_cap = self.order_1m_limit * (
                    1.0 if emergency else self.normal_utilization
                )
                used_weight = sum(cost for _, cost in self._weights)
                orders_10 = sum(1 for ts in self._orders if now - ts < 10)
                orders_60 = len(self._orders)
                allowed = (
                    pause <= 0
                    and used_weight + weight <= weight_cap
                    and (
                        not is_order
                        or (orders_10 + 1 <= order_10_cap and orders_60 + 1 <= order_60_cap)
                    )
                )
                if allowed:
                    self._weights.append((now, weight))
                    if is_order:
                        self._orders.append(now)
                    return
                sleeps = [0.05]
                if pause > 0:
                    sleeps.append(pause)
                if self._weights:
                    sleeps.append(max(0.01, 60 - (now - self._weights[0][0])))
                if is_order and self._orders:
                    sleeps.append(max(0.01, 10 - (now - self._orders[0])))
                sleep_for = min(x for x in sleeps if x > 0)
            await asyncio.sleep(min(sleep_for, 1.0))

    async def backoff(self, seconds: float) -> None:
        async with self._lock:
            self._pause_until = max(self._pause_until, time.monotonic() + seconds)

    def observe_headers(self, headers: Mapping[str, str]) -> None:
        # Headers are authoritative diagnostics. Local windows remain conservative;
        # a near-limit response inserts a short pause instead of attempting exact reset math.
        try:
            used_weight = int(headers.get("X-MBX-USED-WEIGHT-1M", "0"))
            order_10 = int(headers.get("X-MBX-ORDER-COUNT-10S", "0"))
            order_1m = int(headers.get("X-MBX-ORDER-COUNT-1M", "0"))
        except ValueError:
            return
        if (
            used_weight >= self.request_weight_limit * 0.90
            or order_10 >= self.order_10s_limit * 0.90
            or order_1m >= self.order_1m_limit * 0.90
        ):
            self._pause_until = max(self._pause_until, time.monotonic() + 1.0)

    def _trim(self, now: float) -> None:
        while self._weights and now - self._weights[0][0] >= 60:
            self._weights.popleft()
        while self._orders and now - self._orders[0] >= 60:
            self._orders.popleft()


class BinanceUSDMAdapter:
    """
    Concrete Binance USD-M adapter.

    Safety properties:
    * Decimal values are serialized as strings;
    * conditional orders route to /fapi/v1/algoOrder;
    * uncertain POST responses use query-before-retry;
    * public bookTicker and private user stream maintain local caches;
    * REST V3 snapshots reconcile positions/account state.
    """

    def __init__(self, config: BinanceUSDMConfig) -> None:
        self.config = config
        self._session: aiohttp.ClientSession | None = None
        self._server_offset_ms = 0
        self._limiter = HeaderAwareRateLimiter()
        self._symbols: set[str] = set()
        self._rules: dict[str, tuple[float, SymbolRules]] = {}
        self._brackets: dict[str, tuple[float, tuple[Mapping[str, Any], ...]]] = {}
        self._quotes: dict[str, Quote] = {}
        self._positions: dict[tuple[str, str], PositionSnapshot] = {}
        self._symbol_leverage: dict[str, D] = {}
        self._position_mode = PositionMode.ONEWAY
        self._account_cache: tuple[float, AccountSnapshot] | None = None
        self._order_cache: dict[tuple[OrderKind, str], OrderSnapshot] = {}
        self._order_versions: dict[tuple[OrderKind, str], int] = {}
        self._order_condition = asyncio.Condition()
        self._tasks: list[asyncio.Task[Any]] = []
        self._closing = asyncio.Event()
        self._user_stream_connected = False
        self._market_stream_connected = False
        self._listen_key: str | None = None

    async def start(self, symbols: Sequence[str]) -> None:
        self._symbols.update(s.upper() for s in symbols)
        self._closing.clear()
        if self._session is None or self._session.closed:
            timeout = aiohttp.ClientTimeout(total=self.config.request_timeout_seconds)
            self._session = aiohttp.ClientSession(timeout=timeout)
        await self._sync_time()
        exchange_info = await self._request("GET", "/fapi/v1/exchangeInfo", weight=1)
        self._limiter.configure(exchange_info.get("rateLimits", []))
        self._cache_rules_from_exchange_info(exchange_info)
        await self._refresh_position_mode()
        await self._refresh_positions()
        self._tasks = [
            asyncio.create_task(self._public_stream_loop(), name="binance-public-bookticker"),
            asyncio.create_task(self._user_stream_loop(), name="binance-user-stream"),
        ]

    async def close(self) -> None:
        self._closing.set()
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks.clear()
        self._user_stream_connected = False
        self._market_stream_connected = False
        if self._listen_key:
            try:
                await self._request(
                    "DELETE",
                    "/fapi/v1/listenKey",
                    api_key_only=True,
                    credential_role="user_data",
                    weight=1,
                )
            except Exception:
                pass
        if self._session and not self._session.closed:
            await self._session.close()

    async def is_user_stream_healthy(self) -> bool:
        return self._user_stream_connected and any(
            task.get_name() == "binance-user-stream" and not task.done()
            for task in self._tasks
        )

    async def get_symbol_rules(self, symbol: str) -> SymbolRules:
        symbol = symbol.upper()
        cached = self._rules.get(symbol)
        if cached and time.monotonic() - cached[0] < self.config.rules_cache_ttl_seconds:
            return cached[1]
        data = await self._request("GET", "/fapi/v1/exchangeInfo", weight=1)
        self._cache_rules_from_exchange_info(data)
        if symbol not in self._rules:
            raise ExchangeReject(f"Unknown symbol {symbol}")
        return self._rules[symbol][1]

    async def get_quote(self, symbol: str) -> Quote:
        symbol = symbol.upper()
        cached = self._quotes.get(symbol)
        if cached and (time.monotonic() - cached.received_monotonic) < 0.75:
            return cached
        data = await self._request(
            "GET", "/fapi/v1/ticker/bookTicker", {"symbol": symbol}, weight=2
        )
        quote = Quote(
            symbol=symbol,
            bid=D(data["bidPrice"]),
            ask=D(data["askPrice"]),
            bid_qty=D(data["bidQty"]),
            ask_qty=D(data["askQty"]),
            exchange_time_ms=int(data.get("time") or self._now_ms()),
            sequence_id=None,
            exchange_age_at_receive_ms=max(
                0,
                self._now_ms() - int(data.get("time") or self._now_ms()),
            ),
        )
        self._quotes[symbol] = quote
        return quote

    async def get_account_snapshot(self) -> AccountSnapshot:
        cached = self._account_cache
        if cached and (time.monotonic() - cached[0]) * 1000 < self.config.account_cache_ttl_ms:
            return cached[1]
        account, positions, algos = await asyncio.gather(
            self._request(
                "GET", "/fapi/v3/account", signed=True, credential_role="monitor", weight=5
            ),
            self._request(
                "GET",
                "/fapi/v3/positionRisk",
                signed=True,
                credential_role="monitor",
                weight=5,
            ),
            self._request(
                "GET",
                "/fapi/v1/openAlgoOrders",
                signed=True,
                credential_role="monitor",
                weight=40,
            ),
        )
        self._replace_positions(positions)
        equity = D(account.get("totalMarginBalance", "0"))
        available = D(account.get("availableBalance", "0"))
        max_new = min(
            self.config.max_new_notional_cap,
            available
            * self.config.max_leverage_for_sizing
            * self.config.margin_utilization_cap,
        )
        current_risk, unprotected = self._estimate_open_risk(positions, algos)
        can_trade = bool(account.get("canTrade", True))
        risk_halt = not can_trade or bool(unprotected)
        reason = None
        if not can_trade:
            reason = "Binance account canTrade=false"
        elif unprotected:
            reason = "Unprotected positions: " + ", ".join(sorted(unprotected))
        snapshot = AccountSnapshot(
            equity=equity,
            available_balance=available,
            max_new_position_notional=max_new,
            current_open_risk=current_risk,
            position_mode=self._position_mode,
            risk_halt=risk_halt,
            risk_halt_reason=reason,
            update_time_ms=self._now_ms(),
        )
        self._account_cache = (time.monotonic(), snapshot)
        return snapshot

    async def get_closed_klines(
        self, symbol: str, interval: str, limit: int
    ) -> Sequence[Kline]:
        data = await self._request(
            "GET",
            "/fapi/v1/klines",
            {"symbol": symbol.upper(), "interval": interval, "limit": limit},
            weight=5 if limit > 100 else 1,
        )
        now = self._now_ms()
        return [
            Kline(
                open_time_ms=int(row[0]),
                close_time_ms=int(row[6]),
                high=D(row[2]),
                low=D(row[3]),
                close=D(row[4]),
                is_closed=int(row[6]) < now,
            )
            for row in data
        ]

    async def get_position(self, symbol: str, action: Action) -> PositionSnapshot:
        symbol = symbol.upper()
        positions = await self._request(
            "GET",
            "/fapi/v3/positionRisk",
            {"symbol": symbol},
            signed=True,
            credential_role="monitor",
            weight=5,
        )
        self._replace_positions(positions, symbol=symbol)
        if self._position_mode is PositionMode.HEDGE:
            key = (symbol, action.value)
        else:
            key = (symbol, "BOTH")
        return self._positions.get(
            key,
            PositionSnapshot(
                symbol=symbol,
                position_mode=self._position_mode,
                position_side=key[1],
                quantity=ZERO,
                entry_price=ZERO,
                mark_price=ZERO,
                update_time_ms=self._now_ms(),
            ),
        )

    async def list_positions(self) -> Sequence[PositionSnapshot]:
        positions = await self._request(
            "GET",
            "/fapi/v3/positionRisk",
            signed=True,
            credential_role="monitor",
            weight=5,
        )
        self._replace_positions(positions)
        return tuple(self._positions.values())

    async def place_order(self, request: OrderRequest) -> OrderSnapshot:
        await self._validate_order_request(request)
        params = self._order_params(request)
        path = "/fapi/v1/algoOrder" if request.kind is OrderKind.ALGO else "/fapi/v1/order"
        try:
            data = await self._request(
                "POST",
                path,
                params,
                signed=True,
                credential_role="trade",
                weight=1,
                is_order=True,
                emergency=request.client_order_id.endswith(("EIOC", "EMKT")),
            )
            snapshot = self._parse_order(data, request.kind, fallback=request)
            await self._publish_order(snapshot)
            return snapshot
        except (asyncio.TimeoutError, aiohttp.ClientError, AmbiguousSubmitError) as exc:
            return await self._query_before_retry(request, path, exc)
        except ExchangeReject as exc:
            # Duplicate client id after an ambiguous response means the first request may exist.
            if exc.code in {-4111, -2010}:
                try:
                    return await self.get_order(
                        request.symbol, None, request.client_order_id, request.kind
                    )
                except Exception as query_exc:
                    raise AmbiguousSubmitError(
                        f"Duplicate client ID cannot be reconciled for "
                        f"{request.client_order_id}: {query_exc!r}"
                    ) from exc
            raise

    async def cancel_order(
        self,
        symbol: str,
        order_id: str,
        client_order_id: str,
        kind: OrderKind,
    ) -> OrderSnapshot:
        if kind is OrderKind.ALGO:
            params = {
                "symbol": symbol,
                "algoId": order_id or None,
                "clientAlgoId": client_order_id or None,
            }
            path = "/fapi/v1/algoOrder"
        else:
            params = {
                "symbol": symbol,
                "orderId": order_id or None,
                "origClientOrderId": client_order_id or None,
            }
            path = "/fapi/v1/order"
        data = await self._request(
            "DELETE",
            path,
            params,
            signed=True,
            credential_role="trade",
            weight=1,
            is_order=True,
        )
        snapshot = self._parse_order(data, kind)
        await self._publish_order(snapshot)
        return snapshot

    async def get_order(
        self,
        symbol: str,
        order_id: str | None,
        client_order_id: str,
        kind: OrderKind,
    ) -> OrderSnapshot:
        if kind is OrderKind.ALGO:
            params = {
                "symbol": symbol,
                "algoId": order_id or None,
                "clientAlgoId": client_order_id or None,
            }
            path = "/fapi/v1/algoOrder"
        else:
            params = {
                "symbol": symbol,
                "orderId": order_id or None,
                "origClientOrderId": client_order_id or None,
            }
            path = "/fapi/v1/order"
        try:
            data = await self._request(
                "GET",
                path,
                params,
                signed=True,
                credential_role="user_data",
                weight=1,
            )
        except ExchangeReject as exc:
            if exc.code in {-2013, -2011}:
                raise OrderNotFound(f"Order not found: {client_order_id}") from exc
            raise
        snapshot = self._parse_order(data, kind)
        await self._publish_order(snapshot)
        return snapshot

    async def wait_order_update(
        self,
        symbol: str,
        order_id: str,
        client_order_id: str,
        kind: OrderKind,
        timeout_ms: int,
    ) -> OrderSnapshot | None:
        key = (kind, client_order_id)
        async with self._order_condition:
            initial_version = self._order_versions.get(key, 0)
            cached = self._order_cache.get(key)
            if cached and cached.status.terminal:
                return cached
            try:
                await asyncio.wait_for(
                    self._order_condition.wait_for(
                        lambda: self._order_versions.get(key, 0) > initial_version
                    ),
                    timeout=max(0.001, timeout_ms / 1000),
                )
            except TimeoutError:
                return None
            return self._order_cache.get(key)

    async def list_open_orders(
        self, symbol: str | None, kind: OrderKind
    ) -> Sequence[OrderSnapshot]:
        params = {"symbol": symbol} if symbol else None
        path = "/fapi/v1/openAlgoOrders" if kind is OrderKind.ALGO else "/fapi/v1/openOrders"
        weight = 1 if symbol else 40
        data = await self._request(
            "GET",
            path,
            params,
            signed=True,
            credential_role="monitor",
            weight=weight,
        )
        return tuple(self._parse_order(item, kind) for item in data)

    async def _query_before_retry(
        self, request: OrderRequest, path: str, original_exc: Exception
    ) -> OrderSnapshot:
        del path  # Deliberately retained in the signature for adapter compatibility.
        for delay in (0.05, 0.15, 0.35):
            await asyncio.sleep(delay)
            try:
                return await self.get_order(
                    request.symbol, None, request.client_order_id, request.kind
                )
            except OrderNotFound:
                continue
            except Exception as query_exc:
                raise AmbiguousSubmitError(
                    f"Submit result cannot be queried safely for "
                    f"{request.client_order_id}: {query_exc!r}"
                ) from original_exc
        # A short series of not-found responses is not proof of rejection: the
        # matching engine and query path are eventually consistent.  Never submit
        # again automatically.  Durable recovery keeps this execution UNKNOWN until
        # REST/user-stream reconciliation proves the actual result.
        raise AmbiguousSubmitError(
            f"Submit result remains UNKNOWN for {request.client_order_id}; "
            "automatic retry is prohibited"
        ) from original_exc

    async def _validate_order_request(self, request: OrderRequest) -> None:
        """Fail closed on exchange filters and account mode before every submit."""

        symbol = request.symbol.upper().strip()
        if symbol != request.symbol or not symbol:
            raise ExchangeReject("Order symbol must be normalized uppercase")
        rules, _ = await asyncio.gather(
            self._fresh_symbol_rules(symbol),
            self._refresh_position_mode(),
        )
        if rules.status != "TRADING":
            raise ExchangeReject(
                f"Symbol {symbol} does not accept new orders: status={rules.status}"
            )
        if not _CLIENT_ORDER_ID.fullmatch(request.client_order_id):
            raise ExchangeReject("Invalid Binance clientOrderId")

        position_side = request.position_side or "BOTH"
        if self._position_mode is PositionMode.HEDGE:
            if position_side not in {"LONG", "SHORT"}:
                raise ExchangeReject("Hedge mode requires positionSide LONG or SHORT")
            if request.reduce_only:
                raise ExchangeReject("reduceOnly cannot be sent in Hedge mode")
            if request.close_position and (
                (request.side is Side.BUY and position_side == "LONG")
                or (request.side is Side.SELL and position_side == "SHORT")
            ):
                raise ExchangeReject("closePosition side would increase Hedge exposure")
        elif position_side != "BOTH":
            raise ExchangeReject("One-way mode requires positionSide BOTH")

        if request.close_position:
            if request.quantity is not None or request.reduce_only:
                raise ExchangeReject(
                    "closePosition cannot be combined with quantity or reduceOnly"
                )
        elif request.quantity is None:
            raise ExchangeReject("Order quantity is required")

        quantity = request.quantity
        if quantity is not None:
            market = request.order_type == "MARKET"
            step = rules.market_step_size if market else rules.step_size
            minimum = rules.market_min_qty if market else rules.min_qty
            maximum = rules.market_max_qty if market else rules.max_qty
            step = step or rules.step_size
            minimum = minimum or rules.min_qty
            maximum = maximum or rules.max_qty
            if quantity <= ZERO or step <= ZERO or quantity % step != ZERO:
                raise ExchangeReject(
                    f"Quantity {quantity} does not match stepSize {step}"
                )
            if quantity < minimum or (maximum > ZERO and quantity > maximum):
                raise ExchangeReject(
                    f"Quantity {quantity} is outside [{minimum}, {maximum}]"
                )

        for name, value in (("price", request.price), ("stopPrice", request.stop_price)):
            if value is None:
                continue
            if value <= ZERO or rules.tick_size <= ZERO or value % rules.tick_size != ZERO:
                raise ExchangeReject(
                    f"{name} {value} does not match tickSize {rules.tick_size}"
                )
        if request.order_type == "LIMIT" and (
            request.price is None or not request.time_in_force
        ):
            raise ExchangeReject("LIMIT requires price and timeInForce")
        if request.kind is OrderKind.ALGO and request.stop_price is None:
            raise ExchangeReject("Conditional algo order requires trigger price")

        increases_exposure = not request.reduce_only and not request.close_position
        if quantity is None or not increases_exposure:
            return
        reference = request.price or request.stop_price
        if reference is None:
            quote = await self.get_quote(symbol)
            reference = quote.ask if request.side is Side.BUY else quote.bid
        notional = quantity * reference
        if notional < rules.min_notional:
            raise ExchangeReject(
                f"Order notional {notional} is below minNotional {rules.min_notional}"
            )
        if rules.max_notional is not None and notional > rules.max_notional:
            raise ExchangeReject(
                f"Order notional {notional} exceeds maxNotional {rules.max_notional}"
            )
        await self._validate_leverage_bracket(symbol, notional)

    async def _fresh_symbol_rules(self, symbol: str) -> SymbolRules:
        data = await self._request("GET", "/fapi/v1/exchangeInfo", weight=1)
        self._cache_rules_from_exchange_info(data)
        try:
            return self._rules[symbol][1]
        except KeyError as exc:
            raise ExchangeReject(f"Unknown symbol {symbol}") from exc

    async def _validate_leverage_bracket(self, symbol: str, new_notional: D) -> None:
        cached = self._brackets.get(symbol)
        if cached and time.monotonic() - cached[0] < self.config.brackets_cache_ttl_seconds:
            brackets = cached[1]
        else:
            raw = await self._request(
                "GET",
                "/fapi/v1/leverageBracket",
                {"symbol": symbol},
                signed=True,
                credential_role="monitor",
                weight=1,
            )
            item = raw[0] if isinstance(raw, list) and raw else raw
            values = item.get("brackets", ()) if isinstance(item, Mapping) else ()
            brackets = tuple(value for value in values if isinstance(value, Mapping))
            if not brackets:
                raise ExchangeReject(f"No leverage brackets returned for {symbol}")
            self._brackets[symbol] = (time.monotonic(), brackets)

        existing_notional = sum(
            (
                position.abs_qty
                * (
                    position.mark_price
                    if position.mark_price > ZERO
                    else position.entry_price
                )
                for (position_symbol, _), position in self._positions.items()
                if position_symbol == symbol and not position.is_flat
            ),
            ZERO,
        )
        total_notional = existing_notional + new_notional
        selected: Mapping[str, Any] | None = None
        for bracket in brackets:
            floor = D(str(bracket.get("notionalFloor", "0")))
            cap = D(str(bracket.get("notionalCap", "0")))
            if total_notional >= floor and (cap <= ZERO or total_notional <= cap):
                selected = bracket
                break
        if selected is None:
            raise ExchangeReject(
                f"Total notional {total_notional} is outside leverage brackets for {symbol}"
            )
        permitted = D(str(selected.get("initialLeverage", "0")))
        actual = self._symbol_leverage.get(
            symbol, self.config.max_leverage_for_sizing
        )
        if permitted <= ZERO or actual > permitted:
            raise ExchangeReject(
                f"Leverage {actual} exceeds bracket maximum {permitted} for "
                f"notional {total_notional}"
            )

    async def _request(
        self,
        method: str,
        path: str,
        params: Mapping[str, Any] | None = None,
        *,
        signed: bool = False,
        api_key_only: bool = False,
        credential_role: str = "monitor",
        weight: int = 1,
        is_order: bool = False,
        emergency: bool = False,
    ) -> Any:
        if self._session is None or self._session.closed:
            raise RuntimeError("Adapter has not been started")
        await self._limiter.acquire(
            weight=weight, is_order=is_order, emergency=emergency
        )
        payload = {
            key: self._format_param(value)
            for key, value in (params or {}).items()
            if value is not None
        }
        headers: dict[str, str] = {}
        if signed:
            api_key, api_secret = self.config.credentials(credential_role)
            payload["timestamp"] = str(self._now_ms())
            payload["recvWindow"] = str(self.config.recv_window_ms)
            query = urlencode(payload)
            payload["signature"] = hmac.new(
                api_secret.encode(), query.encode(), hashlib.sha256
            ).hexdigest()
            headers["X-MBX-APIKEY"] = api_key
        elif api_key_only:
            api_key, _ = self.config.credentials(credential_role)
            headers["X-MBX-APIKEY"] = api_key

        url = self.config.rest_base + path
        try:
            async with self._session.request(
                method,
                url,
                params=payload if method in {"GET", "DELETE"} else None,
                data=payload if method not in {"GET", "DELETE"} else None,
                headers=headers,
            ) as response:
                text = await response.text()
                self._limiter.observe_headers(response.headers)
                try:
                    data = json.loads(text) if text else {}
                except json.JSONDecodeError:
                    data = {"msg": text}
                if response.status >= 400:
                    code = data.get("code") if isinstance(data, dict) else None
                    message = data.get("msg", text) if isinstance(data, dict) else text
                    if response.status in {418, 429}:
                        retry_after = float(response.headers.get("Retry-After", "1"))
                        await self._limiter.backoff(max(1.0, retry_after))
                    if code == -1021:
                        await self._sync_time()
                    if code == -1008:
                        await self._limiter.backoff(0.5)
                    if is_order and (response.status >= 500 or code == -1007):
                        raise AmbiguousSubmitError(
                            f"Binance returned an unknown execution result: "
                            f"HTTP {response.status} code={code}: {message}"
                        )
                    raise ExchangeReject(
                        f"Binance {response.status} code={code}: {message}",
                        code=int(code) if code is not None else None,
                    )
                return data
        except asyncio.TimeoutError:
            raise

    async def _sync_time(self) -> None:
        if self._session is None or self._session.closed:
            return
        started = int(time.time() * 1000)
        async with self._session.get(self.config.rest_base + "/fapi/v1/time") as response:
            data = await response.json()
        ended = int(time.time() * 1000)
        midpoint = (started + ended) // 2
        self._server_offset_ms = int(data["serverTime"]) - midpoint

    async def _refresh_position_mode(self) -> None:
        data = await self._request(
            "GET",
            "/fapi/v1/positionSide/dual",
            signed=True,
            credential_role="monitor",
            weight=30,
        )
        self._position_mode = (
            PositionMode.HEDGE if bool(data.get("dualSidePosition")) else PositionMode.ONEWAY
        )

    async def _refresh_positions(self) -> None:
        data = await self._request(
            "GET",
            "/fapi/v3/positionRisk",
            signed=True,
            credential_role="monitor",
            weight=5,
        )
        self._replace_positions(data)

    def _replace_positions(
        self,
        rows: Sequence[Mapping[str, Any]],
        *,
        symbol: str | None = None,
    ) -> None:
        if symbol is None:
            self._positions.clear()
            self._symbol_leverage.clear()
        else:
            normalized = symbol.upper()
            for key in [key for key in self._positions if key[0] == normalized]:
                del self._positions[key]
            self._symbol_leverage.pop(normalized, None)
        self._ingest_positions(rows)

    def _ingest_positions(self, rows: Sequence[Mapping[str, Any]]) -> None:
        now = self._now_ms()
        for row in rows:
            symbol = str(row["symbol"])
            side = str(row.get("positionSide", "BOTH"))
            quantity = D(str(row.get("positionAmt", "0")))
            if row.get("leverage") is not None:
                self._symbol_leverage[symbol] = D(str(row["leverage"]))
            self._positions[(symbol, side)] = PositionSnapshot(
                symbol=symbol,
                position_mode=self._position_mode,
                position_side=side,
                quantity=quantity,
                entry_price=D(str(row.get("entryPrice", "0"))),
                mark_price=D(str(row.get("markPrice", "0"))),
                update_time_ms=int(row.get("updateTime") or now),
            )

    def _estimate_open_risk(
        self,
        positions: Sequence[Mapping[str, Any]],
        algos: Sequence[Mapping[str, Any]],
    ) -> tuple[D, set[str]]:
        stops: dict[tuple[str, str], list[tuple[D, Side]]] = {}
        for row in algos:
            order_type = str(row.get("orderType") or row.get("type") or row.get("o") or "")
            status = str(
                row.get("algoStatus")
                or row.get("status")
                or row.get("X")
                or ""
            )
            if order_type != "STOP_MARKET" or status not in {
                "NEW",
                "TRIGGERING",
                "TRIGGERED",
            }:
                continue
            symbol = str(row.get("symbol") or row.get("s") or "")
            position_side = str(row.get("positionSide") or row.get("ps") or "BOTH")
            trigger = D(
                str(
                    row.get("triggerPrice")
                    or row.get("stopPrice")
                    or row.get("tp")
                    or "0"
                )
            )
            side_raw = str(row.get("side") or row.get("S") or "")
            try:
                order_side = Side(side_raw)
            except ValueError:
                continue
            if trigger > ZERO:
                stops.setdefault((symbol, position_side), []).append(
                    (trigger, order_side)
                )

        risk = ZERO
        unprotected: set[str] = set()
        for row in positions:
            qty = D(str(row.get("positionAmt", "0")))
            if qty == ZERO:
                continue
            symbol = str(row["symbol"])
            position_side = str(row.get("positionSide", "BOTH"))
            entry = D(str(row.get("entryPrice", "0")))
            mark = D(str(row.get("markPrice", "0")))
            reference = mark if mark > ZERO else entry
            candidates = stops.get((symbol, position_side), [])
            if not candidates and position_side == "BOTH":
                candidates = stops.get((symbol, "BOTH"), [])
            valid: list[D] = []
            for stop, order_side in candidates:
                if qty > ZERO and order_side is Side.SELL and stop < reference:
                    valid.append(stop)
                elif qty < ZERO and order_side is Side.BUY and stop > reference:
                    valid.append(stop)
            if valid:
                nearest = max(valid) if qty > ZERO else min(valid)
                risk += abs(qty) * abs(reference - nearest)
            else:
                unprotected.add(f"{symbol}:{position_side}")
                risk += abs(qty) * max(mark, entry)
        return risk, unprotected

    def _cache_rules_from_exchange_info(self, data: Mapping[str, Any]) -> None:
        now = time.monotonic()
        for item in data.get("symbols", []):
            symbol = item["symbol"]
            filters = {f["filterType"]: f for f in item.get("filters", [])}
            price_filter = filters.get("PRICE_FILTER", {})
            lot = filters.get("LOT_SIZE", {})
            market_lot = filters.get("MARKET_LOT_SIZE", {})
            notional = filters.get("MIN_NOTIONAL") or filters.get("NOTIONAL") or {}
            rules = SymbolRules(
                symbol=symbol,
                tick_size=D(str(price_filter.get("tickSize", "0"))),
                step_size=D(str(lot.get("stepSize", "0"))),
                min_qty=D(str(lot.get("minQty", "0"))),
                max_qty=D(str(lot.get("maxQty", "0"))),
                min_notional=D(
                    str(notional.get("notional") or notional.get("minNotional") or "0")
                ),
                max_notional=(
                    None
                    if notional.get("maxNotional") in {None, "0", 0}
                    else D(str(notional["maxNotional"]))
                ),
                status=str(item.get("status", "")),
                market_step_size=(
                    None
                    if market_lot.get("stepSize") in {None, "0", 0}
                    else D(str(market_lot["stepSize"]))
                ),
                market_min_qty=(
                    None
                    if market_lot.get("minQty") is None
                    else D(str(market_lot["minQty"]))
                ),
                market_max_qty=(
                    None
                    if market_lot.get("maxQty") is None
                    else D(str(market_lot["maxQty"]))
                ),
            )
            self._rules[symbol] = (now, rules)

    def _order_params(self, request: OrderRequest) -> dict[str, Any]:
        params: dict[str, Any] = {
            "symbol": request.symbol,
            "side": request.side.value,
            "positionSide": request.position_side,
            "quantity": request.quantity,
            "price": request.price,
            "timeInForce": request.time_in_force,
            "workingType": request.working_type,
            "priceProtect": request.price_protect,
            "reduceOnly": request.reduce_only if request.reduce_only else None,
            "closePosition": request.close_position if request.close_position else None,
            "selfTradePreventionMode": request.self_trade_prevention_mode,
        }
        if request.kind is OrderKind.ALGO:
            params.update(
                {
                    "algoType": "CONDITIONAL",
                    "type": request.order_type,
                    "triggerPrice": request.stop_price,
                    "clientAlgoId": request.client_order_id,
                }
            )
        else:
            params.update(
                {
                    "type": request.order_type,
                    "stopPrice": request.stop_price,
                    "newClientOrderId": request.client_order_id,
                    "newOrderRespType": "RESULT"
                    if request.time_in_force == "IOC" or request.order_type == "MARKET"
                    else "ACK",
                }
            )
        return params

    def _parse_order(
        self,
        data: Mapping[str, Any],
        kind: OrderKind,
        fallback: OrderRequest | None = None,
    ) -> OrderSnapshot:
        if kind is OrderKind.ALGO:
            status_raw = str(data.get("algoStatus") or data.get("status") or data.get("X") or "UNKNOWN")
            client = str(
                data.get("clientAlgoId")
                or data.get("caid")
                or (fallback.client_order_id if fallback else "")
            )
            order_id = str(data.get("algoId") or data.get("aid") or "")
            order_type = str(data.get("orderType") or data.get("type") or data.get("o") or "")
            orig_qty = D(str(data.get("quantity") or data.get("q") or "0"))
            executed = D(
                str(data.get("executedQty") or data.get("actualQty") or data.get("aq") or data.get("z") or "0")
            )
            avg = D(str(data.get("avgPrice") or data.get("ap") or "0"))
            cumulative = D(
                str(data.get("cumQuote") or data.get("cumulativeQuoteQty") or "0")
            )
            stop_price = D(
                str(data.get("triggerPrice") or data.get("stopPrice") or data.get("tp") or "0")
            )
            price = D(str(data.get("price") or data.get("p") or "0"))
        else:
            status_raw = str(data.get("status") or data.get("X") or "UNKNOWN")
            client = str(
                data.get("clientOrderId")
                or data.get("c")
                or (fallback.client_order_id if fallback else "")
            )
            order_id = str(data.get("orderId") or data.get("i") or "")
            order_type = str(data.get("type") or data.get("o") or "")
            orig_qty = D(
                str(data.get("origQty") or data.get("q") or (fallback.quantity if fallback else "0") or "0")
            )
            executed = D(str(data.get("executedQty") or data.get("z") or "0"))
            avg = D(str(data.get("avgPrice") or data.get("ap") or "0"))
            cumulative = D(str(data.get("cumQuote") or data.get("cumQuoteQty") or "0"))
            stop_price = D(str(data.get("stopPrice") or data.get("sp") or "0"))
            price = D(str(data.get("price") or data.get("p") or "0"))
        status = self._normalize_status(status_raw)
        if cumulative <= ZERO and avg > ZERO and executed > ZERO:
            cumulative = avg * executed
        side_raw = str(data.get("side") or data.get("S") or (fallback.side.value if fallback else "BUY"))
        return OrderSnapshot(
            order_id=order_id,
            client_order_id=client,
            symbol=str(data.get("symbol") or data.get("s") or (fallback.symbol if fallback else "")),
            side=Side(side_raw),
            status=status,
            orig_qty=orig_qty,
            executed_qty=executed,
            cumulative_quote_qty=cumulative,
            avg_price=avg,
            update_time_ms=int(
                data.get("updateTime")
                or data.get("time")
                or data.get("T")
                or self._now_ms()
            ),
            kind=kind,
            order_type=order_type,
            price=price,
            stop_price=stop_price,
        )

    @staticmethod
    def _normalize_status(value: str) -> OrderStatus:
        aliases = {
            "CANCELLED": "CANCELED",
            "PENDING_NEW": "NEW",
            "ACTIVE": "NEW",
        }
        normalized = aliases.get(value.upper(), value.upper())
        try:
            return OrderStatus(normalized)
        except ValueError:
            return OrderStatus.UNKNOWN

    async def _publish_order(self, snapshot: OrderSnapshot) -> None:
        if not snapshot.client_order_id:
            return
        key = (snapshot.kind, snapshot.client_order_id)
        async with self._order_condition:
            existing = self._order_cache.get(key)
            if existing:
                if snapshot.update_time_ms < existing.update_time_ms:
                    return
                if snapshot.executed_qty < existing.executed_qty:
                    return
                if (
                    snapshot.update_time_ms == existing.update_time_ms
                    and existing.status.terminal
                    and not snapshot.status.terminal
                ):
                    return
                snapshot = replace(
                    snapshot,
                    order_id=snapshot.order_id or existing.order_id,
                    symbol=snapshot.symbol or existing.symbol,
                    orig_qty=max(snapshot.orig_qty, existing.orig_qty),
                    cumulative_quote_qty=max(
                        snapshot.cumulative_quote_qty,
                        existing.cumulative_quote_qty,
                    ),
                    avg_price=(
                        snapshot.avg_price
                        if snapshot.avg_price > ZERO
                        else existing.avg_price
                    ),
                    order_type=snapshot.order_type or existing.order_type,
                    price=snapshot.price if snapshot.price > ZERO else existing.price,
                    stop_price=(
                        snapshot.stop_price
                        if snapshot.stop_price > ZERO
                        else existing.stop_price
                    ),
                )
            self._order_cache[key] = snapshot
            self._order_versions[key] = self._order_versions.get(key, 0) + 1
            self._order_condition.notify_all()

    def _ingest_book_ticker(self, data: Mapping[str, Any]) -> bool:
        stream_type = data.get("st")
        if stream_type is not None and str(stream_type) != "1":
            return False
        symbol = str(data.get("s") or "").upper()
        if not symbol or (self._symbols and symbol not in self._symbols):
            return False
        try:
            bid = D(str(data["b"]))
            ask = D(str(data["a"]))
            bid_qty = D(str(data["B"]))
            ask_qty = D(str(data["A"]))
            sequence_id = int(data["u"]) if data.get("u") is not None else None
        except (KeyError, ValueError, TypeError):
            return False
        if bid <= ZERO or ask <= bid or bid_qty < ZERO or ask_qty < ZERO:
            return False
        existing = self._quotes.get(symbol)
        if (
            existing
            and existing.sequence_id is not None
            and sequence_id is not None
            and sequence_id <= existing.sequence_id
        ):
            return False
        exchange_time_ms = int(data.get("T") or data.get("E") or self._now_ms())
        self._quotes[symbol] = Quote(
            symbol=symbol,
            bid=bid,
            ask=ask,
            bid_qty=bid_qty,
            ask_qty=ask_qty,
            exchange_time_ms=exchange_time_ms,
            sequence_id=sequence_id,
            exchange_age_at_receive_ms=self._now_ms() - exchange_time_ms,
        )
        return True

    async def _public_stream_loop(self) -> None:
        backoff = 1.0
        while not self._closing.is_set():
            if not self._symbols:
                await asyncio.sleep(0.2)
                continue
            streams = "/".join(
                f"{symbol.lower()}@bookTicker" for symbol in sorted(self._symbols)
            )
            url = f"{self.config.public_ws}/stream?streams={streams}"
            try:
                async with websockets.connect(
                    url, ping_interval=20, ping_timeout=20, close_timeout=5
                ) as ws:
                    self._market_stream_connected = True
                    backoff = 1.0
                    async for raw in ws:
                        payload = json.loads(raw)
                        data = payload.get("data", payload)
                        if data.get("e") != "bookTicker":
                            continue
                        self._ingest_book_ticker(data)
            except asyncio.CancelledError:
                raise
            except Exception:
                self._market_stream_connected = False
                log.exception("Public bookTicker stream disconnected")
                await asyncio.sleep(backoff + random.random() * 0.2)
                backoff = min(30.0, backoff * 2)

    def _private_stream_url(self, listen_key: str) -> str:
        events = "/".join(
            (
                "ORDER_TRADE_UPDATE",
                "ACCOUNT_UPDATE",
                "ACCOUNT_CONFIG_UPDATE",
                "ALGO_UPDATE",
                "MARGIN_CALL",
            )
        )
        query = urlencode({"listenKey": listen_key, "events": events}, safe="/")
        return f"{self.config.user_ws}/ws?{query}"

    async def _user_stream_loop(self) -> None:
        backoff = 1.0
        while not self._closing.is_set():
            keepalive_task: asyncio.Task[Any] | None = None
            try:
                listen = await self._request(
                    "POST",
                    "/fapi/v1/listenKey",
                    api_key_only=True,
                    credential_role="user_data",
                    weight=1,
                )
                self._listen_key = str(listen["listenKey"])
                url = self._private_stream_url(self._listen_key)
                keepalive_task = asyncio.create_task(self._listen_key_keepalive())
                async with websockets.connect(
                    url, ping_interval=20, ping_timeout=20, close_timeout=5
                ) as ws:
                    self._user_stream_connected = True
                    backoff = 1.0
                    async for raw in ws:
                        data = json.loads(raw)
                        await self._handle_user_event(data)
            except asyncio.CancelledError:
                raise
            except Exception:
                self._user_stream_connected = False
                log.exception("Private user stream disconnected")
                await asyncio.sleep(backoff + random.random() * 0.2)
                backoff = min(30.0, backoff * 2)
            finally:
                self._user_stream_connected = False
                if keepalive_task:
                    keepalive_task.cancel()
                    await asyncio.gather(keepalive_task, return_exceptions=True)

    async def _listen_key_keepalive(self) -> None:
        while not self._closing.is_set() and self._listen_key:
            await asyncio.sleep(self.config.listen_key_keepalive_seconds)
            await self._request(
                "PUT",
                "/fapi/v1/listenKey",
                api_key_only=True,
                credential_role="user_data",
                weight=1,
            )

    async def _handle_user_event(self, data: Mapping[str, Any]) -> None:
        event = data.get("e")
        if event == "ORDER_TRADE_UPDATE":
            order = dict(data.get("o", {}))
            order.setdefault("updateTime", data.get("T") or data.get("E"))
            await self._publish_order(self._parse_order(order, OrderKind.REGULAR))
            self._account_cache = None
        elif event == "ALGO_UPDATE":
            order = dict(data.get("ao") or data.get("o") or data)
            order.setdefault("updateTime", data.get("T") or data.get("E"))
            snapshot = self._parse_order(order, OrderKind.ALGO)
            if snapshot.client_order_id:
                await self._publish_order(snapshot)
            self._account_cache = None
        elif event == "ACCOUNT_UPDATE":
            account = data.get("a", {})
            rows = []
            for position in account.get("P", []):
                rows.append(
                    {
                        "symbol": position.get("s"),
                        "positionSide": position.get("ps", "BOTH"),
                        "positionAmt": position.get("pa", "0"),
                        "entryPrice": position.get("ep", "0"),
                        "markPrice": "0",
                        "updateTime": data.get("E", self._now_ms()),
                    }
                )
            self._ingest_positions(rows)
            self._account_cache = None
        elif event == "ACCOUNT_CONFIG_UPDATE":
            self._account_cache = None
            await self._refresh_position_mode()
        elif event == "MARGIN_CALL":
            self._account_cache = None
        elif event == "listenKeyExpired":
            self._user_stream_connected = False
            raise ConnectionError("Binance listenKey expired")

    def _now_ms(self) -> int:
        return int(time.time() * 1000) + self._server_offset_ms

    @staticmethod
    def _format_param(value: Any) -> str:
        if isinstance(value, Decimal):
            return format(value, "f")
        if isinstance(value, bool):
            return "true" if value else "false"
        return str(value)
