__version__ = "5.0.1"

from .binance_usdm import BinanceUSDMAdapter, BinanceUSDMConfig
from .config import ExecutionConfig
from .control_plane import SQLiteControlPlane
from .engine import ExecutionEngine
from .governor import PortfolioGovernor, PortfolioGovernorConfig
from .models import *  # noqa: F401,F403
from .preflight import PreflightReport, run_preflight
from .quality import ExecutionQualityGuard
from .recovery import RecoveryConfig, RecoveryManager
from .redis_control_plane import RedisControlPlane
from .runtime import TradingRuntime
from .supervisor import ProtectionSupervisor

__all__ = [
    "BinanceUSDMAdapter",
    "BinanceUSDMConfig",
    "ExecutionConfig",
    "SQLiteControlPlane",
    "RedisControlPlane",
    "ExecutionEngine",
    "PortfolioGovernor",
    "PortfolioGovernorConfig",
    "ExecutionQualityGuard",
    "RecoveryConfig",
    "RecoveryManager",
    "TradingRuntime",
    "ProtectionSupervisor",
    "PreflightReport",
    "run_preflight",
    "TradingMode",
    "ModeProfile",
    "DynamicProtectionPolicy",
    "TradingModeController",
    "OperatorService",
    "OperatorSettings",
    "OperatorStore",
]

from .modes import DynamicProtectionPolicy, ModeProfile, TradingMode, TradingModeController
from .operator_service import OperatorService, OperatorSettings
from .operator_store import OperatorStore

from .alpha_gate import AlphaGateSettings, GovernedSignalRouter, SignalAdmissionError
from .backtest import BacktestResult, EventDrivenBacktester, calculate_metrics
from .binance_research import BinanceResearchDataClient
from .research_data import DataQualityReport, ResearchDataset, load_csv_dataset, validate_bars, write_csv_dataset
from .research_models import *  # noqa: F401,F403
from .research_service import ResearchService, ResearchServiceSettings
from .research_store import ResearchStore, StrategyApproval
from .strategy import (
    MeanReversionBaseline,
    RandomBaseline,
    SMCCompositeStrategy,
    SMCParameters,
    TrendFollowingBaseline,
)
from .validation import AlphaGatePolicy, ResearchRunner, WalkForwardConfig
from .automation import (
    ApprovedPortfolioAutomation,
    ApprovedPortfolioAutomationSettings,
    StrategyAutomation,
    StrategyAutomationSettings,
)
from .shadow import ShadowBook, ShadowConfig
from .signal_generator import (
    SMCSignalDaemon,
    SignalDaemonSettings,
    StrategyManifestMismatch,
    load_approved_smc_strategy,
)

__all__ += [
    "AlphaGateSettings", "GovernedSignalRouter", "SignalAdmissionError",
    "BacktestResult", "EventDrivenBacktester", "calculate_metrics",
    "BinanceResearchDataClient", "DataQualityReport", "ResearchDataset",
    "load_csv_dataset", "validate_bars", "write_csv_dataset",
    "ResearchService", "ResearchServiceSettings", "ResearchStore",
    "StrategyApproval", "SMCCompositeStrategy", "SMCParameters",
    "TrendFollowingBaseline", "MeanReversionBaseline", "RandomBaseline",
    "AlphaGatePolicy", "ResearchRunner", "WalkForwardConfig",
]

__all__ += [
    "ApprovedPortfolioAutomation", "ApprovedPortfolioAutomationSettings",
    "StrategyAutomation", "StrategyAutomationSettings", "ShadowBook", "ShadowConfig",
    "SMCSignalDaemon", "SignalDaemonSettings", "StrategyManifestMismatch",
    "load_approved_smc_strategy",
]
