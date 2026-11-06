from __future__ import annotations

import asyncio
import json
import os

from smc_execution import BinanceUSDMAdapter, BinanceUSDMConfig, run_preflight
from smc_execution.environment import env_bool, env_csv, env_secret, load_env_file



async def main() -> None:
    load_env_file(os.getenv("ENV_FILE", ".env"))
    testnet = env_bool("BINANCE_TESTNET", True)
    require_file = not testnet
    trade_key = env_secret(
        "BINANCE_TRADE_API_KEY",
        legacy_name="BINANCE_API_KEY",
        require_file=require_file,
    )
    trade_secret = env_secret(
        "BINANCE_TRADE_API_SECRET",
        legacy_name="BINANCE_API_SECRET",
        require_file=require_file,
    )
    user_data_key = env_secret(
        "BINANCE_USER_DATA_API_KEY",
        legacy_name="BINANCE_API_KEY",
        require_file=require_file,
    )
    user_data_secret = env_secret(
        "BINANCE_USER_DATA_API_SECRET",
        legacy_name="BINANCE_API_SECRET",
        require_file=require_file,
    )
    monitor_key = env_secret(
        "BINANCE_MONITOR_API_KEY",
        legacy_name="BINANCE_API_KEY",
        require_file=require_file,
    )
    monitor_secret = env_secret(
        "BINANCE_MONITOR_API_SECRET",
        legacy_name="BINANCE_API_SECRET",
        require_file=require_file,
    )
    symbols = env_csv("SYMBOLS", "BTCUSDT")
    adapter = BinanceUSDMAdapter(
        BinanceUSDMConfig(
            api_key=monitor_key,
            api_secret=monitor_secret,
            testnet=testnet,
            trade_api_key=trade_key,
            trade_api_secret=trade_secret,
            user_data_api_key=user_data_key,
            user_data_api_secret=user_data_secret,
            monitor_api_key=monitor_key,
            monitor_api_secret=monitor_secret,
        )
    )
    await adapter.start(symbols)
    try:
        # Give both public and private streams a chance to establish.
        await asyncio.sleep(1.0)
        report = await run_preflight(adapter, symbols)
        print(json.dumps(report.__dict__, default=str, indent=2, ensure_ascii=False))
        if report.warnings:
            raise SystemExit(2)
    finally:
        await adapter.close()


if __name__ == "__main__":
    asyncio.run(main())
