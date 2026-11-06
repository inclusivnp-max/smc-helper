from __future__ import annotations

import hashlib
import json
import math
import time
from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any, Mapping, Sequence

from .models import Action, D, ZERO, encode_json


class ResearchError(RuntimeError):
    pass


class DataQualityError(ResearchError):
    pass


class ApprovalStage(str, Enum):
    DRAFT = "DRAFT"
    RESEARCH_PASSED = "RESEARCH_PASSED"
    SHADOW = "SHADOW"
    TESTNET = "TESTNET"
    LIVE = "LIVE"
    REVOKED = "REVOKED"


class RunStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


@dataclass(frozen=True)
class ResearchBar:
    symbol: str
    interval: str
    open_time_ms: int
    close_time_ms: int
    open: D
    high: D
    low: D
    close: D
    volume: D
    quote_volume: D = ZERO
    trades: int = 0
    taker_buy_base: D = ZERO
    taker_buy_quote: D = ZERO
    mark_close: D | None = None
    open_interest: D | None = None
    funding_rate: D | None = None
    open_interest_available_at_ms: int | None = None
    funding_available_at_ms: int | None = None

    def __post_init__(self) -> None:
        if not self.symbol:
            raise ValueError("symbol is required")
        if not self.interval:
            raise ValueError("interval is required")
        if self.close_time_ms <= self.open_time_ms:
            raise ValueError("close_time_ms must be after open_time_ms")
        if self.low > self.high:
            raise ValueError("low cannot exceed high")
        if self.high < max(self.open, self.close):
            raise ValueError("high is below candle body")
        if self.low > min(self.open, self.close):
            raise ValueError("low is above candle body")
        if self.volume < ZERO or self.quote_volume < ZERO:
            raise ValueError("volume cannot be negative")
        if self.trades < 0:
            raise ValueError("trades cannot be negative")

    @property
    def body(self) -> D:
        return abs(self.close - self.open)

    @property
    def range(self) -> D:
        return self.high - self.low

    @property
    def bullish(self) -> bool:
        return self.close > self.open

    @property
    def bearish(self) -> bool:
        return self.close < self.open

    def feature_open_interest(self) -> D | None:
        if self.open_interest is None:
            return None
        available = self.open_interest_available_at_ms
        if available is not None and available > self.close_time_ms:
            return None
        return self.open_interest

    def feature_funding_rate(self) -> D | None:
        if self.funding_rate is None:
            return None
        available = self.funding_available_at_ms
        if available is not None and available > self.close_time_ms:
            return None
        return self.funding_rate

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class StrategyManifest:
    strategy_id: str
    version: str
    parameters: Mapping[str, Any]
    code_fingerprint: str
    created_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))
    notes: str = ""

    @property
    def identity(self) -> str:
        return f"{self.strategy_id}:{self.version}"

    @classmethod
    def create(
        cls,
        strategy_id: str,
        version: str,
        parameters: Mapping[str, Any],
        *,
        source_text: str = "",
        notes: str = "",
    ) -> "StrategyManifest":
        canonical = json.dumps(encode_json(dict(parameters)), sort_keys=True, separators=(",", ":"))
        digest = hashlib.sha256((source_text + "\n" + canonical).encode("utf-8")).hexdigest()
        return cls(
            strategy_id=strategy_id,
            version=version,
            parameters=dict(parameters),
            code_fingerprint=digest,
            notes=notes,
        )

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class SignalDecision:
    action: Action
    confidence: D
    reason: str
    feature_time_ms: int
    reference_price: D
    features: Mapping[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class SimulationConfig:
    profile: str = "CUSTOM"
    initial_equity: D = D("10000")
    risk_per_trade_pct: D = D("0.003")
    max_position_notional: D = D("2000")
    stop_atr_multiple: D = D("1.5")
    take_profit_atr_multiple: D = D("2.25")
    maker_offset_bps: D = D("1")
    aggressive_fallback_bps: D = D("5")
    max_adverse_slippage_bps: D = D("8")
    stop_slippage_bps: D = D("5")
    maker_fee_bps: D = D("2")
    taker_fee_bps: D = D("5")
    entry_latency_bars: int = 1
    maker_wait_bars: int = 1
    max_holding_bars: int = 96
    min_confidence: D = D("0.70")
    minimum_reward_risk: D = D("1.25")
    missed_fill_probability: D = ZERO
    funding_period_hours: D = D("8")
    conservative_same_bar_exit: bool = True
    allow_aggressive_fallback: bool = True
    breakeven_activation_r: D | None = D("1.0")
    breakeven_lock_bps: D = D("10")
    trailing_activation_r: D | None = D("1.8")
    trailing_atr_multiple: D | None = D("1.35")

    def __post_init__(self) -> None:
        if not self.profile:
            raise ValueError("simulation profile is required")
        if self.initial_equity <= ZERO:
            raise ValueError("initial_equity must be positive")
        if not (ZERO < self.risk_per_trade_pct < D("1")):
            raise ValueError("risk_per_trade_pct must be in (0, 1)")
        if self.max_position_notional <= ZERO:
            raise ValueError("max_position_notional must be positive")
        if self.stop_atr_multiple <= ZERO or self.take_profit_atr_multiple <= ZERO:
            raise ValueError("ATR multiples must be positive")
        for name, value in (
            ("maker_offset_bps", self.maker_offset_bps),
            ("aggressive_fallback_bps", self.aggressive_fallback_bps),
            ("max_adverse_slippage_bps", self.max_adverse_slippage_bps),
            ("stop_slippage_bps", self.stop_slippage_bps),
            ("maker_fee_bps", self.maker_fee_bps),
            ("taker_fee_bps", self.taker_fee_bps),
        ):
            if value < ZERO:
                raise ValueError(f"{name} cannot be negative")
        if self.entry_latency_bars < 1:
            raise ValueError("entry_latency_bars must be >= 1")
        if self.maker_wait_bars < 0:
            raise ValueError("maker_wait_bars cannot be negative")
        if self.max_holding_bars < 1:
            raise ValueError("max_holding_bars must be positive")
        if not (ZERO <= self.missed_fill_probability < D("1")):
            raise ValueError("missed_fill_probability must be in [0,1)")
        if not (ZERO <= self.min_confidence <= D("1")):
            raise ValueError("min_confidence must be in [0,1]")

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class SimulatedTrade:
    trade_id: str
    symbol: str
    action: Action
    signal_time_ms: int
    entry_time_ms: int
    exit_time_ms: int
    entry_price: D
    exit_price: D
    stop_price_initial: D
    take_profit_price_initial: D
    quantity: D
    gross_pnl: D
    fees: D
    funding: D
    net_pnl: D
    initial_risk: D
    r_multiple: D
    holding_bars: int
    exit_reason: str
    entry_liquidity: str
    confidence: D
    equity_before: D
    equity_after: D
    max_favorable_r: D
    max_adverse_r: D

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class PerformanceMetrics:
    trades: int
    wins: int
    losses: int
    breakeven: int
    win_rate: D
    net_pnl: D
    return_pct: D
    expectancy_r: D
    avg_win_r: D
    avg_loss_r: D
    profit_factor: D
    max_drawdown_pct: D
    sharpe_per_trade: D
    sortino_per_trade: D
    calmar: D
    total_fees: D
    total_funding: D
    maker_ratio: D
    avg_holding_bars: D
    median_r: D
    best_trade_r: D
    worst_trade_r: D

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class BootstrapResult:
    samples: int
    probability_positive_expectancy: D
    expectancy_r_p05: D
    expectancy_r_p50: D
    expectancy_r_p95: D
    max_drawdown_pct_p50: D
    max_drawdown_pct_p95: D

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class StressScenarioResult:
    name: str
    net_pnl: D
    profit_factor: D
    expectancy_r: D
    max_drawdown_pct: D
    passed: bool

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class WalkForwardFold:
    fold: int
    train_start_ms: int
    train_end_ms: int
    test_start_ms: int
    test_end_ms: int
    selected_parameters: Mapping[str, Any]
    train_metrics: PerformanceMetrics
    test_metrics: PerformanceMetrics

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class GateDecision:
    passed: bool
    reasons: tuple[str, ...]
    score: D
    required_stage: ApprovalStage = ApprovalStage.RESEARCH_PASSED

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


METRICS_ALGORITHM_VERSION = 2
"""Semantics of the aggregate OOS metrics and of every alpha-gate input.

Bump this whenever a change alters what those numbers mean. A stored report is
only a cached decision: ``evaluate_alpha_gate`` runs once, per-trade rows are
never persisted, and the report is immutable, so an older report can neither be
recomputed nor compared with a newer one. Promotion, the governed signal router
and the live authorization all refuse a report below the running version, which
forces a re-run instead of silently re-blessing a decision made under different
rules.

Version 1 (5.0.0) is recorded as absent/0: those reports predate the field.
Version 2 (5.0.1) chains walk-forward windows into one fixed-fractional equity
curve and compounds the realized rather than the configured risk fraction.
"""


@dataclass(frozen=True)
class ResearchReport:
    run_id: str
    dataset_id: str
    dataset_fingerprint: str
    manifest: StrategyManifest
    simulation: SimulationConfig
    status: RunStatus
    created_at_ms: int
    completed_at_ms: int | None
    metrics: PerformanceMetrics | None
    bootstrap: BootstrapResult | None
    stress: tuple[StressScenarioResult, ...]
    folds: tuple[WalkForwardFold, ...]
    baselines: Mapping[str, PerformanceMetrics]
    gate: GateDecision | None
    warnings: tuple[str, ...] = ()
    error: str | None = None
    metrics_algorithm_version: int = METRICS_ALGORITHM_VERSION

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


def dataset_fingerprint(bars: Sequence[ResearchBar]) -> str:
    hasher = hashlib.sha256()
    for bar in bars:
        payload = json.dumps(bar.to_dict(), sort_keys=True, separators=(",", ":"))
        hasher.update(payload.encode("utf-8"))
        hasher.update(b"\n")
    return hasher.hexdigest()


def decimal_from_float(value: float, places: int = 12) -> D:
    if not math.isfinite(value):
        return ZERO
    return D(f"{value:.{places}f}")
