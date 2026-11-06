from __future__ import annotations

import argparse
import asyncio
import json
from datetime import datetime, timezone

from smc_execution.binance_research import BinanceResearchDataClient
from smc_execution.research_data import write_csv_dataset
from smc_execution.research_store import ResearchStore


def parse_time(value: str) -> int:
    if value.isdigit():
        return int(value)
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


async def run(args) -> None:
    async with BinanceResearchDataClient() as client:
        dataset = await client.download_dataset(
            symbol=args.symbol,
            interval=args.interval,
            start_time_ms=parse_time(args.start),
            end_time_ms=parse_time(args.end),
            include_mark_price=not args.no_mark,
            include_funding=not args.no_funding,
            include_open_interest=not args.no_oi,
            dataset_id=args.dataset_id,
        )
    store = ResearchStore(args.db)
    await store.initialize()
    await store.save_dataset(dataset)
    if args.csv:
        write_csv_dataset(args.csv, dataset.bars)
    print(json.dumps(dataset.to_dict(), ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description="Download Binance USD-M research data")
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--interval", default="5m")
    parser.add_argument("--start", required=True, help="ISO-8601 UTC or unix milliseconds")
    parser.add_argument("--end", required=True, help="ISO-8601 UTC or unix milliseconds")
    parser.add_argument("--db", default="./var/research.db")
    parser.add_argument("--csv")
    parser.add_argument("--dataset-id")
    parser.add_argument("--no-mark", action="store_true")
    parser.add_argument("--no-funding", action="store_true")
    parser.add_argument("--no-oi", action="store_true")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
