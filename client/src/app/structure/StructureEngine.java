package app.structure;

import app.common.Direction;
import app.common.EngineConfig;
import app.common.ScoreUtils;
import app.displacement.DisplacementContext;
import app.displacement.DisplacementEvent;
import app.model.Candle;
import app.volatility.MarketMetrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StructureEngine {
    private final EngineConfig config;

    public StructureEngine(EngineConfig config) {
        this.config = config;
    }

    public MarketStructure analyze(List<Candle> candles, MarketMetrics metrics,
                                   DisplacementContext displacement, String trendMethod) {
        List<SwingPoint> internal = findSwings(candles, metrics,
                config.swingInternalLeft, config.swingInternalRight, false);
        List<SwingPoint> external = findSwings(candles, metrics,
                config.swingExternalLeft, config.swingExternalRight, true);
        List<StructureEvent> events = detectBreaks(candles, metrics, displacement, internal);

        Direction bias = inferBias(candles, internal, external, events, trendMethod);
        int strength = scoreBias(candles, metrics, bias, internal, external, events);
        SwingPoint protectedHigh = latestSwing(external.isEmpty() ? internal : external, true);
        SwingPoint protectedLow = latestSwing(external.isEmpty() ? internal : external, false);

        return new MarketStructure(bias, strength, protectedHigh, protectedLow, internal, external, events);
    }

    public List<SwingPoint> findSwings(List<Candle> candles, MarketMetrics metrics,
                                       int left, int right, boolean external) {
        List<SwingPoint> swings = new ArrayList<>();
        for (int i = left; i < candles.size() - right; i++) {
            Candle current = candles.get(i);
            boolean high = true;
            boolean low = true;
            for (int j = i - left; j <= i + right; j++) {
                if (j == i) continue;
                Candle neighbor = candles.get(j);
                if (neighbor.high >= current.high) high = false;
                if (neighbor.low <= current.low) low = false;
                if (!high && !low) break;
            }
            double prominence = swingProminence(candles, i, left, right, current);
            int strength = ScoreUtils.clamp(20 + ScoreUtils.scale(prominence / metrics.atrAt(i), 0.2, 1.8, 60)
                    + (external ? 15 : 0), 0, 100);
            if (high) swings.add(new SwingPoint(i, current.high, true, external, strength));
            if (low) swings.add(new SwingPoint(i, current.low, false, external, strength));
        }
        return swings;
    }

    private List<StructureEvent> detectBreaks(List<Candle> candles, MarketMetrics metrics,
                                              DisplacementContext displacement, List<SwingPoint> swings) {
        // Index swings by candle position for O(1) lookup instead of O(n) scan per bar
        Map<Integer, SwingPoint> highByIndex = new HashMap<>();
        Map<Integer, SwingPoint> lowByIndex  = new HashMap<>();
        for (SwingPoint swing : swings) {
            if (swing.high) highByIndex.merge(swing.index, swing,
                    (a, b) -> a.strength >= b.strength ? a : b);
            else             lowByIndex.merge(swing.index, swing,
                    (a, b) -> a.strength >= b.strength ? a : b);
        }

        List<StructureEvent> events = new ArrayList<>();
        Direction activeBias = Direction.NEUTRAL;
        SwingPoint lastHigh = null;
        SwingPoint lastLow = null;

        for (int i = 0; i < candles.size(); i++) {
            SwingPoint h = highByIndex.get(i - 1);
            SwingPoint l = lowByIndex.get(i - 1);
            if (h != null) lastHigh = h;
            if (l != null) lastLow  = l;

            Candle c = candles.get(i);
            double tolerance = metrics.atrAt(i) * 0.04;
            if (lastHigh != null && c.close > lastHigh.price + tolerance) {
                StructureEvent event = buildEvent(i, Direction.BULLISH, lastHigh.price,
                        activeBias == Direction.BEARISH ? "CHoCH" : "BOS",
                        displacement, metrics, false);
                if (event != null) {
                    events.add(event);
                    activeBias = Direction.BULLISH;
                    lastHigh = null;
                }
            }
            if (lastLow != null && c.close < lastLow.price - tolerance) {
                StructureEvent event = buildEvent(i, Direction.BEARISH, lastLow.price,
                        activeBias == Direction.BULLISH ? "CHoCH" : "BOS",
                        displacement, metrics, false);
                if (event != null) {
                    events.add(event);
                    activeBias = Direction.BEARISH;
                    lastLow = null;
                }
            }
        }
        return events;
    }

    private StructureEvent buildEvent(int index, Direction direction, double level, String type,
                                      DisplacementContext displacement, MarketMetrics metrics, boolean internal) {
        DisplacementEvent event = displacement.bestAfter(index - 3, direction, 4);
        if (event == null && displacement.at(index) != null && displacement.at(index).direction == direction) {
            event = displacement.at(index);
        }
        int dispScore = event == null ? 0 : event.score;
        boolean bodyConfirmed = dispScore >= 42;
        if (!bodyConfirmed && metrics.normalizedRangeAt(index) < 1.1) return null;

        int score = 25 + dispScore / 2;
        if ("CHoCH".equals(type)) score += 10;
        if (bodyConfirmed) score += 15;
        score = ScoreUtils.clamp(score, 0, 100);
        if (score < 45) return null;
        return new StructureEvent(index, type, direction, level, dispScore, bodyConfirmed, internal, score);
    }

    private Direction inferBias(List<Candle> candles, List<SwingPoint> internal,
                                List<SwingPoint> external, List<StructureEvent> events, String trendMethod) {
        StructureEvent latest = events.isEmpty() ? null : events.get(events.size() - 1);
        if (latest != null && latest.score >= 55) return latest.direction;

        if ("SMA (50)".equals(trendMethod) && candles.size() >= 50) {
            double sum = 0.0;
            for (int i = candles.size() - 50; i < candles.size(); i++) sum += candles.get(i).close;
            return candles.get(candles.size() - 1).close >= sum / 50.0 ? Direction.BULLISH : Direction.BEARISH;
        }

        List<SwingPoint> source = external.size() >= 4 ? external : internal;
        SwingPoint lastHigh = latestSwing(source, true);
        SwingPoint prevHigh = previousSwing(source, lastHigh, true);
        SwingPoint lastLow = latestSwing(source, false);
        SwingPoint prevLow = previousSwing(source, lastLow, false);
        if (lastHigh != null && prevHigh != null && lastLow != null && prevLow != null) {
            if (lastHigh.price > prevHigh.price && lastLow.price > prevLow.price) return Direction.BULLISH;
            if (lastHigh.price < prevHigh.price && lastLow.price < prevLow.price) return Direction.BEARISH;
        }

        return candles.get(candles.size() - 1).close >= candles.get(0).close ? Direction.BULLISH : Direction.BEARISH;
    }

    private int scoreBias(List<Candle> candles, MarketMetrics metrics, Direction bias,
                          List<SwingPoint> internal, List<SwingPoint> external, List<StructureEvent> events) {
        int score = 35;
        StructureEvent latest = events.isEmpty() ? null : events.get(events.size() - 1);
        if (latest != null && latest.direction == bias) score += latest.score / 3;
        if (!external.isEmpty()) score += 10;
        if (metrics.normalizedRangeAt(candles.size() - 1) >= 0.9) score += 8;
        SwingPoint high = latestSwing(external.isEmpty() ? internal : external, true);
        SwingPoint low = latestSwing(external.isEmpty() ? internal : external, false);
        if (high != null && low != null) score += (high.strength + low.strength) / 12;
        return ScoreUtils.clamp(score, 0, 100);
    }

    private double swingProminence(List<Candle> candles, int index, int left, int right, Candle current) {
        double maxNeighborHigh = -Double.MAX_VALUE;
        double minNeighborLow = Double.MAX_VALUE;
        for (int j = index - left; j <= index + right; j++) {
            if (j == index) continue;
            maxNeighborHigh = Math.max(maxNeighborHigh, candles.get(j).high);
            minNeighborLow = Math.min(minNeighborLow, candles.get(j).low);
        }
        return Math.max(Math.abs(current.high - maxNeighborHigh), Math.abs(current.low - minNeighborLow));
    }

    private SwingPoint latestSwing(List<SwingPoint> swings, boolean high) {
        for (int i = swings.size() - 1; i >= 0; i--) {
            SwingPoint swing = swings.get(i);
            if (swing.high == high) return swing;
        }
        return null;
    }

    private SwingPoint previousSwing(List<SwingPoint> swings, SwingPoint current, boolean high) {
        if (current == null) return null;
        for (int i = swings.size() - 1; i >= 0; i--) {
            SwingPoint swing = swings.get(i);
            if (swing.high == high && swing.index < current.index) return swing;
        }
        return null;
    }
}
