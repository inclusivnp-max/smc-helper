package app.common;

import java.util.function.IntToDoubleFunction;

/**
 * Symbol-relative threshold helper.
 *
 * Most engines in this codebase already normalize a raw metric against the symbol's own recent
 * average (e.g. MarketMetrics.volumeRatioAt = volume / rolling avg volume, atrExpansionAt =
 * true range / rolling ATR) — good, that removes raw price-scale dependence. What they then do
 * with that normalized ratio is compare it to a single fixed constant shared by every symbol
 * (e.g. "atrExpansion >= 1.15 is an institutional impulse", "volumeRatio >= 1.50 is a spike").
 * That still bakes in an assumption that every symbol's *distribution* of its own normalized
 * ratio looks the same — it doesn't. A naturally spiky micro-cap can clear 1.5x rolling-average
 * volume on an unremarkable afternoon; a naturally sleepy large-cap might rarely clear 1.3x even
 * on a genuinely important move. One fixed cutoff over-fires on the first symbol and under-fires
 * on the second.
 *
 * `rank` answers a scale-free question instead: "is this reading unusual *for this symbol*,
 * relative to its own recent history of the same normalized ratio?" A percentile rank means
 * roughly the same thing across symbols in a way a fixed absolute cutoff cannot.
 *
 * Deliberately backward-looking only (the window ends at `index - 1`, never includes `index`
 * itself) so callers stay walk-forward-safe for backtesting — no lookahead into the candle the
 * signal is being computed for.
 */
public final class RollingPercentile {
    private RollingPercentile() {
    }

    /**
     * Percentile rank (0..100) of valueAt(index) among valueAt(index-window .. index-1).
     * Returns a neutral 50.0 when there isn't at least {@code minSamples} of prior history yet —
     * callers should treat that as "not enough evidence either way" rather than as a real
     * percentile, typically by falling back to an absolute sanity threshold while history warms
     * up (see DisplacementEngine/VolumeEngine for the pattern).
     */
    public static double rank(IntToDoubleFunction valueAt, int index, int window, int minSamples) {
        int from = Math.max(0, index - window);
        int to = Math.max(from, index); // exclusive of `index`
        int samples = to - from;
        if (samples < Math.max(1, minSamples)) return 50.0;
        double current = valueAt.applyAsDouble(index);
        int atOrBelow = 0;
        for (int i = from; i < to; i++) {
            if (valueAt.applyAsDouble(i) <= current) atOrBelow++;
        }
        return atOrBelow * 100.0 / samples;
    }

    /** True once `index` has at least {@code minSamples} of backward-looking history to rank against. */
    public static boolean hasEnoughHistory(int index, int window, int minSamples) {
        int from = Math.max(0, index - window);
        int to = Math.max(from, index);
        return (to - from) >= Math.max(1, minSamples);
    }
}
