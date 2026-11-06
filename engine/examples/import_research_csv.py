from __future__ import annotations

import argparse
import asyncio
import json

from smc_execution.research_data import load_csv_dataset
from smc_execution.research_store import ResearchStore


async def run(args) -> None:
    dataset = load_csv_dataset(
        args.csv,
        dataset_id=args.dataset_id,
        symbol=args.symbol,
        interval=args.interval,
        strict=not args.allow_gaps,
    )
    store = ResearchStore(args.db)
    await store.initialize()
    await store.save_dataset(dataset)
    print(json.dumps(dataset.to_dict(), ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description="Import research OHLCV CSV")
    parser.add_argument("csv")
    parser.add_argument("--db", default="./var/research.db")
    parser.add_argument("--dataset-id")
    parser.add_argument("--symbol")
    parser.add_argument("--interval")
    parser.add_argument("--allow-gaps", action="store_true")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
