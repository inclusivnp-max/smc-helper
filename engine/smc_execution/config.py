from __future__ import annotations

from dataclasses import dataclass

from .models import D, ONE, PartialFillPolicy, OneWayPolicy, ZERO


@dataclass(frozen=True)
class ExecutionConfig:
    # Freshness and execution budget
    max_signal_age_ms: int = 30_000
    quote_local_max_age_ms: int = 750
    quote_exchange_max_age_ms: int = 1_000
    quote_future_tolerance_ms: int = 1_000
    maker_timeout_ms: int = 500
    total_entry_timeout_ms: int = 1_800
    protection_timeout_ms: int = 2_000
    default_io_timeout_ms: int = 1_200
    cancel_reconcile_timeout_ms: int = 1_200
    poll_fallback_ms: int = 75

    # Entry behavior
    maker_base_improve_ticks: int = 0
    maker_max_improve_ticks: int = 2
    maker_imbalance_threshold: D = D("0.35")
    aggressive_cross_bps: D = D("5")
    max_adverse_slippage_bps: D = D("8")
    max_spread_bps: D = D("12")
    min_confidence: D = ZERO
    self_trade_prevention_mode: str = "EXPIRE_TAKER"

    # Fill policy
    min_fill_ratio: D = D("0.90")
    partial_fill_policy: PartialFillPolicy = PartialFillPolicy.FLATTEN_BELOW_MIN

    # Risk
    risk_per_trade_pct: D = D("0.005")
    max_portfolio_risk_pct: D = D("0.03")
    max_position_notional: D = D("5000")
    risk_reservation_buffer_pct: D = D("0.05")
    stop_atr_multiple: D = D("1.5")
    take_profit_atr_multiple: D = D("2.0")
    minimum_reward_risk: D = D("1.25")
    min_stop_distance_bps: D = D("5")
    estimated_round_trip_fees_bps: D = D("8")
    execution_risk_reserve_bps: D = D("4")
    atr_period: int = 14
    atr_timeframe: str = "5m"
    atr_warmup_bars: int = 100

    # Position and protection policy
    one_way_policy: OneWayPolicy = OneWayPolicy.REJECT_IF_ANY_POSITION
    stop_working_type: str = "MARK_PRICE"
    stop_price_protect: bool = False
    stop_close_position: bool = True
    allow_emergency_market_exit: bool = True
    emergency_ioc_band_bps: D = D("15")

    # Runtime limits
    max_concurrent_symbols: int = 8
    admission_require_user_stream: bool = True

    # Execution quality circuit breaker
    quality_min_samples: int = 30
    quality_window: int = 200
    quality_max_p95_shortfall_bps: D = D("12")
    quality_max_reject_rate: D = D("0.20")
    quality_mad_multiplier: D = D("6")

    def __post_init__(self) -> None:
        if not (ZERO < self.risk_per_trade_pct < ONE):
            raise ValueError("risk_per_trade_pct must be in (0, 1)")
        if not (ZERO < self.max_portfolio_risk_pct < ONE):
            raise ValueError("max_portfolio_risk_pct must be in (0, 1)")
        if not (ZERO < self.min_fill_ratio <= ONE):
            raise ValueError("min_fill_ratio must be in (0, 1]")
        if self.total_entry_timeout_ms < self.maker_timeout_ms:
            raise ValueError("total_entry_timeout_ms must be >= maker_timeout_ms")
        if self.atr_warmup_bars < self.atr_period + 1:
            raise ValueError("atr_warmup_bars must be >= atr_period + 1")
        if self.maker_base_improve_ticks < 0:
            raise ValueError("maker_base_improve_ticks cannot be negative")
        if self.maker_max_improve_ticks < self.maker_base_improve_ticks:
            raise ValueError("maker_max_improve_ticks must be >= maker_base_improve_ticks")
        if self.risk_reservation_buffer_pct < ZERO:
            raise ValueError("risk_reservation_buffer_pct cannot be negative")
