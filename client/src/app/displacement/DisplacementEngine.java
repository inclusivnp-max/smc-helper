package app.displacement;

import app.common.Direction;
import app.common.EngineConfig;
import app.common.RollingPercentile;
import app.common.ScoreUtils;
import app.model.Candle;
import app.volatility.MarketMetrics;
import app.volume.VolumeContext;
import app.volume.VolumeSignal;

import java.util.ArrayList;
import java.util.List;

public class DisplacementEngine {
    // Window/threshold for the symbol-relative expansion check below — see RollingPercentile's
    // class doc for why a fixed absolute cutoff on an already-normalized ratio still isn't
    // symbol-fair. 120 bars is roughly "this symbol's last few sessions" on the LTF timeframes
    // this engine typically runs on; 30 minimum samples avoids trusting a rank computed from a
    // handful of candles.
    private static final int RELATIVE_WINDOW = 120;
    private static final int RELATIVE_MIN_SAMPLES = 30;
    private static final double RELATIVE_EXPANSION_PERCENTILE = 70.0;

    private final EngineConfig config;

    public DisplacementEngine(EngineConfig config) {
        this.config = config;
    }

    public DisplacementContext analyze(List<Candle> candles, MarketMetrics metrics, VolumeContext volume) {
        List<DisplacementEvent> events = new ArrayList<>();
        DisplacementEvent[] byIndex = new DisplacementEvent[candles.size()];

        for (int i = 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Direction direction = c.bullish() ? Direction.BULLISH : c.bearish() ? Direction.BEARISH : Direction.NEUTRAL;
            if (direction == Direction.NEUTRAL) continue;

            double bodyPercent = c.bodyPercent();
            double bodyExpansion = metrics.bodyExpansionAt(i);
            double atrExpansion = metrics.atrExpansionAt(i);
            double volumeRatio = metrics.volumeRatioAt(i);
            double imbalance = imbalanceSize(candles, i, direction);
            int consecutive = consecutiveImpulses(candles, metrics, i, direction);
            VolumeSignal volumeSignal = volume.at(i);

            int score = 0;
            score += ScoreUtils.scale(bodyPercent, 0.45, 0.88, 25);
            score += ScoreUtils.scale(bodyExpansion, 1.15, 2.60, 22);
            score += ScoreUtils.scale(atrExpansion, 1.05, 2.20, 18);
            score += ScoreUtils.scale(volumeRatio, 1.00, 2.60, 15);
            score += ScoreUtils.scale(imbalance / metrics.atrAt(i), config.minFvgAtrSize, 0.65, 12);
            score += Math.min(8, consecutive * 3);
            if (volumeSignal != null && volumeSignal.aggressiveSide == direction) score += volumeSignal.score / 10;
            if (metrics.compressedVolatility(i - 1) && atrExpansion > 1.2) score += 6;

            score = ScoreUtils.clamp(score, 0, 100);
            boolean institutional = score >= 42
                    && bodyPercent >= 0.50
                    && isRelativeExpansion(metrics, i, bodyExpansion, atrExpansion)
                    && !isNoiseBreak(c, metrics, i);
            if (!institutional) continue;

            String quality = score >= 72 ? "strong" : score >= 54 ? "medium" : "weak";
            DisplacementEvent event = new DisplacementEvent(
                    i, direction, score, quality, bodyPercent, bodyExpansion,
                    atrExpansion, volumeRatio, imbalance, consecutive);
            events.add(event);
            byIndex[i] = event;
        }

        return new DisplacementContext(events, byIndex);
    }

    /**
     * Was this candle's expansion unusual for THIS symbol's own recent behavior, rather than
     * unusual by one fixed number applied to every symbol alike? Falls back to the old absolute
     * cutoffs while there isn't yet enough history to rank against (fresh scan windows, thin
     * candle history) so short-history behavior is unchanged from before this change.
     */
    private boolean isRelativeExpansion(MarketMetrics metrics, int index,
                                        double bodyExpansion, double atrExpansion) {
        if (!RollingPercentile.hasEnoughHistory(index, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES)) {
            return bodyExpansion >= 1.20 || atrExpansion >= 1.15;
        }
        double bodyExpansionPct = RollingPercentile.rank(metrics::bodyExpansionAt, index, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES);
        double atrExpansionPct = RollingPercentile.rank(metrics::atrExpansionAt, index, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES);
        // Still require a token absolute floor (>=1.0x, i.e. an actual expansion happened) so a
        // symbol that's been dead flat for 120 bars can't call a barely-there wiggle "the top
        // 30% of its own history" and count that as institutional.
        boolean absoluteFloor = bodyExpansion >= 1.0 || atrExpansion >= 1.0;
        return absoluteFloor
                && (bodyExpansionPct >= RELATIVE_EXPANSION_PERCENTILE || atrExpansionPct >= RELATIVE_EXPANSION_PERCENTILE);
    }

    private boolean isNoiseBreak(Candle c, MarketMetrics metrics, int index) {
        return c.range() < metrics.atrAt(index) * 0.65 || c.bodyPercent() < 0.42;
    }

    private int consecutiveImpulses(List<Candle> candles, MarketMetrics metrics, int index, Direction direction) {
        int count = 0;
        int weakAllowed = 1; // разрешаем 1 доджи/слабую свечу внутри серии
        int weakUsed = 0;
        for (int i = index; i >= 0 && i >= index - 6; i--) {
            Candle c = candles.get(i);
            boolean sameSide = direction.bullish() ? c.bullish() : c.bearish();
            boolean impulse = sameSide && c.bodyPercent() >= 0.45 && metrics.bodyExpansionAt(i) >= 0.88;
            if (impulse) {
                count++;
            } else if (count > 0 && weakUsed < weakAllowed) {
                // Пропускаем одну слабую свечу, не обрывая серию
                weakUsed++;
            } else {
                break;
            }
        }
        return count;
    }

    private double imbalanceSize(List<Candle> candles, int index, Direction direction) {
        if (index < 2) return 0.0;
        Candle c1 = candles.get(index - 2);
        Candle c3 = candles.get(index);
        if (direction.bullish() && c1.high < c3.low) return c3.low - c1.high;
        if (direction.bearish() && c1.low > c3.high) return c1.low - c3.high;
        return 0.0;
    }
}
