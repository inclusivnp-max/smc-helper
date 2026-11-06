#!/usr/bin/env python3
"""
Print agg_trade ticks from the local SMC data lake as:
aggregate_id,time,price,quantity,buyer_maker

Used by Java only when Parquet files exist and no native csv.gz data was found.
Requires pyarrow for Parquet reading.
"""

import argparse
import csv
import gzip
from datetime import datetime, timezone
from pathlib import Path


def day(ts_ms):
    return datetime.fromtimestamp(int(ts_ms) / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def iter_days(start_ms, end_ms):
    start = datetime.fromtimestamp(int(start_ms) / 1000, tz=timezone.utc).date()
    end = datetime.fromtimestamp(int(end_ms) / 1000, tz=timezone.utc).date()
    current = start
    while current <= end:
        yield current.isoformat()
        current = current.fromordinal(current.toordinal() + 1)


def emit_row(row, start_ms, end_ms):
    t = int(row.get("time", 0))
    if t < start_ms or t > end_ms:
        return
    print(
        f"{int(row.get('aggregate_id', row.get('aggregateId', row.get('id', 0))))},"
        f"{t},"
        f"{float(row.get('price', 0.0))},"
        f"{float(row.get('quantity', row.get('qty', 0.0)))},"
        f"{str(row.get('buyer_maker', row.get('buyerMaker', False))).lower()}"
    )


def read_csv_gz(path, start_ms, end_ms):
    with gzip.open(path, "rt", encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            emit_row(row, start_ms, end_ms)


def read_parquet(path, start_ms, end_ms):
    import pyarrow.parquet as pq
    table = pq.read_table(path)
    for row in table.to_pylist():
        emit_row(row, start_ms, end_ms)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--market", default="FUTURES")
    parser.add_argument("--start", type=int, required=True)
    parser.add_argument("--end", type=int, required=True)
    args = parser.parse_args()

    root = Path(args.root)
    for d in iter_days(args.start, args.end):
        part = root / "AGG_TRADES" / f"market={args.market}" / f"symbol={args.symbol}" / f"date={d}"
        if not part.exists():
            part = root / "agg_trades" / f"market={args.market}" / f"symbol={args.symbol}" / f"date={d}"
        if not part.exists():
            continue
        for path in sorted(part.glob("*.csv.gz")):
            read_csv_gz(path, args.start, args.end)
        for path in sorted(part.glob("*.parquet")):
            read_parquet(path, args.start, args.end)


if __name__ == "__main__":
    main()
