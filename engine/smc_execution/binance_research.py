from __future__ import annotations

import asyncio
import json
import time
from typing import Any
from urllib.parse import urlencode

import aiohttp

from .models import D, ZERO
from .research_data import ResearchDataset, validate_bars
from .research_models import ResearchBar, dataset_fingerprint


INTERVAL_MS = {
    "1m": 60_000,
    "3m": 180_000,
    "5m": 300_000,
    "15m": 900_000,
    "30m": 1_800_000,
    "1h": 3_600_000,
    "2h": 7_200_000,
    "4h": 14_400_000,
    "6h": 21_600_000,
    "8h": 28_800_000,
    "12h": 43_200_000,
    "1d": 86_400_000,
}

OI_PERIOD_BY_INTERVAL = {
    "1m": "5m",
    "3m": "5m",
    "5m": "5m",
    "15m": "15m",
    "30m": "30m",
    "1h": "1h",
    "2h": "2h",
    "4h": "4h",
    "6h": "6h",
    "8h": "6h",
    "12h": "12h",
    "1d": "1d",
}

# Binance's public open-interest history endpoint only accepts a bounded
# recent window. Older candles must keep OI missing rather than failing the
# entire immutable dataset download.
# Keep one day of margin because the venue may reject a range starting
# exactly at its advertised 30-day boundary.
OPEN_INTEREST_LOOKBACK_MS = 29 * 86_400_000


class BinanceResearchDataClient:
    """Public-data downloader for reproducible research datasets.

    It intentionally uses public market-data endpoints and never accepts API
    secrets. Historical open-interest availability is venue-limited; missing OI
    is preserved as missing rather than silently forward-filled.
    """

    def __init__(
        self,
        *,
        base_url: str = "https://fapi.binance.com",
        request_timeout_seconds: float = 20.0,
        min_request_interval_seconds: float = 0.05,
        max_retries: int = 5,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.request_timeout_seconds = request_timeout_seconds
        self.min_request_interval_seconds = min_request_interval_seconds
        self.max_retries = max(0, int(max_retries))
        self._session: aiohttp.ClientSession | None = None
        self._last_request = 0.0
        self._lock = asyncio.Lock()

    async def __aenter__(self) -> "BinanceResearchDataClient":
        timeout = aiohttp.ClientTimeout(total=self.request_timeout_seconds)
        self._session = aiohttp.ClientSession(timeout=timeout)
        return self

    async def __aexit__(self, *_: Any) -> None:
        if self._session is not None:
            await self._session.close()
            self._session = None

    async def get_recent_bars(
        self,
        symbol: str,
        interval: str,
        *,
        limit: int = 500,
    ) -> tuple[ResearchBar, ...]:
        if interval not in INTERVAL_MS:
            raise ValueError(f"Unsupported research interval: {interval}")
        bounded = max(2, min(int(limit), 1500))
        server = await self._get_json("/fapi/v1/time", {})
        server_time = int(server["serverTime"])
        rows = await self._get_json(
            "/fapi/v1/klines",
            {"symbol": symbol.upper(), "interval": interval, "limit": bounded},
        )
        result: list[ResearchBar] = []
        for row in rows:
            if int(row[6]) >= server_time:
                continue
            result.append(
                ResearchBar(
                    symbol=symbol.upper(),
                    interval=interval,
                    open_time_ms=int(row[0]),
                    close_time_ms=int(row[6]),
                    open=D(str(row[1])),
                    high=D(str(row[2])),
                    low=D(str(row[3])),
                    close=D(str(row[4])),
                    volume=D(str(row[5])),
                    quote_volume=D(str(row[7])),
                    trades=int(row[8]),
                    taker_buy_base=D(str(row[9])),
                    taker_buy_quote=D(str(row[10])),
                )
            )
        return tuple(result)

    async def download_dataset(
        self,
        *,
        symbol: str,
        interval: str,
        start_time_ms: int,
        end_time_ms: int,
        include_mark_price: bool = True,
        include_funding: bool = True,
        include_open_interest: bool = True,
        dataset_id: str | None = None,
    ) -> ResearchDataset:
        symbol = symbol.upper()
        if interval not in INTERVAL_MS:
            raise ValueError(f"Unsupported research interval: {interval}")
        if end_time_ms <= start_time_ms:
            raise ValueError("end_time_ms must be after start_time_ms")
        server = await self._get_json("/fapi/v1/time", {})
        server_time_ms = int(server["serverTime"])
        effective_end_ms = min(end_time_ms, server_time_ms - 1)
        if effective_end_ms <= start_time_ms:
            raise ValueError("Requested range contains no closed candles")
        klines_task = self._paginate_klines(
            "/fapi/v1/klines",
            {"symbol": symbol, "interval": interval},
            start_time_ms,
            effective_end_ms,
        )
        mark_task = (
            self._paginate_klines(
                "/fapi/v1/markPriceKlines",
                {"symbol": symbol, "interval": interval},
                start_time_ms,
                effective_end_ms,
            )
            if include_mark_price
            else asyncio.sleep(0, result=[])
        )
        funding_task = (
            self._paginate_simple(
                "/fapi/v1/fundingRate",
                {"symbol": symbol},
                start_time_ms,
                effective_end_ms,
                limit=1000,
                timestamp_key="fundingTime",
            )
            if include_funding
            else asyncio.sleep(0, result=[])
        )
        oi_start_ms = max(
            start_time_ms,
            server_time_ms - OPEN_INTEREST_LOOKBACK_MS,
        )
        oi_task = (
            self._paginate_simple(
                "/futures/data/openInterestHist",
                {"symbol": symbol, "period": OI_PERIOD_BY_INTERVAL[interval]},
                oi_start_ms,
                effective_end_ms,
                limit=500,
                timestamp_key="timestamp",
            )
            if include_open_interest and oi_start_ms <= effective_end_ms
            else asyncio.sleep(0, result=[])
        )
        raw_klines, mark_rows, funding_rows, oi_rows = await asyncio.gather(
            klines_task, mark_task, funding_task, oi_task
        )
        mark_by_open = {int(row[0]): D(str(row[4])) for row in mark_rows}
        oi_by_timestamp = {
            int(row["timestamp"]): D(str(row.get("sumOpenInterestValue") or row.get("sumOpenInterest") or "0"))
            for row in oi_rows
        }
        funding_by_time = {
            int(row["fundingTime"]): (
                D(str(row["fundingRate"])),
                D(str(row.get("markPrice") or "0")),
            )
            for row in funding_rows
        }

        bars: list[ResearchBar] = []
        sorted_oi = sorted(oi_by_timestamp.items())
        oi_index = 0
        latest_oi: tuple[int, D] | None = None
        sorted_funding = sorted(funding_by_time.items())
        funding_index = 0
        for row in raw_klines:
            open_time = int(row[0])
            close_time = int(row[6])
            if close_time >= server_time_ms or close_time > effective_end_ms:
                continue
            while oi_index < len(sorted_oi) and sorted_oi[oi_index][0] <= close_time:
                latest_oi = sorted_oi[oi_index]
                oi_index += 1
            funding_rate = None
            funding_mark = None
            funding_available = None
            while (
                funding_index < len(sorted_funding)
                and sorted_funding[funding_index][0] < open_time
            ):
                funding_index += 1
            if (
                funding_index < len(sorted_funding)
                and open_time <= sorted_funding[funding_index][0] <= close_time
            ):
                funding_available, (funding_rate, funding_mark) = sorted_funding[funding_index]
                funding_index += 1
            bars.append(
                ResearchBar(
                    symbol=symbol,
                    interval=interval,
                    open_time_ms=open_time,
                    close_time_ms=close_time,
                    open=D(str(row[1])),
                    high=D(str(row[2])),
                    low=D(str(row[3])),
                    close=D(str(row[4])),
                    volume=D(str(row[5])),
                    quote_volume=D(str(row[7])),
                    trades=int(row[8]),
                    taker_buy_base=D(str(row[9])),
                    taker_buy_quote=D(str(row[10])),
                    mark_close=(
                        mark_by_open.get(open_time)
                        or (funding_mark if funding_mark and funding_mark > ZERO else None)
                    ),
                    open_interest=None if latest_oi is None else latest_oi[1],
                    funding_rate=funding_rate,
                    open_interest_available_at_ms=(
                        None if latest_oi is None else latest_oi[0]
                    ),
                    funding_available_at_ms=funding_available,
                )
            )
        if not bars:
            raise ValueError("Binance returned no closed candles for the requested range")
        quality = validate_bars(bars)
        fingerprint = dataset_fingerprint(bars)
        return ResearchDataset(
            dataset_id=dataset_id or f"{symbol}-{interval}-{fingerprint[:12]}",
            symbol=symbol,
            interval=interval,
            bars=tuple(bars),
            fingerprint=fingerprint,
            quality=quality,
            source=f"{self.base_url}:{start_time_ms}-{effective_end_ms}",
        )

    async def _paginate_klines(
        self,
        path: str,
        params: dict[str, Any],
        start_time_ms: int,
        end_time_ms: int,
    ) -> list[list[Any]]:
        interval_ms = INTERVAL_MS[str(params["interval"])]
        cursor = start_time_ms
        result: list[list[Any]] = []
        while cursor <= end_time_ms:
            page = await self._get_json(
                path,
                {
                    **params,
                    "startTime": cursor,
                    "endTime": end_time_ms,
                    "limit": 1500,
                },
            )
            if not page:
                break
            result.extend(page)
            next_cursor = int(page[-1][0]) + interval_ms
            if next_cursor <= cursor:
                break
            cursor = next_cursor
            if len(page) < 1500:
                break
        deduplicated = {int(row[0]): row for row in result}
        return [deduplicated[key] for key in sorted(deduplicated)]

    async def _paginate_simple(
        self,
        path: str,
        params: dict[str, Any],
        start_time_ms: int,
        end_time_ms: int,
        *,
        limit: int,
        timestamp_key: str,
    ) -> list[dict[str, Any]]:
        cursor = start_time_ms
        result: list[dict[str, Any]] = []
        while cursor <= end_time_ms:
            page = await self._get_json(
                path,
                {
                    **params,
                    "startTime": cursor,
                    "endTime": end_time_ms,
                    "limit": limit,
                },
            )
            if not page:
                break
            result.extend(page)
            last = int(page[-1][timestamp_key])
            if last < cursor:
                break
            cursor = last + 1
            if len(page) < limit:
                break
        deduplicated = {int(row[timestamp_key]): row for row in result}
        return [deduplicated[key] for key in sorted(deduplicated)]

    async def _get_json(self, path: str, params: dict[str, Any]):
        if self._session is None:
            raise RuntimeError("Use BinanceResearchDataClient as an async context manager")
        async with self._lock:
            for attempt in range(self.max_retries + 1):
                elapsed = time.monotonic() - self._last_request
                if elapsed < self.min_request_interval_seconds:
                    await asyncio.sleep(self.min_request_interval_seconds - elapsed)
                url = f"{self.base_url}{path}?{urlencode(params)}"
                try:
                    async with self._session.get(url) as response:
                        body = await response.text()
                        self._last_request = time.monotonic()
                        if response.status < 400:
                            return json.loads(body)
                        retryable = response.status == 429 or 500 <= response.status < 600
                        if not retryable or attempt >= self.max_retries:
                            raise RuntimeError(
                                f"Binance market-data request failed "
                                f"{response.status}: {body[:500]}"
                            )
                        retry_after = response.headers.get("Retry-After")
                        delay = (
                            min(60.0, float(retry_after))
                            if retry_after
                            else min(30.0, 0.5 * (2**attempt))
                        )
                except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
                    if attempt >= self.max_retries:
                        raise RuntimeError(
                            f"Binance market-data transport failed: {exc!r}"
                        ) from exc
                    delay = min(30.0, 0.5 * (2**attempt))
                await asyncio.sleep(delay)
        raise RuntimeError("unreachable market-data retry state")
