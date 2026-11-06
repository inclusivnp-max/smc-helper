package app.liquidity;

import app.common.Direction;
import app.common.EngineConfig;
import app.common.RollingPercentile;
import app.common.ScoreUtils;
import app.displacement.DisplacementContext;
import app.displacement.DisplacementEvent;
import app.model.Candle;
import app.sessions.SessionContext;
import app.structure.MarketStructure;
import app.structure.SwingPoint;
import app.volatility.MarketMetrics;
import app.volume.VolumeContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LiquidityEngine {
    // See RollingPercentile's class doc. Sweep pierce/reclaim geometry (config.sweepAtrTolerance)
    // stays a fixed ATR-multiple -- it's a measurement-slack constant, not an "is this unusual"
    // judgment. The wick-rejection quality gate below IS such a judgment (how much wick rejection
    // counts as a real sweep vs. routine noise), and unlike the geometry it's naturally
    // symbol-character-dependent, so it gets the relative treatment.
    private static final int RELATIVE_WINDOW = 120;
    private static final int RELATIVE_MIN_SAMPLES = 30;
    private static final double RELATIVE_WICK_PERCENTILE = 65.0;
    private static final double ABSOLUTE_WICK_FALLBACK = 0.42;
    private static final double ABSOLUTE_WICK_FLOOR = 0.15;

    private final EngineConfig config;

    public LiquidityEngine(EngineConfig config) {
        this.config = config;
    }

    public LiquidityMap analyze(List<Candle> ltf, List<Candle> htf,
                                MarketMetrics metrics,
                                MarketStructure ltfStructure,
                                MarketStructure htfStructure,
                                DisplacementContext displacement,
                                VolumeContext volume,
                                SessionContext session) {
        List<LiquidityLevel> bsl = new ArrayList<>();
        List<LiquidityLevel> ssl = new ArrayList<>();

        addEqualLevels(bsl, ssl, ltfStructure.internalSwings, metrics, "internal");
        addEqualLevels(bsl, ssl, ltfStructure.externalSwings, metrics, "external");
        addHtfLevels(bsl, ssl, htf, ltf);
        addSessionLevels(bsl, ssl, session, ltf.size() - 1);
        mergeNearby(bsl, metrics.latestAtr());
        mergeNearby(ssl, metrics.latestAtr());

        List<LiquiditySweep> sweeps = detectSweeps(ltf, metrics, displacement, volume, session, bsl, ssl);
        List<InducementEvent> inducements = detectInducements(ltf, metrics, ltfStructure, bsl, ssl);

        return new LiquidityMap(limit(bsl), limit(ssl), sweeps, inducements);
    }

    private void addEqualLevels(List<LiquidityLevel> bsl, List<LiquidityLevel> ssl,
                                List<SwingPoint> swings, MarketMetrics metrics, String scope) {
        List<SwingPoint> highs = new ArrayList<>();
        List<SwingPoint> lows = new ArrayList<>();
        for (SwingPoint swing : swings) {
            if (swing.high) highs.add(swing); else lows.add(swing);
        }
        groupSwings(highs, true, scope, metrics, bsl);
        groupSwings(lows, false, scope, metrics, ssl);
    }

    private void groupSwings(List<SwingPoint> swings, boolean highs, String scope,
                             MarketMetrics metrics, List<LiquidityLevel> out) {
        swings.sort(Comparator.comparingDouble(s -> s.price));
        boolean[] used = new boolean[swings.size()];
        for (int i = 0; i < swings.size(); i++) {
            if (used[i]) continue;
            SwingPoint base = swings.get(i);
            double tolerance = metrics.atrAt(base.index) * config.equalLiquidityAtrTolerance;
            List<SwingPoint> group = new ArrayList<>();
            for (int j = i; j < swings.size(); j++) {
                SwingPoint candidate = swings.get(j);
                if (Math.abs(candidate.price - base.price) <= tolerance) {
                    group.add(candidate);
                    used[j] = true;
                }
            }
            if (group.size() < config.equalLiquidityMinTouches) continue;

            double price = 0.0;
            int first = Integer.MAX_VALUE;
            int last = -1;
            int rawStrength = 0;
            for (SwingPoint swing : group) {
                price += swing.price;
                first = Math.min(first, swing.index);
                last = Math.max(last, swing.index);
                rawStrength += swing.strength;
            }
            price /= group.size();
            int ageScore = ScoreUtils.scale(last, 0, metrics.size() - 1, 12);
            int strength = ScoreUtils.clamp(25 + group.size() * 14 + rawStrength / Math.max(1, group.size() * 4)
                    + ("external".equals(scope) ? 18 : 0) + ageScore, 0, 100);
            String label = highs ? "EQH / buy-side liquidity" : "EQL / sell-side liquidity";
            out.add(new LiquidityLevel(highs ? "BSL" : "SSL", scope, label, price, strength, group.size(), first, last));
        }
    }

    private void addHtfLevels(List<LiquidityLevel> bsl, List<LiquidityLevel> ssl, List<Candle> htf, List<Candle> ltf) {
        if (htf.isEmpty() || ltf.isEmpty()) return;
        int lookback = Math.min(80, htf.size());
        int startIdx = htf.size() - lookback;
        double high = -Double.MAX_VALUE;
        double low = Double.MAX_VALUE;
        int highIdx = startIdx;
        int lowIdx  = startIdx;
        for (int i = startIdx; i < htf.size(); i++) {
            Candle c = htf.get(i);
            if (c.high > high) { high = c.high; highIdx = i; }
            if (c.low  < low)  { low  = c.low;  lowIdx  = i; }
        }

        // Strength = base 55 + up to 30 pts from touch count + up to 15 pts from recency
        double tolerance = (high - low) * 0.002;
        int highTouches = 0;
        int lowTouches  = 0;
        for (int i = startIdx; i < htf.size(); i++) {
            Candle c = htf.get(i);
            if (Math.abs(c.high - high) <= tolerance) highTouches++;
            if (Math.abs(c.low  - low)  <= tolerance) lowTouches++;
        }
        int highRecency = ScoreUtils.scale(highIdx, startIdx, htf.size() - 1, 15);
        int lowRecency  = ScoreUtils.scale(lowIdx,  startIdx, htf.size() - 1, 15);
        int highStrength = ScoreUtils.clamp(55 + Math.min(highTouches - 1, 3) * 10 + highRecency, 0, 100);
        int lowStrength  = ScoreUtils.clamp(55 + Math.min(lowTouches  - 1, 3) * 10 + lowRecency,  0, 100);

        bsl.add(new LiquidityLevel("BSL", "external", "HTF high liquidity", high, highStrength, highTouches, startIdx, highIdx));
        ssl.add(new LiquidityLevel("SSL", "external", "HTF low liquidity",  low,  lowStrength,  lowTouches,  startIdx, lowIdx));
    }

    private void addSessionLevels(List<LiquidityLevel> bsl, List<LiquidityLevel> ssl,
                                  SessionContext session, int lastIndex) {
        if (session == null) return;
        if (!Double.isNaN(session.asiaHigh)) {
            int idx = Math.max(0, session.asiaHighIndex);
            bsl.add(new LiquidityLevel("BSL", "session", "Asia high", session.asiaHigh, 74, 1,
                    idx, idx));
        }
        if (!Double.isNaN(session.asiaLow)) {
            int idx = Math.max(0, session.asiaLowIndex);
            ssl.add(new LiquidityLevel("SSL", "session", "Asia low", session.asiaLow, 74, 1,
                    idx, idx));
        }
        if (!Double.isNaN(session.sessionHigh)) {
            bsl.add(new LiquidityLevel("BSL", "session", "Current session high", session.sessionHigh, 62, 1,
                    lastIndex, lastIndex));
        }
        if (!Double.isNaN(session.sessionLow)) {
            ssl.add(new LiquidityLevel("SSL", "session", "Current session low", session.sessionLow, 62, 1,
                    lastIndex, lastIndex));
        }
    }

    private List<LiquiditySweep> detectSweeps(List<Candle> candles, MarketMetrics metrics,
                                              DisplacementContext displacement, VolumeContext volume,
                                              SessionContext session,
                                              List<LiquidityLevel> bsl, List<LiquidityLevel> ssl) {
        List<LiquiditySweep> sweeps = new ArrayList<>();
        int start = Math.max(1, candles.size() - 160);
        for (int i = start; i < candles.size(); i++) {
            Candle c = candles.get(i);
            for (LiquidityLevel level : bsl) {
                if (level.lastIndex >= i) continue;
                // Минимальный gap: уровень должен быть сформирован ≥2 баров назад,
                // чтобы избежать "sweep" сразу же после формирования уровня.
                if (i - level.lastIndex < 2) continue;
                double tolerance = metrics.atrAt(i) * config.sweepAtrTolerance;
                boolean pierced = c.high > level.price + tolerance;
                boolean reclaimed = c.close < level.price - tolerance * 0.25;
                if (!pierced || !reclaimed) continue;
                double wickRatio = c.range() == 0.0 ? 0.0 : c.upperWick() / c.range();
                DisplacementEvent disp = displacement.bestAfter(i, Direction.BEARISH, config.sweepDisplacementLookahead);
                int score = sweepScore(level, wickRatio, disp, volume.scoreAt(i), session);
                if (score < 48 || (disp == null && !isRelativeWickRejection(candles, i, wickRatio, true))) continue;
                markSwept(level, i, score);
                sweeps.add(new LiquiditySweep(i, Direction.BEARISH, level, score, wickRatio,
                        true, disp != null, session == null ? "" : session.activeSession));
            }
            for (LiquidityLevel level : ssl) {
                if (level.lastIndex >= i) continue;
                if (i - level.lastIndex < 2) continue;
                double tolerance = metrics.atrAt(i) * config.sweepAtrTolerance;
                boolean pierced = c.low < level.price - tolerance;
                boolean reclaimed = c.close > level.price + tolerance * 0.25;
                if (!pierced || !reclaimed) continue;
                double wickRatio = c.range() == 0.0 ? 0.0 : c.lowerWick() / c.range();
                DisplacementEvent disp = displacement.bestAfter(i, Direction.BULLISH, config.sweepDisplacementLookahead);
                int score = sweepScore(level, wickRatio, disp, volume.scoreAt(i), session);
                if (score < 48 || (disp == null && !isRelativeWickRejection(candles, i, wickRatio, false))) continue;
                markSwept(level, i, score);
                sweeps.add(new LiquiditySweep(i, Direction.BULLISH, level, score, wickRatio,
                        true, disp != null, session == null ? "" : session.activeSession));
            }
        }
        sweeps.sort(Comparator.comparingInt(s -> s.index));
        return sweeps;
    }

    private int sweepScore(LiquidityLevel level, double wickRatio, DisplacementEvent displacement,
                           int volumeScore, SessionContext session) {
        int score = 20;
        score += level.strength / 3;
        score += ScoreUtils.scale(wickRatio, 0.28, 0.72, 22);
        score += displacement == null ? 0 : displacement.score / 3;
        score += volumeScore / 8;
        if ("external".equals(level.scope)) score += 12;
        if ("session".equals(level.scope)) score += 8;
        if (session != null && session.inKillzone) score += 8;
        return ScoreUtils.clamp(score, 0, 100);
    }

    /**
     * Was this candle's wick rejection unusual for THIS symbol's own recent candles, rather than
     * unusual by one fixed ratio applied to every symbol alike? A symbol whose candles routinely
     * have long wicks needs a deeper rejection than 0.42 to mean something; a clean-candled one
     * might show real rejection well below it. Falls back to the old absolute 0.42 cutoff while
     * there isn't enough history yet.
     */
    private boolean isRelativeWickRejection(List<Candle> candles, int index, double wickRatio, boolean upperWick) {
        if (!RollingPercentile.hasEnoughHistory(index, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES)) {
            return wickRatio >= ABSOLUTE_WICK_FALLBACK;
        }
        double percentile = RollingPercentile.rank(
                i -> upperWick ? wickRatioAt(candles, i, true) : wickRatioAt(candles, i, false),
                index, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES);
        // Token absolute floor so a symbol whose candles have barely any wicks for 120 bars can't
        // call an ordinary 15%-wick candle "the top 35% of its own history" and call it rejection.
        return wickRatio >= ABSOLUTE_WICK_FLOOR && percentile >= RELATIVE_WICK_PERCENTILE;
    }

    private double wickRatioAt(List<Candle> candles, int index, boolean upperWick) {
        Candle c = candles.get(index);
        if (c.range() == 0.0) return 0.0;
        return upperWick ? c.upperWick() / c.range() : c.lowerWick() / c.range();
    }

    private void markSwept(LiquidityLevel level, int index, int score) {
        level.swept = true;
        level.touched = true;
        level.sweptIndex = index;
        level.sweepScore = Math.max(level.sweepScore, score);
    }

    private List<InducementEvent> detectInducements(List<Candle> candles, MarketMetrics metrics,
                                                    MarketStructure structure,
                                                    List<LiquidityLevel> bsl,
                                                    List<LiquidityLevel> ssl) {
        List<InducementEvent> events = new ArrayList<>();
        int start = Math.max(0, structure.internalSwings.size() - 30);
        for (int i = start; i < structure.internalSwings.size(); i++) {
            SwingPoint swing = structure.internalSwings.get(i);
            if (swing.strength > 58) continue;
            if (swing.index < candles.size() - 130) continue;
            Candle c = candles.get(swing.index);
            boolean nearOppositeLiquidity = swing.high
                    ? hasNearby(bsl, swing.price, metrics.atrAt(swing.index) * 1.2)
                    : hasNearby(ssl, swing.price, metrics.atrAt(swing.index) * 1.2);
            if (!nearOppositeLiquidity) continue;
            Direction expected = swing.high ? Direction.BEARISH : Direction.BULLISH;
            int score = ScoreUtils.clamp(38 + (58 - swing.strength) + (nearOppositeLiquidity ? 18 : 0), 0, 100);
            String type = swing.high ? "weak LH / buy-side bait" : "weak HL / sell-side bait";
            events.add(new InducementEvent(swing.index, expected, swing.high ? c.high : c.low, type, score,
                    "Minor structure created tradable liquidity before the real move."));
        }
        return events;
    }

    private boolean hasNearby(List<LiquidityLevel> levels, double price, double tolerance) {
        for (LiquidityLevel level : levels) {
            if (Math.abs(level.price - price) <= tolerance) return true;
        }
        return false;
    }

    private void mergeNearby(List<LiquidityLevel> levels, double atr) {
        levels.sort(Comparator.comparingDouble(l -> l.price));
        int n = levels.size();
        boolean[] consumed = new boolean[n];
        double mergeTolerance = atr * 0.16;

        for (int i = 0; i < n; i++) {
            if (consumed[i]) continue;
            LiquidityLevel a = levels.get(i);
            // Only look forward — list is sorted by price, so nearby candidates follow
            for (int j = i + 1; j < n; j++) {
                if (consumed[j]) continue;
                LiquidityLevel b = levels.get(j);
                if (b.price - a.price > mergeTolerance) break; // sorted: no more matches
                if (!a.side.equals(b.side)) continue;
                // Absorb weaker into stronger
                LiquidityLevel stronger = a.strength >= b.strength ? a : b;
                LiquidityLevel weaker   = a.strength >= b.strength ? b : a;
                stronger.touched    |= weaker.touched;
                stronger.swept      |= weaker.swept;
                stronger.sweepScore  = Math.max(stronger.sweepScore, weaker.sweepScore);
                consumed[b == weaker ? j : i] = true;
                if (weaker == a) break; // a was consumed; move outer loop forward
            }
        }

        // Rebuild list from survivors, preserving sorted order
        List<LiquidityLevel> merged = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!consumed[i]) merged.add(levels.get(i));
        }
        levels.clear();
        levels.addAll(merged);
    }

    private List<LiquidityLevel> limit(List<LiquidityLevel> levels) {
        levels.sort(Comparator.comparingInt((LiquidityLevel l) -> l.strength).reversed());
        if (levels.size() <= config.maxLiquidityLevels) return levels;
        return new ArrayList<>(levels.subList(0, config.maxLiquidityLevels));
    }
}
