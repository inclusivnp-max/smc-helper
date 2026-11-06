from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

from smc_execution import (
    BinanceUSDMAdapter,
    BinanceUSDMConfig,
    ExecutionConfig,
    ExecutionEngine,
    PortfolioGovernor,
    PortfolioGovernorConfig,
    ProtectionSupervisor,
    RecoveryManager,
    SQLiteControlPlane,
    TradingRuntime,
)
from smc_execution.environment import env_bool, env_csv, env_secret, load_env_file



async def read_line() -> str:
    return await asyncio.to_thread(sys.stdin.readline)


async def main() -> None:
    load_env_file(os.getenv("ENV_FILE", ".env"))
    testnet = env_bool("BINANCE_TESTNET", True)
    if not testnet:
        raise SystemExit(
            "Live mode is permanently disabled for the stdin example runner. "
            "Use the installed operator service with signed live authorization."
        )

    symbols = env_csv("SYMBOLS", "BTCUSDT")
    state_path = Path(os.getenv("STATE_DB", "./var/execution_state.db"))
    state_path.parent.mkdir(parents=True, exist_ok=True)

    api = BinanceUSDMAdapter(
        BinanceUSDMConfig(
            api_key=env_secret(
                "BINANCE_MONITOR_API_KEY", legacy_name="BINANCE_API_KEY"
            ),
            api_secret=env_secret(
                "BINANCE_MONITOR_API_SECRET", legacy_name="BINANCE_API_SECRET"
            ),
            testnet=testnet,
            trade_api_key=env_secret(
                "BINANCE_TRADE_API_KEY", legacy_name="BINANCE_API_KEY"
            ),
            trade_api_secret=env_secret(
                "BINANCE_TRADE_API_SECRET", legacy_name="BINANCE_API_SECRET"
            ),
            user_data_api_key=env_secret(
                "BINANCE_USER_DATA_API_KEY", legacy_name="BINANCE_API_KEY"
            ),
            user_data_api_secret=env_secret(
                "BINANCE_USER_DATA_API_SECRET", legacy_name="BINANCE_API_SECRET"
            ),
        )
    )
    control = SQLiteControlPlane(state_path)
    execution_config = ExecutionConfig()
    engine = ExecutionEngine(api, control, execution_config)
    supervisor = ProtectionSupervisor(api, control, execution_config)
    recovery = RecoveryManager(api, control, supervisor, execution_config)
    governor = PortfolioGovernor(
        api,
        control,
        PortfolioGovernorConfig(
            max_open_risk_pct=execution_config.max_portfolio_risk_pct
        ),
    )
    runtime = TradingRuntime(
        api,
        control,
        engine,
        supervisor,
        recovery,
        governor,
    )

    report = await runtime.start(symbols)
    print(json.dumps(report.__dict__, ensure_ascii=False, default=str), flush=True)
    try:
        while True:
            line = (await read_line()).strip()
            if not line or line.lower() in {"quit", "exit"}:
                break
            try:
                payload = json.loads(line)
                result = await runtime.submit_signal(payload)
                print(json.dumps(result.to_dict(), ensure_ascii=False), flush=True)
            except Exception as exc:
                print(json.dumps({"error": repr(exc)}, ensure_ascii=False), flush=True)
    finally:
        await runtime.stop()


if __name__ == "__main__":
    asyncio.run(main())
