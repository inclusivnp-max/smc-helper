from __future__ import annotations

import os
from pathlib import Path

import uvicorn

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
from smc_execution.live_guard import (
    LiveAuthorizationError,
    enforce_live_capital_policy,
    load_live_authorization,
    read_live_risk_profile,
    risk_configuration_fingerprint,
    runtime_code_fingerprint,
)
from smc_execution.modes import DEFAULT_MODE_PROFILES, TradingMode, TradingModeController
from smc_execution.operator_app import create_operator_app
from smc_execution.operator_service import OperatorService, OperatorSettings
from smc_execution.operator_store import OperatorStore
from smc_execution.automation import (
    ApprovedPortfolioAutomation,
    ApprovedPortfolioAutomationSettings,
)
from smc_execution.research_service import ResearchService, ResearchServiceSettings
from smc_execution.research_store import ResearchStore

def build_app():
    load_env_file(os.getenv("ENV_FILE", ".env"))
    testnet = env_bool("BINANCE_TESTNET", True)
    symbols = env_csv("SYMBOLS", "BTCUSDT")
    auto_signal_generator = env_bool("AUTO_SIGNAL_GENERATOR", False)
    raw_run_ids = os.getenv("APPROVED_RUN_IDS", "").strip()
    if not raw_run_ids:
        raw_run_ids = os.getenv("APPROVED_RUN_ID", "").strip()
    run_ids = tuple(item.strip() for item in raw_run_ids.split(",") if item.strip())
    signal_environment = os.getenv(
        "SIGNAL_ENVIRONMENT", "shadow" if testnet else "live"
    ).strip().lower()
    if auto_signal_generator and not run_ids:
        raise SystemExit(
            "AUTO_SIGNAL_GENERATOR requires APPROVED_RUN_IDS or APPROVED_RUN_ID"
        )
    if not testnet:
        try:
            enforce_live_capital_policy()
        except LiveAuthorizationError as exc:
            raise SystemExit(f"Live mode refused: {exc}") from exc
        if not auto_signal_generator:
            raise SystemExit("LIVE requires AUTO_SIGNAL_GENERATOR=true")
        if signal_environment != "live":
            raise SystemExit("A live Binance account requires SIGNAL_ENVIRONMENT=live")
        if env_bool("ALLOW_LIVE_RESEARCH_PROMOTION", False):
            raise SystemExit(
                "LIVE promotion must not be enabled inside the live execution service"
            )

    state_path = Path(os.getenv("STATE_DB", "./var/execution_state.db"))
    operator_path = Path(os.getenv("OPERATOR_DB", "./var/operator.db"))
    research_path = Path(os.getenv("RESEARCH_DB", "./var/research.db"))
    state_path.parent.mkdir(parents=True, exist_ok=True)
    operator_path.parent.mkdir(parents=True, exist_ok=True)
    research_path.parent.mkdir(parents=True, exist_ok=True)

    base_execution = ExecutionConfig(
        atr_timeframe=os.getenv("EXECUTION_TIMEFRAME", "5m").strip(),
    )
    base_governor = PortfolioGovernorConfig(
        max_open_risk_pct=base_execution.max_portfolio_risk_pct
    )
    require_credential_files = not testnet
    try:
        trade_key = env_secret(
            "BINANCE_TRADE_API_KEY",
            legacy_name="BINANCE_API_KEY",
            require_file=require_credential_files,
        )
        trade_secret = env_secret(
            "BINANCE_TRADE_API_SECRET",
            legacy_name="BINANCE_API_SECRET",
            require_file=require_credential_files,
        )
        user_data_key = env_secret(
            "BINANCE_USER_DATA_API_KEY",
            legacy_name="BINANCE_API_KEY",
            require_file=require_credential_files,
        )
        user_data_secret = env_secret(
            "BINANCE_USER_DATA_API_SECRET",
            legacy_name="BINANCE_API_SECRET",
            require_file=require_credential_files,
        )
        monitor_key = env_secret(
            "BINANCE_MONITOR_API_KEY",
            legacy_name="BINANCE_API_KEY",
            require_file=require_credential_files,
        )
        monitor_secret = env_secret(
            "BINANCE_MONITOR_API_SECRET",
            legacy_name="BINANCE_API_SECRET",
            require_file=require_credential_files,
        )
        operator_token = env_secret(
            "OPERATOR_TOKEN", require_file=require_credential_files
        )
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc

    live_authorization = None
    if not testnet:
        try:
            config_file = os.environ["LIVE_CONFIG_FILE"]
            profile_name = read_live_risk_profile(config_file)
            profile_mode = TradingMode(profile_name)
            if profile_mode is TradingMode.PAUSED:
                raise LiveAuthorizationError("PAUSED cannot be a signed live risk profile")
            profile = DEFAULT_MODE_PROFILES[profile_mode]
            live_authorization = load_live_authorization(
                config_file,
                os.environ["LIVE_APPROVER_PUBLIC_KEYS_DIR"],
                expected_deployment_id=os.environ["LIVE_DEPLOYMENT_ID"],
                expected_subaccount_id=os.environ["BINANCE_LIVE_SUBACCOUNT_ID"],
                expected_symbols=symbols,
                expected_run_ids=run_ids,
                expected_engine_code_fingerprint=runtime_code_fingerprint(),
                expected_risk_profile=profile.mode.value,
                expected_risk_fingerprint=risk_configuration_fingerprint(
                    base_execution, base_governor, profile
                ),
                expected_state_backend="sqlite-single-node",
                credential_api_keys={
                    "trade": trade_key,
                    "user_data": user_data_key,
                    "monitor": monitor_key,
                },
            )
        except (KeyError, ValueError, LiveAuthorizationError) as exc:
            raise SystemExit(f"Live mode refused: {exc}") from exc

    api = BinanceUSDMAdapter(
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
    control = SQLiteControlPlane(state_path)
    engine = ExecutionEngine(api, control, base_execution)
    supervisor = ProtectionSupervisor(api, control, base_execution)
    recovery = RecoveryManager(api, control, supervisor, base_execution)
    governor = PortfolioGovernor(api, control, base_governor)
    runtime = TradingRuntime(api, control, engine, supervisor, recovery, governor)
    mode_controller = TradingModeController(
        control,
        api,
        engine,
        supervisor,
        recovery,
        governor,
        base_execution_config=base_execution,
        base_governor_config=base_governor,
    )
    operator_store = OperatorStore(operator_path)
    research_store = ResearchStore(research_path)
    research_service = ResearchService(
        research_store,
        operator_store,
        ResearchServiceSettings(
            allow_live_promotion=env_bool("ALLOW_LIVE_RESEARCH_PROMOTION", False),
            bootstrap_samples=int(os.getenv("RESEARCH_BOOTSTRAP_SAMPLES", "2000")),
            minimum_dataset_bars=int(os.getenv("RESEARCH_MINIMUM_BARS", "10000")),
            minimum_dataset_span_days=int(
                os.getenv("RESEARCH_MINIMUM_SPAN_DAYS", "90")
            ),
            minimum_shadow_closed_trades=int(
                os.getenv("RESEARCH_MINIMUM_SHADOW_TRADES", "20")
            ),
        ),
    )
    strategy_automation = None
    if auto_signal_generator:
        strategy_automation = ApprovedPortfolioAutomation(
            runtime,
            research_store,
            ApprovedPortfolioAutomationSettings(
                run_ids=run_ids,
                environment=signal_environment,
                poll_seconds=float(os.getenv("SIGNAL_POLL_SECONDS", "5")),
                history_bars=int(os.getenv("SIGNAL_HISTORY_BARS", "500")),
            ),
        )

    service = OperatorService(
        runtime,
        mode_controller,
        operator_store,
        OperatorSettings(
            token=operator_token,
            symbols=symbols,
            testnet=testnet,
            actor_name=os.getenv("OPERATOR_ACTOR", "operator"),
            telemetry_interval_seconds=float(
                os.getenv("TELEMETRY_INTERVAL_SECONDS", "5")
            ),
            allow_flatten_command=env_bool("ALLOW_FLATTEN_COMMAND", True),
        ),
        research_service=research_service,
        strategy_automation=strategy_automation,
        live_authorization=live_authorization,
    )
    return create_operator_app(
        service,
        start_runtime=True,
        require_https_proxy=env_bool("OPERATOR_BEHIND_HTTPS_PROXY", False),
    )


def main() -> None:
    load_env_file(os.getenv("ENV_FILE", ".env"))
    host = os.getenv("OPERATOR_HOST", "127.0.0.1")
    port = int(os.getenv("OPERATOR_PORT", "8080"))
    cert = os.getenv("OPERATOR_TLS_CERT") or None
    key = os.getenv("OPERATOR_TLS_KEY") or None
    behind_https_proxy = env_bool("OPERATOR_BEHIND_HTTPS_PROXY", False)
    if (
        host not in {"127.0.0.1", "localhost", "::1"}
        and not (cert and key)
        and not behind_https_proxy
    ):
        raise SystemExit(
            "Remote bind without TLS is refused. Configure OPERATOR_TLS_CERT/"
            "OPERATOR_TLS_KEY or an HTTPS reverse proxy."
        )
    uvicorn.run(
        build_app(),
        host=host,
        port=port,
        log_level=os.getenv("LOG_LEVEL", "info").lower(),
        ssl_certfile=cert,
        ssl_keyfile=key,
        proxy_headers=behind_https_proxy,
        forwarded_allow_ips=os.getenv("OPERATOR_TRUSTED_PROXY_IPS", "127.0.0.1"),
        server_header=False,
    )


if __name__ == "__main__":
    main()
