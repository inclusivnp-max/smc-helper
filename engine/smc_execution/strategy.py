from __future__ import annotations

import inspect
import random
import sys
from dataclasses import asdict, dataclass
from decimal import Decimal
from typing import Any, Mapping, Protocol, Sequence

from .models import Action, D, ZERO, encode_json
from .research_models import ResearchBar, SignalDecision, StrategyManifest


class ResearchStrategy(Protocol):
    @property
    def manifest(self) -> StrategyManifest: ...

    @property
    def warmup_bars(self) -> int: ...

    def decide(self, history: Sequence[ResearchBar]) -> SignalDecision | None: ...

    def with_parameters(self, parameters: Mapping[str, Any]) -> "ResearchStrategy": ...


def _source_text(obj: object) -> str:
    """Fingerprint the complete strategy module, including helper functions."""
    try:
        module = sys.modules[type(obj).__module__]
        return inspect.getsource(module)
    except (KeyError, OSError, TypeError):
        try:
            return inspect.getsource(type(obj))
        except (OSError, TypeError):
            return f"{type(obj).__module__}.{type(obj).__qualname__}"


def _ema(values: Sequence[D], period: int) -> D:
    if not values:
        return ZERO
    alpha = D("2") / D(str(period + 1))
    decay = D("1") - alpha
    result = values[0]
    for value in values[1:]:
        result = alpha * value + decay * result
    return result


def _atr(bars: Sequence[ResearchBar], period: int) -> D:
    if len(bars) < 2:
        return ZERO
    true_ranges: list[D] = []
    for previous, current in zip(bars[:-1], bars[1:]):
        true_ranges.append(
            max(
                current.high - current.low,
                abs(current.high - previous.close),
                abs(current.low - previous.close),
            )
        )
    selected = true_ranges[-period:]
    return sum(selected, ZERO) / D(str(len(selected))) if selected else ZERO


def _mean(values: Sequence[D]) -> D:
    return sum(values, ZERO) / D(str(len(values))) if values else ZERO


def _std(values: Sequence[D], mean: D | None = None) -> D:
    if len(values) < 2:
        return ZERO
    center = _mean(values) if mean is None else mean
    variance = sum(((value - center) ** 2 for value in values), ZERO) / D(
        str(len(values) - 1)
    )
    return variance.sqrt() if variance > ZERO else ZERO


def _zscore(value: D, values: Sequence[D]) -> D:
    mean = _mean(values)
    deviation = _std(values, mean)
    return (value - mean) / deviation if deviation > ZERO else ZERO


def _clamp(value: D, low: D, high: D) -> D:
    return min(high, max(low, value))


@dataclass(frozen=True)
class SMCParameters:
    swing_lookback: int = 20
    atr_period: int = 14
    fast_ema: int = 20
    slow_ema: int = 50
    volume_lookback: int = 30
    oi_lookback: int = 12
    displacement_atr: D = D("0.80")
    sweep_weight: D = D("2.0")
    structure_weight: D = D("1.5")
    fvg_weight: D = D("1.0")
    trend_weight: D = D("1.0")
    volume_weight: D = D("0.5")
    oi_weight: D = D("0.75")
    funding_weight: D = D("0.35")
    min_score: D = D("3.0")
    max_funding_abs: D = D("0.0015")
    require_displacement: bool = True
    allow_countertrend_sweeps: bool = True

    def __post_init__(self) -> None:
        if self.swing_lookback < 3:
            raise ValueError("swing_lookback must be >= 3")
        if self.atr_period < 2:
            raise ValueError("atr_period must be >= 2")
        if self.fast_ema < 2 or self.slow_ema <= self.fast_ema:
            raise ValueError("EMA periods are invalid")
        if self.min_score <= ZERO:
            raise ValueError("min_score must be positive")

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


class SMCCompositeStrategy:
    """Deterministic, no-lookahead SMC-inspired research strategy.

    This is intentionally a falsifiable baseline, not a claim of alpha. Every
    feature is calculated from bars available at or before the decision candle.
    Entry is delegated to the simulator on a later candle.
    """

    def __init__(
        self,
        parameters: SMCParameters = SMCParameters(),
        *,
        version: str = "1.0.0",
    ) -> None:
        self.parameters = parameters
        self._manifest = StrategyManifest.create(
            "smc_composite",
            version,
            parameters.to_dict(),
            source_text=_source_text(self),
            notes="SMC-inspired, deterministic and explicitly no-lookahead",
        )

    @property
    def manifest(self) -> StrategyManifest:
        return self._manifest

    @property
    def warmup_bars(self) -> int:
        p = self.parameters
        return max(p.slow_ema * 3, p.swing_lookback + 3, p.volume_lookback + 2)

    def with_parameters(self, parameters: Mapping[str, Any]) -> "SMCCompositeStrategy":
        current = asdict(self.parameters)
        for key, value in parameters.items():
            if key not in current:
                raise ValueError(f"Unknown SMC parameter: {key}")
            if isinstance(current[key], Decimal):
                current[key] = D(str(value))
            else:
                current[key] = value
        return SMCCompositeStrategy(SMCParameters(**current), version=self.manifest.version)

    def decide(self, history: Sequence[ResearchBar]) -> SignalDecision | None:
        p = self.parameters
        if len(history) < self.warmup_bars:
            return None
        current = history[-1]
        atr = _atr(history[-(p.atr_period + 1) :], p.atr_period)
        if atr <= ZERO:
            return None

        swing_window = history[-(p.swing_lookback + 1) : -1]
        prior_high = max(bar.high for bar in swing_window)
        prior_low = min(bar.low for bar in swing_window)
        bullish_sweep = current.low < prior_low and current.close > prior_low
        bearish_sweep = current.high > prior_high and current.close < prior_high
        bullish_structure = current.close > prior_high
        bearish_structure = current.close < prior_low

        bullish_fvg = (
            len(history) >= 3
            and current.low > history[-3].high
            and current.bullish
        )
        bearish_fvg = (
            len(history) >= 3
            and current.high < history[-3].low
            and current.bearish
        )

        displaced = current.body >= atr * p.displacement_atr
        closes = [bar.close for bar in history[-(p.slow_ema * 3) :]]
        fast = _ema(closes[-(p.fast_ema * 3) :], p.fast_ema)
        slow = _ema(closes, p.slow_ema)
        trend_long = fast > slow and current.close >= fast
        trend_short = fast < slow and current.close <= fast

        volumes = [bar.volume for bar in history[-(p.volume_lookback + 1) : -1]]
        volume_z = _zscore(current.volume, volumes)

        oi_values = [
            value
            for bar in history[-(p.oi_lookback + 1) :]
            if (value := bar.feature_open_interest()) is not None
        ]
        oi_change = ZERO
        if len(oi_values) >= 2 and oi_values[0] != ZERO:
            oi_change = (oi_values[-1] - oi_values[0]) / abs(oi_values[0])

        funding = current.feature_funding_rate() or ZERO
        funding_usable = abs(funding) <= p.max_funding_abs

        long_score = ZERO
        short_score = ZERO
        reasons_long: list[str] = []
        reasons_short: list[str] = []

        if bullish_sweep:
            long_score += p.sweep_weight
            reasons_long.append("sell-side liquidity sweep")
        if bearish_sweep:
            short_score += p.sweep_weight
            reasons_short.append("buy-side liquidity sweep")
        if bullish_structure:
            long_score += p.structure_weight
            reasons_long.append("bullish structure break")
        if bearish_structure:
            short_score += p.structure_weight
            reasons_short.append("bearish structure break")
        if bullish_fvg:
            long_score += p.fvg_weight
            reasons_long.append("bullish fair-value gap")
        if bearish_fvg:
            short_score += p.fvg_weight
            reasons_short.append("bearish fair-value gap")
        if trend_long:
            long_score += p.trend_weight
            reasons_long.append("trend filter long")
        if trend_short:
            short_score += p.trend_weight
            reasons_short.append("trend filter short")
        if volume_z > D("1"):
            bonus = min(D("2"), volume_z) / D("2") * p.volume_weight
            if current.bullish:
                long_score += bonus
                reasons_long.append("volume expansion")
            elif current.bearish:
                short_score += bonus
                reasons_short.append("volume expansion")
        if oi_change > ZERO:
            oi_bonus = min(D("1"), abs(oi_change) * D("100")) * p.oi_weight
            if current.bullish:
                long_score += oi_bonus
                reasons_long.append("open-interest expansion")
            elif current.bearish:
                short_score += oi_bonus
                reasons_short.append("open-interest expansion")
        if funding_usable:
            if funding < ZERO:
                long_score += min(p.funding_weight, abs(funding) * D("1000"))
            elif funding > ZERO:
                short_score += min(p.funding_weight, abs(funding) * D("1000"))

        if p.require_displacement and not displaced:
            long_score *= D("0.65")
            short_score *= D("0.65")

        if not p.allow_countertrend_sweeps:
            if not trend_long:
                long_score = ZERO
            if not trend_short:
                short_score = ZERO

        best = max(long_score, short_score)
        if best < p.min_score or long_score == short_score:
            return None
        action = Action.LONG if long_score > short_score else Action.SHORT
        selected_reasons = reasons_long if action is Action.LONG else reasons_short
        opposing = short_score if action is Action.LONG else long_score
        separation = max(ZERO, best - opposing)
        confidence = _clamp(
            D("0.50") + D("0.08") * best + D("0.04") * separation,
            D("0.50"),
            D("0.99"),
        )
        return SignalDecision(
            action=action,
            confidence=confidence,
            reason=", ".join(selected_reasons) or "composite score",
            feature_time_ms=current.close_time_ms,
            reference_price=current.close,
            features={
                "long_score": long_score,
                "short_score": short_score,
                "atr": atr,
                "prior_high": prior_high,
                "prior_low": prior_low,
                "fast_ema": fast,
                "slow_ema": slow,
                "volume_z": volume_z,
                "oi_change": oi_change,
                "funding_rate": funding,
                "displaced": displaced,
                "bullish_sweep": bullish_sweep,
                "bearish_sweep": bearish_sweep,
                "bullish_structure": bullish_structure,
                "bearish_structure": bearish_structure,
                "bullish_fvg": bullish_fvg,
                "bearish_fvg": bearish_fvg,
            },
        )


@dataclass(frozen=True)
class TrendParameters:
    fast_ema: int = 20
    slow_ema: int = 80
    atr_period: int = 14
    min_separation_bps: D = D("10")


class TrendFollowingBaseline:
    def __init__(self, parameters: TrendParameters = TrendParameters()) -> None:
        self.parameters = parameters
        self._manifest = StrategyManifest.create(
            "baseline_trend",
            "1.0.0",
            encode_json(asdict(parameters)),
            source_text=_source_text(self),
        )

    @property
    def manifest(self) -> StrategyManifest:
        return self._manifest

    @property
    def warmup_bars(self) -> int:
        return self.parameters.slow_ema * 3

    def with_parameters(self, parameters: Mapping[str, Any]) -> "TrendFollowingBaseline":
        values = asdict(self.parameters)
        values.update(parameters)
        if "min_separation_bps" in values:
            values["min_separation_bps"] = D(str(values["min_separation_bps"]))
        return TrendFollowingBaseline(TrendParameters(**values))

    def decide(self, history: Sequence[ResearchBar]) -> SignalDecision | None:
        if len(history) < self.warmup_bars:
            return None
        p = self.parameters
        closes = [bar.close for bar in history[-self.warmup_bars :]]
        fast = _ema(closes[-p.fast_ema * 3 :], p.fast_ema)
        slow = _ema(closes, p.slow_ema)
        current = history[-1]
        if slow <= ZERO:
            return None
        separation_bps = abs(fast - slow) / slow * D("10000")
        if separation_bps < p.min_separation_bps:
            return None
        action = Action.LONG if fast > slow else Action.SHORT
        return SignalDecision(
            action=action,
            confidence=_clamp(D("0.60") + separation_bps / D("1000"), D("0.60"), D("0.90")),
            reason="EMA trend baseline",
            feature_time_ms=current.close_time_ms,
            reference_price=current.close,
            features={"fast_ema": fast, "slow_ema": slow, "separation_bps": separation_bps},
        )


@dataclass(frozen=True)
class MeanReversionParameters:
    lookback: int = 40
    entry_z: D = D("2.0")


class MeanReversionBaseline:
    def __init__(self, parameters: MeanReversionParameters = MeanReversionParameters()) -> None:
        self.parameters = parameters
        self._manifest = StrategyManifest.create(
            "baseline_mean_reversion",
            "1.0.0",
            encode_json(asdict(parameters)),
            source_text=_source_text(self),
        )

    @property
    def manifest(self) -> StrategyManifest:
        return self._manifest

    @property
    def warmup_bars(self) -> int:
        return self.parameters.lookback + 2

    def with_parameters(self, parameters: Mapping[str, Any]) -> "MeanReversionBaseline":
        values = asdict(self.parameters)
        values.update(parameters)
        values["entry_z"] = D(str(values["entry_z"]))
        return MeanReversionBaseline(MeanReversionParameters(**values))

    def decide(self, history: Sequence[ResearchBar]) -> SignalDecision | None:
        if len(history) < self.warmup_bars:
            return None
        p = self.parameters
        current = history[-1]
        values = [bar.close for bar in history[-p.lookback : -1]]
        z = _zscore(current.close, values)
        if abs(z) < p.entry_z:
            return None
        action = Action.SHORT if z > ZERO else Action.LONG
        return SignalDecision(
            action=action,
            confidence=_clamp(D("0.60") + (abs(z) - p.entry_z) / D("10"), D("0.60"), D("0.90")),
            reason="price z-score mean-reversion baseline",
            feature_time_ms=current.close_time_ms,
            reference_price=current.close,
            features={"zscore": z},
        )


@dataclass(frozen=True)
class RandomParameters:
    probability: D = D("0.03")
    seed: int = 7


class RandomBaseline:
    def __init__(self, parameters: RandomParameters = RandomParameters()) -> None:
        self.parameters = parameters
        self._manifest = StrategyManifest.create(
            "baseline_random",
            "1.0.0",
            encode_json(asdict(parameters)),
            source_text=_source_text(self),
        )

    @property
    def manifest(self) -> StrategyManifest:
        return self._manifest

    @property
    def warmup_bars(self) -> int:
        return 2

    def with_parameters(self, parameters: Mapping[str, Any]) -> "RandomBaseline":
        values = asdict(self.parameters)
        values.update(parameters)
        values["probability"] = D(str(values["probability"]))
        return RandomBaseline(RandomParameters(**values))

    def decide(self, history: Sequence[ResearchBar]) -> SignalDecision | None:
        if len(history) < self.warmup_bars:
            return None
        current = history[-1]
        # Deterministic per timestamp; repeated research runs are reproducible.
        rng = random.Random(self.parameters.seed ^ current.close_time_ms)
        if D(str(rng.random())) >= self.parameters.probability:
            return None
        action = Action.LONG if rng.random() >= 0.5 else Action.SHORT
        return SignalDecision(
            action=action,
            confidence=D("0.70"),
            reason="deterministic random baseline",
            feature_time_ms=current.close_time_ms,
            reference_price=current.close,
            features={"seed": self.parameters.seed},
        )
