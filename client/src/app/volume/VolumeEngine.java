package app.volume;

import app.common.Direction;
import app.common.RollingPercentile;
import app.common.ScoreUtils;
import app.model.Candle;
import app.volatility.MarketMetrics;

import java.util.ArrayList;
import java.util.List;

public class VolumeEngine {
    // See RollingPercentile's class doc: volumeRatio is already normalized (volume / rolling
    // avg volume), but a fixed "1.50x = spike" cutoff still treats a naturally bursty low-cap
    // and a naturally steady major pair as if their normal ranges of that ratio were identical.
    private static final int RELATIVE_WINDOW = 120;
    private static final int RELATIVE_MIN_SAMPLES = 30;
    private static final double RELATIVE_SPIKE_PERCENTILE = 85.0;
    private static final double ABSOLUTE_SPIKE_FALLBACK_RATIO = 1.50;

    public VolumeContext analyze(List<Candle> candles, MarketMetrics metrics) {
        List<VolumeSignal> signals = new ArrayList<>();
        VolumeSignal[] byIndex = new VolumeSignal[candles.size()];
        boolean[] relativeSpike = new boolean[candles.size()];

        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double volumeRatio = metrics.volumeRatioAt(i);
            double bodyPower = c.bodyPercent();
            Direction side = c.bullish() ? Direction.BULLISH : c.bearish() ? Direction.BEARISH : Direction.NEUTRAL;
            double signed = side.bullish() ? 1.0 : side.bearish() ? -1.0 : 0.0;
            // Wick-adjusted delta proxy:
            // Бычья свеча: нижний фитиль = поглощение продаж (→ сила), верхний = отторжение (→ слабость)
            // Медвежья свеча: верхний фитиль = поглощение покупок (→ сила), нижний = отторжение (→ слабость)
            double wickAdj = 1.0;
            if (c.range() > 0.0) {
                double upperWickRatio = c.upperWick() / c.range();
                double lowerWickRatio = c.lowerWick() / c.range();
                if (side.bullish()) {
                    wickAdj = 1.0 + lowerWickRatio * 0.30 - upperWickRatio * 0.35;
                } else if (side.bearish()) {
                    wickAdj = 1.0 + upperWickRatio * 0.30 - lowerWickRatio * 0.35;
                }
            }
            double deltaProxy = c.volume * bodyPower * signed * Math.max(0.25, wickAdj);

            int score = 0;
            score += ScoreUtils.scale(volumeRatio, 1.0, 2.8, 45);
            score += ScoreUtils.scale(bodyPower, 0.35, 0.85, 30);
            score += ScoreUtils.scale(metrics.atrExpansionAt(i), 0.9, 1.8, 25);

            relativeSpike[i] = isRelativeSpike(metrics, i, volumeRatio);

            // Consecutive spike bonus: два подряд высокообъёмных бара = институциональная агрессия
            if (i > 0 && relativeSpike[i - 1] && volumeRatio > 1.30) {
                score += 12;
            }

            VolumeSignal signal = new VolumeSignal(i, volumeRatio, deltaProxy, side, ScoreUtils.clamp(score, 0, 100));
            if (signal.score >= 35 || relativeSpike[i]) signals.add(signal);
            byIndex[i] = signal;
        }

        return new VolumeContext(signals, byIndex);
    }

    /**
     * Symbol-relative version of "is this a volume spike": ranks volumeRatio against this
     * symbol's own recent 120-bar history instead of comparing to one fixed number for every
     * symbol. Falls back to the old absolute 1.50x cutoff while there isn't enough history yet,
     * so short candle windows behave exactly as before this change.
     */
    private boolean isRelativeSpike(MarketMetrics metrics, int index, double volumeRatio) {
        if (!RollingPercentile.hasEnoughHistory(index, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES)) {
            return volumeRatio >= ABSOLUTE_SPIKE_FALLBACK_RATIO;
        }
        double pct = RollingPercentile.rank(metrics::volumeRatioAt, index, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES);
        // Token absolute floor so a symbol that's been quiet for 120 bars can't call its own
        // slightly-less-quiet bar a "spike" just because it ranks high within a flat history.
        return volumeRatio >= 1.05 && pct >= RELATIVE_SPIKE_PERCENTILE;
    }
}
