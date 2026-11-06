package app.deviation;

import app.common.Direction;
import app.liquidity.LiquidityMap;
import app.liquidity.LiquiditySweep;
import app.model.Candle;
import app.sessions.SessionContext;
import app.volatility.MarketMetrics;

import java.util.List;
import java.util.Locale;

public class DeviationEngine {
    public DeviationContext analyze(List<Candle> candles,
                                    MarketMetrics metrics,
                                    LiquidityMap liquidity,
                                    SessionContext session) {
        if (candles == null || candles.size() < 45 || metrics == null) return DeviationContext.NONE;

        double atr = Math.max(metrics.latestAtr(), avgRange(candles, 30));
        if (atr <= 0.0) return DeviationContext.NONE;

        Range range = rollingRange(candles, Math.min(52, candles.size() - 8), 4);
        Candidate rangeCandidate = rangeDeviation(candles, range, atr);
        Candidate liquidityCandidate = liquidityDeviation(candles, liquidity, range, atr);
        Candidate sessionCandidate = sessionDeviation(candles, session, range, atr);
        Candidate meanCandidate = meanDeviation(candles, range, atr);

        Candidate best = best(rangeCandidate, liquidityCandidate, sessionCandidate, meanCandidate);
        if (best == null || best.score < 34) return DeviationContext.NONE;

        double vwap = vwap(candles, Math.min(96, candles.size()));
        double last = candles.get(candles.size() - 1).close;
        double vwapDeviationPct = vwap <= 0.0 ? 0.0 : (last - vwap) / vwap * 100.0;
        double mean = meanClose(candles, Math.min(48, candles.size()));
        double meanDeviationAtr = (last - mean) / atr;
        double premiumDiscount = range.high <= range.low ? 0.5 : (last - range.low) / (range.high - range.low);

        int score = best.score;
        if (best.returnedInside) score += 12;
        if (best.displacementAfterReturn) score += 16;
        if (best.liquiditySweep) score += 12;
        if (session != null && session.inKillzone) score += 5;
        if (best.reversalDirection.bullish() && premiumDiscount <= 0.35) score += 5;
        if (best.reversalDirection.bearish() && premiumDiscount >= 0.65) score += 5;
        score = clamp(score, 0, 100);

        String label = switch (best.type) {
            case RANGE_HIGH -> "range high deviation";
            case RANGE_LOW -> "range low deviation";
            case LIQUIDITY -> "liquidity deviation";
            case SESSION -> "session deviation";
            case VWAP_MEAN -> "VWAP/mean deviation";
            default -> "deviation";
        };
        String narrative = label + " · " + best.reversalDirection
                + " · depth " + fmt(best.atrMultiple) + " ATR"
                + (best.returnedInside ? " · returned inside" : "")
                + (best.displacementAfterReturn ? " · displacement after return" : "")
                + (best.liquiditySweep ? " · liquidity swept" : "");

        return new DeviationContext(true, best.type, best.reversalDirection, best.index,
                range.high, range.low, best.price, best.deviationPct, best.atrMultiple,
                best.returnedInside, best.displacementAfterReturn, best.liquiditySweep,
                vwapDeviationPct, meanDeviationAtr, premiumDiscount, score, label, narrative);
    }

    private Candidate rangeDeviation(List<Candle> candles, Range range, double atr) {
        Candidate best = null;
        int from = Math.max(range.endIndex + 1, candles.size() - 14);
        Candle last = candles.get(candles.size() - 1);
        for (int i = from; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (candle.high > range.high + atr * 0.12) {
                double depth = candle.high - range.high;
                Candidate c = candidate(DeviationContext.Type.RANGE_HIGH, Direction.BEARISH, i,
                        candle.high, depth, atr, last.close < range.high, candles, range);
                best = better(best, c);
            }
            if (candle.low < range.low - atr * 0.12) {
                double depth = range.low - candle.low;
                Candidate c = candidate(DeviationContext.Type.RANGE_LOW, Direction.BULLISH, i,
                        candle.low, depth, atr, last.close > range.low, candles, range);
                best = better(best, c);
            }
        }
        return best;
    }

    private Candidate liquidityDeviation(List<Candle> candles, LiquidityMap liquidity, Range range, double atr) {
        if (liquidity == null || liquidity.sweeps == null || liquidity.sweeps.isEmpty()) return null;
        for (int i = liquidity.sweeps.size() - 1; i >= 0; i--) {
            LiquiditySweep sweep = liquidity.sweeps.get(i);
            if (sweep.index < candles.size() - 18 || sweep.index < 0 || sweep.index >= candles.size()) continue;
            Candle candle = candles.get(sweep.index);
            double level = sweep.level == null ? (sweep.reversalDirection.bullish() ? range.low : range.high) : sweep.level.price;
            double price = sweep.reversalDirection.bullish() ? candle.low : candle.high;
            double depth = Math.abs(price - level);
            Candidate c = candidate(DeviationContext.Type.LIQUIDITY, sweep.reversalDirection, sweep.index,
                    price, Math.max(depth, atr * 0.10), atr, sweep.closeReclaimed, candles, range);
            c.liquiditySweep = true;
            c.score += Math.min(22, Math.max(0, sweep.score / 4));
            if (sweep.displacementConfirmed) c.displacementAfterReturn = true;
            return c;
        }
        return null;
    }

    private Candidate sessionDeviation(List<Candle> candles, SessionContext session, Range fallbackRange, double atr) {
        if (session == null || !Double.isFinite(session.asiaHigh) || !Double.isFinite(session.asiaLow)) return null;
        Range asia = new Range(Math.max(0, Math.min(session.asiaHighIndex, session.asiaLowIndex)),
                Math.max(session.asiaHighIndex, session.asiaLowIndex), session.asiaHigh, session.asiaLow);
        Candidate c = rangeDeviation(candles, asia.high > asia.low ? asia : fallbackRange, atr);
        if (c == null) return null;
        c.type = DeviationContext.Type.SESSION;
        c.score += session.inKillzone ? 12 : 4;
        return c;
    }

    private Candidate meanDeviation(List<Candle> candles, Range range, double atr) {
        Candle last = candles.get(candles.size() - 1);
        double mean = meanClose(candles, Math.min(48, candles.size()));
        double vwap = vwap(candles, Math.min(96, candles.size()));
        double anchor = vwap > 0.0 ? vwap : mean;
        double deviation = last.close - anchor;
        if (Math.abs(deviation) < atr * 1.35) return null;
        Direction direction = deviation < 0.0 ? Direction.BULLISH : Direction.BEARISH;
        Candidate c = candidate(DeviationContext.Type.VWAP_MEAN, direction, candles.size() - 1,
                last.close, Math.abs(deviation), atr, true, candles, range);
        c.score -= 8;
        return c;
    }

    private Candidate candidate(DeviationContext.Type type,
                                Direction direction,
                                int index,
                                double price,
                                double depth,
                                double atr,
                                boolean returnedInside,
                                List<Candle> candles,
                                Range range) {
        Candidate c = new Candidate();
        c.type = type;
        c.reversalDirection = direction;
        c.index = index;
        c.price = price;
        c.atrMultiple = depth / Math.max(atr, 0.00000001);
        c.deviationPct = price <= 0.0 ? 0.0 : depth / price * 100.0;
        c.returnedInside = returnedInside;
        c.displacementAfterReturn = displacementAfter(candles, index, direction, atr);
        c.score = 34 + clamp((int) Math.round(c.atrMultiple * 24.0), 0, 26);
        if (type == DeviationContext.Type.RANGE_HIGH || type == DeviationContext.Type.RANGE_LOW) {
            double widthAtr = (range.high - range.low) / Math.max(atr, 0.00000001);
            if (widthAtr <= 5.0) c.score += 8;
        }
        return c;
    }

    private boolean displacementAfter(List<Candle> candles, int index, Direction direction, double atr) {
        int from = Math.max(0, index);
        int to = Math.min(candles.size(), index + 5);
        for (int i = from; i < to; i++) {
            Candle c = candles.get(i);
            boolean rightDirection = direction.bullish() ? c.bullish() : c.bearish();
            if (rightDirection && c.body() >= atr * 0.42 && c.bodyPercent() >= 0.48) return true;
        }
        return false;
    }

    private Range rollingRange(List<Candle> candles, int lookback, int excludeLast) {
        int end = Math.max(1, candles.size() - excludeLast);
        int start = Math.max(0, end - lookback);
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (int i = start; i < end; i++) {
            Candle c = candles.get(i);
            high = Math.max(high, c.high);
            low = Math.min(low, c.low);
        }
        if (!Double.isFinite(high) || !Double.isFinite(low)) {
            Candle last = candles.get(candles.size() - 1);
            high = last.high;
            low = last.low;
        }
        return new Range(start, end - 1, high, low);
    }

    private Candidate best(Candidate... candidates) {
        Candidate best = null;
        for (Candidate c : candidates) best = better(best, c);
        return best;
    }

    private Candidate better(Candidate a, Candidate b) {
        if (b == null) return a;
        if (a == null) return b;
        return b.score > a.score ? b : a;
    }

    private double avgRange(List<Candle> candles, int lookback) {
        int from = Math.max(0, candles.size() - lookback);
        double sum = 0.0;
        int count = 0;
        for (int i = from; i < candles.size(); i++) {
            sum += candles.get(i).range();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double meanClose(List<Candle> candles, int lookback) {
        int from = Math.max(0, candles.size() - lookback);
        double sum = 0.0;
        int count = 0;
        for (int i = from; i < candles.size(); i++) {
            sum += candles.get(i).close;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double vwap(List<Candle> candles, int lookback) {
        int from = Math.max(0, candles.size() - lookback);
        double pv = 0.0;
        double vol = 0.0;
        for (int i = from; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double v = Math.max(0.0, c.volume);
            double typical = (c.high + c.low + c.close) / 3.0;
            pv += typical * v;
            vol += v;
        }
        return vol <= 0.0 ? 0.0 : pv / vol;
    }

    private int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static class Range {
        final int startIndex;
        final int endIndex;
        final double high;
        final double low;

        Range(int startIndex, int endIndex, double high, double low) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.high = high;
            this.low = low;
        }
    }

    private static class Candidate {
        DeviationContext.Type type;
        Direction reversalDirection;
        int index;
        double price;
        double deviationPct;
        double atrMultiple;
        boolean returnedInside;
        boolean displacementAfterReturn;
        boolean liquiditySweep;
        int score;
    }
}
