from __future__ import annotations

import argparse
import asyncio
import json

from smc_execution.modes import TradingMode
from smc_execution.research_service import adaptive_walk_forward, simulation_for_mode
from smc_execution.research_store import ResearchStore
from smc_execution.strategy import SMCCompositeStrategy
from smc_execution.validation import ResearchRunner


async def run(args) -> None:
    store = ResearchStore(args.db)
    await store.initialize()
    dataset = await store.get_dataset(args.dataset_id)
    if dataset is None:
        raise SystemExit(f"Dataset not found: {args.dataset_id}")
    mode = TradingMode(args.mode)
    report = await asyncio.to_thread(
        ResearchRunner(
            simulation=simulation_for_mode(mode),
            walk_forward=adaptive_walk_forward(len(dataset.bars)),
            bootstrap_samples=args.bootstrap_samples,
        ).run,
        dataset,
        SMCCompositeStrategy(),
    )
    await store.save_report(report)
    print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description="Run SMC walk-forward research")
    parser.add_argument("dataset_id")
    parser.add_argument("--db", default="./var/research.db")
    parser.add_argument(
        "--mode",
        choices=[
            TradingMode.CAPITAL_PROTECTION.value,
            TradingMode.BALANCED.value,
            TradingMode.PROFITABILITY.value,
        ],
        default=TradingMode.CAPITAL_PROTECTION.value,
    )
    parser.add_argument("--bootstrap-samples", type=int, default=2000)
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
