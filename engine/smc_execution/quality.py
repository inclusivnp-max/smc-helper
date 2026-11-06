from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from statistics import median
import math

from .config import ExecutionConfig
from .models import D, ExecutionResult, ZERO


@dataclass(frozen=True)
class QualityDecision:
    halt: bool
    reason: str | None
    sample_count: int
    p95_shortfall_bps: D
    reject_rate: D
    median_shortfall_bps: D
    mad_shortfall_bps: D


class ExecutionQualityGuard:
    """
    Conservative circuit breaker, not a predictive alpha model.

    It combines absolute p95 limits, reject rate and a robust median/MAD outlier
    detector. A regime change in execution quality stops admission; it never
    increases risk or chases liquidity.
    """

    def __init__(self, config: ExecutionConfig) -> None:
        self.config = config
        self._samples: deque[tuple[D | None, bool]] = deque(maxlen=config.quality_window)

    def observe(self, result: ExecutionResult) -> QualityDecision:
        rejected = result.status in {
            "CANCELLED_NO_FILL",
            "FAILED_SAFE",
            "FAILED_UNCERTAIN",
            "PROTECTION_FAILED_POSITION_CLOSED",
            "ABORTED_PRICE_ESCAPED_BEFORE_SUBMIT",
            "ABORTED_RISK_RESERVATION_OVERRUN",
        }
        shortfall = result.implementation_shortfall_bps
        self._samples.append((shortfall, rejected))
        return self.evaluate()

    def evaluate(self) -> QualityDecision:
        n = len(self._samples)
        values = sorted(max(ZERO, x) for x, _ in self._samples if x is not None)
        reject_rate = (
            D(sum(1 for _, rejected in self._samples if rejected)) / D(n)
            if n
            else ZERO
        )
        if values:
            med = D(str(median(values)))
            deviations = sorted(abs(x - med) for x in values)
            mad = D(str(median(deviations))) if deviations else ZERO
            idx = max(0, min(len(values) - 1, math.ceil(len(values) * 0.95) - 1))
            p95 = values[idx]
        else:
            med = mad = p95 = ZERO

        halt = False
        reason: str | None = None
        if n >= self.config.quality_min_samples:
            if p95 > self.config.quality_max_p95_shortfall_bps:
                halt = True
                reason = (
                    f"execution quality p95={p95} bps exceeds "
                    f"{self.config.quality_max_p95_shortfall_bps}"
                )
            elif reject_rate > self.config.quality_max_reject_rate:
                halt = True
                reason = (
                    f"execution reject rate={reject_rate} exceeds "
                    f"{self.config.quality_max_reject_rate}"
                )
            elif mad > ZERO and values[-1] > med + self.config.quality_mad_multiplier * mad:
                halt = True
                reason = (
                    f"execution shortfall outlier={values[-1]} bps; "
                    f"median={med}, MAD={mad}"
                )

        return QualityDecision(
            halt=halt,
            reason=reason,
            sample_count=n,
            p95_shortfall_bps=p95,
            reject_rate=reject_rate,
            median_shortfall_bps=med,
            mad_shortfall_bps=mad,
        )
