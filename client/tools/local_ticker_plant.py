#!/usr/bin/env python3
"""
Local ticker plant for SMC Chart.

Collects Binance Futures aggTrades, order-book depth snapshots, open interest,
and funding into a local partitioned data lake:

  <project>/data-lake/{dataset}/market=FUTURES/symbol=BTCUSDT/date=YYYY-MM-DD/

Output format:
  - Parquet when pyarrow is installed
  - csv.gz fallback with the same schema when pyarrow is absent

Examples:
  python tools/local_ticker_plant.py --symbols BTCUSDT,ETHUSDT --seconds 300
  python tools/local_ticker_plant.py --symbols SOLUSDT --root D:/smc-data --depth-interval 2
"""

import argparse
import csv
import gzip
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

try:
    import pyarrow as pa
    import pyarrow.parquet as pq
except Exception:
    pa = None
    pq = None


BASE = "https://fapi.binance.com"
PROJECT_ROOT = Path(__file__).resolve().parents[1]


def utc_day(ts_ms):
    return datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def request_json(path, params):
    url = BASE + path + "?" + urlencode(params)
    with urlopen(url, timeout=8) as response:
        return json.loads(response.read().decode("utf-8"))


def partition(root, dataset, symbol, ts_ms):
    path = Path(root) / dataset / "market=FUTURES" / f"symbol={symbol}" / f"date={utc_day(ts_ms)}"
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_rows(root, dataset, symbol, rows):
    if not rows:
        return
    ts_ms = int(rows[-1]["time"])
    out = partition(root, dataset, symbol, ts_ms)
    stamp = datetime.now(timezone.utc).strftime("%H%M%S_%f")
    if pa and pq:
        table = pa.Table.from_pylist(rows)
        pq.write_table(table, out / f"{dataset}_{stamp}.parquet", compression="zstd")
        return

    keys = list(rows[0].keys())
    with gzip.open(out / f"{dataset}_{stamp}.csv.gz", "wt", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)


def collect_trades(symbol, last_id, limit):
    params = {"symbol": symbol, "limit": limit}
    if last_id is not None:
        params["fromId"] = last_id + 1
    data = request_json("/fapi/v1/aggTrades", params)
    rows = []
    next_id = last_id
    for item in data:
        next_id = max(next_id or 0, int(item["a"]))
        rows.append({
            "aggregate_id": int(item["a"]),
            "time": int(item["T"]),
            "price": float(item["p"]),
            "quantity": float(item["q"]),
            "buyer_maker": bool(item["m"]),
        })
    return rows, next_id


def collect_depth(symbol, limit):
    now = int(time.time() * 1000)
    data = request_json("/fapi/v1/depth", {"symbol": symbol, "limit": limit})
    rows = []
    for side, levels in (("bid", data.get("bids", [])), ("ask", data.get("asks", []))):
        for rank, level in enumerate(levels, start=1):
            price = float(level[0])
            qty = float(level[1])
            rows.append({
                "time": now,
                "side": side,
                "rank": rank,
                "price": price,
                "quantity": qty,
                "notional": price * qty,
            })
    return rows


def collect_oi_funding(symbol):
    now = int(time.time() * 1000)
    oi = request_json("/fapi/v1/openInterest", {"symbol": symbol})
    premium = request_json("/fapi/v1/premiumIndex", {"symbol": symbol})
    return [{
        "time": now,
        "open_interest": float(oi.get("openInterest", 0.0)),
        "mark_price": float(premium.get("markPrice", 0.0)),
        "index_price": float(premium.get("indexPrice", 0.0)),
        "funding_rate": float(premium.get("lastFundingRate", 0.0)),
        "next_funding_time": int(premium.get("nextFundingTime", 0) or 0),
    }]


def run(args):
    symbols = [s.strip().upper() for s in args.symbols.split(",") if s.strip()]
    root = Path(args.root).expanduser()
    last_ids = {symbol: None for symbol in symbols}
    started = time.time()
    next_depth = 0.0
    next_oi = 0.0

    while True:
        if args.seconds > 0 and time.time() - started >= args.seconds:
            break
        for symbol in symbols:
            try:
                trades, last_ids[symbol] = collect_trades(symbol, last_ids[symbol], args.trade_limit)
                write_rows(root, "agg_trades", symbol, trades)
                if time.time() >= next_depth:
                    write_rows(root, "depth_l2", symbol, collect_depth(symbol, args.depth_limit))
                if time.time() >= next_oi:
                    write_rows(root, "derivatives", symbol, collect_oi_funding(symbol))
            except Exception as exc:
                print(f"{symbol}: {exc}")
        if time.time() >= next_depth:
            next_depth = time.time() + args.depth_interval
        if time.time() >= next_oi:
            next_oi = time.time() + args.derivatives_interval
        time.sleep(args.poll_interval)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbols", required=True, help="Comma-separated symbols, e.g. BTCUSDT,ETHUSDT")
    parser.add_argument("--root", default=os.environ.get("SMC_DATA_LAKE_ROOT", str(PROJECT_ROOT / "data-lake")))
    parser.add_argument("--seconds", type=int, default=0, help="0 means run forever")
    parser.add_argument("--poll-interval", type=float, default=1.0)
    parser.add_argument("--trade-limit", type=int, default=1000)
    parser.add_argument("--depth-limit", type=int, default=100)
    parser.add_argument("--depth-interval", type=float, default=2.0)
    parser.add_argument("--derivatives-interval", type=float, default=30.0)
    args = parser.parse_args()
    print(f"Ticker plant writing to {Path(args.root).expanduser()}")
    print("Parquet enabled" if pa and pq else "pyarrow not found; using csv.gz fallback")
    run(args)


if __name__ == "__main__":
    main()
