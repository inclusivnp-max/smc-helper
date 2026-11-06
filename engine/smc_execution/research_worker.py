from __future__ import annotations

import argparse
import asyncio
import sys

from .modes import TradingMode
from .research_service import adaptive_walk_forward, simulation_for_mode
from .research_store import ResearchStore
from .strategy import SMCCompositeStrategy
from .validation import AlphaGatePolicy, ResearchRunner


async def execute(args: argparse.Namespace) -> int:
    store = ResearchStore(args.db)
    await store.initialize()
    dataset = await store.get_dataset(args.dataset_id)
    if dataset is None:
        print(f"Dataset not found: {args.dataset_id}", file=sys.stderr)
        return 2
    report = ResearchRunner(
        simulation=simulation_for_mode(TradingMode(args.mode)),
        walk_forward=adaptive_walk_forward(len(dataset.bars)),
        gate_policy=AlphaGatePolicy(),
        bootstrap_samples=args.bootstrap_samples,
    ).run(
        dataset,
        SMCCompositeStrategy(),
        run_id=args.run_id,
    )
    await store.save_report(report)
    if report.error:
        print(report.error, file=sys.stderr)
    return 0 if report.status.value == "COMPLETED" else 3


def main() -> None:
    parser = argparse.ArgumentParser(description="Isolated SMC research worker")
    parser.add_argument("--db", required=True)
    parser.add_argument("--dataset-id", required=True)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--bootstrap-samples", type=int, required=True)
    raise SystemExit(asyncio.run(execute(parser.parse_args())))


if __name__ == "__main__":
    main()
