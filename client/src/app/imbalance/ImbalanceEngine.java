package app.imbalance;

import app.common.Direction;
import app.common.RollingPercentile;
import app.liquidity.LiquidityLevel;
import app.liquidity.LiquidityMap;
import app.model.Candle;
import app.model.Zone;
import app.orderbook.OrderBookContext;
import app.orderflow.OrderFlowContext;
import app.volatility.MarketMetrics;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ImbalanceEngine {
    // See RollingPercentile's class doc. FootprintCandle.deltaPercent is already normalized
    // (share of volume that was one-sided), but "a fixed |delta| >= 0.18 means something" still
    // assumes every symbol's order flow is equally lopsided on a normal bar, which it isn't.
    private static final int RELATIVE_WINDOW = 120;
    private static final int RELATIVE_MIN_SAMPLES = 30;
    private static final double RELATIVE_DELTA_PERCENTILE = 70.0;
    private static final double ABSOLUTE_DELTA_FALLBACK = 0.18;
    private static final double ABSOLUTE_DELTA_FLOOR = 0.05;
    public ImbalanceContext analyze(List<Candle> candles,
                                    List<Zone> zones,
                                    MarketMetrics metrics,
                                    OrderFlowContext orderFlow,
                                    OrderBookContext orderBook,
                                    LiquidityMap liquidity) {
        if (candles == null || candles.size() < 12 || metrics == null) return ImbalanceContext.NONE;
        Candle last = candles.get(candles.size() - 1);
        Direction candleDirection = last.bullish() ? Direction.BULLISH : last.bearish() ? Direction.BEARISH : Direction.NEUTRAL;

        int candleScore = candleScore(candles, metrics);
        Zone freshFvg = bestFreshFvg(zones, candles.size());
        Direction fvgDirection = freshFvg == null ? Direction.NEUTRAL : freshFvg.bullish ? Direction.BULLISH : Direction.BEARISH;
        int fvgScore = fvgScore(freshFvg, last.close);
        double fvgFilled = fvgFilledPercent(freshFvg, last.close);

        Direction deltaDirection = deltaDirection(orderFlow);
        int deltaScore = orderFlow == null ? 0 : Math.max(0, Math.min(100,
                35 + (int) Math.round(orderFlow.latestDeltaPercent * 48.0)
                        + Math.min(18, Math.abs(orderFlow.buyImbalanceCount - orderFlow.sellImbalanceCount) * 3)
                        + (orderFlow.latestTickBacked ? 8 : 0)));

        Direction bookDirection = bookDirection(orderBook);
        int bookScore = orderBook == null || !orderBook.available ? 0 : orderBook.score;

        Direction liquidityDirection = liquidityDirection(liquidity, last.close);
        int liquidityScore = liquidityDirection == Direction.NEUTRAL ? 0 : 56;

        Direction direction = vote(candleDirection, candleScore, fvgDirection, fvgScore,
                deltaDirection, deltaScore, bookDirection, bookScore, liquidityDirection, liquidityScore);
        int score = aggregateScore(direction, candleDirection, candleScore, fvgDirection, fvgScore,
                deltaDirection, deltaScore, bookDirection, bookScore, liquidityDirection, liquidityScore);
        boolean absorption = orderFlow != null && orderFlow.absorptionCount > 0;
        if (absorption && score > 0) score += 4;
        score = Math.max(0, Math.min(100, score));

        if (direction == Direction.NEUTRAL || score < 28) return ImbalanceContext.NONE;
        String label = direction.bullish() ? "bullish imbalance" : "bearish imbalance";
        String narrative = label
                + " · candle " + candleScore
                + " · FVG " + fvgScore + " filled " + fmt(fvgFilled) + "%"
                + " · delta " + deltaScore
                + " · DOM " + bookScore
                + " · liquidity " + liquidityScore;
        return new ImbalanceContext(true, direction, score, candleScore, fvgScore, deltaScore,
                bookScore, liquidityScore, fvgFilled, absorption, label, narrative);
    }

    private int candleScore(List<Candle> candles, MarketMetrics metrics) {
        Candle last = candles.get(candles.size() - 1);
        double atr = Math.max(metrics.latestAtr(), avgRange(candles, 30));
        int score = 25;
        if (last.bodyPercent() >= 0.55) score += 22;
        if (atr > 0.0 && last.range() >= atr * 1.25) score += 18;
        if (last.volume > avgVolume(candles, 30) * 1.25) score += 12;
        // Opposing wick = давление со стороны противника
        // Маленький фитиль → чистый импульс (+8), большой → явное отторжение (-8)
        double wickAgainst = last.bullish() ? last.upperWick() : last.bearish() ? last.lowerWick() : 0.0;
        double wickRatioAgainst = last.range() > 0.0 ? wickAgainst / last.range() : 0.0;
        if (wickRatioAgainst <= 0.28) score += 8;
        else if (wickRatioAgainst >= 0.45) score -= 8;
        return Math.max(0, Math.min(100, score));
    }

    private Zone bestFreshFvg(List<Zone> zones, int candleCount) {
        if (zones == null || zones.isEmpty()) return null;
        return zones.stream()
                .filter(z -> "FVG".equals(z.type))
                .filter(z -> !z.mitigated)
                .filter(z -> z.creationIndex >= candleCount - 80)
                .max(Comparator.comparingInt(z -> z.score + z.displacementScore))
                .orElse(null);
    }

    private int fvgScore(Zone fvg, double price) {
        if (fvg == null) return 0;
        double filled = fvgFilledPercent(fvg, price);
        int score = 35 + Math.min(28, fvg.displacementScore / 3) + Math.min(18, fvg.score / 5);
        if (filled > 20.0 && filled < 78.0) score += 10;
        if (filled >= 90.0) score -= 12;
        return Math.max(0, Math.min(100, score));
    }

    private double fvgFilledPercent(Zone fvg, double price) {
        if (fvg == null || fvg.height() <= 0.0 || price <= 0.0) return 0.0;
        if (fvg.bullish) {
            if (price >= fvg.top) return 0.0;
            if (price <= fvg.bottom) return 100.0;
            return (fvg.top - price) / fvg.height() * 100.0;
        }
        if (price <= fvg.bottom) return 0.0;
        if (price >= fvg.top) return 100.0;
        return (price - fvg.bottom) / fvg.height() * 100.0;
    }

    private Direction deltaDirection(OrderFlowContext orderFlow) {
        if (orderFlow == null || orderFlow.candles.isEmpty() || !isRelativelySignificantDelta(orderFlow)) {
            return Direction.NEUTRAL;
        }
        if (orderFlow.latestDelta > 0.0) return Direction.BULLISH;
        if (orderFlow.latestDelta < 0.0) return Direction.BEARISH;
        return Direction.NEUTRAL;
    }

    /**
     * Is the latest bar's order-flow delta unusual for THIS symbol's own recent footprint
     * history, rather than unusual by one fixed number applied to every symbol alike? Falls
     * back to the old absolute 0.18 cutoff while there isn't enough footprint history yet.
     */
    private boolean isRelativelySignificantDelta(OrderFlowContext orderFlow) {
        double magnitude = Math.abs(orderFlow.latestDeltaPercent);
        int lastIndex = orderFlow.candles.size() - 1;
        if (!RollingPercentile.hasEnoughHistory(lastIndex, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES)) {
            return magnitude >= ABSOLUTE_DELTA_FALLBACK;
        }
        double percentile = RollingPercentile.rank(
                i -> orderFlow.at(i) == null ? 0.0 : orderFlow.at(i).deltaPercent,
                lastIndex, RELATIVE_WINDOW, RELATIVE_MIN_SAMPLES);
        // Token absolute floor so a symbol whose order flow has been dead flat for 120 bars can't
        // call a barely-there imbalance "the top 30% of its own history" and count it as signal.
        return magnitude >= ABSOLUTE_DELTA_FLOOR && percentile >= RELATIVE_DELTA_PERCENTILE;
    }

    private Direction bookDirection(OrderBookContext orderBook) {
        if (orderBook == null || !orderBook.available) return Direction.NEUTRAL;
        if (orderBook.bidDominant()) return Direction.BULLISH;
        if (orderBook.askDominant()) return Direction.BEARISH;
        return Direction.NEUTRAL;
    }

    private Direction liquidityDirection(LiquidityMap liquidity, double price) {
        if (liquidity == null || price <= 0.0) return Direction.NEUTRAL;
        LiquidityLevel nearestBsl = liquidity.nearestUntouched(price, true);
        LiquidityLevel nearestSsl = liquidity.nearestUntouched(price, false);
        double bsl = nearestBsl == null ? Double.NaN : Math.abs(nearestBsl.price - price);
        double ssl = nearestSsl == null ? Double.NaN : Math.abs(nearestSsl.price - price);
        if (!Double.isFinite(bsl) && !Double.isFinite(ssl)) return Direction.NEUTRAL;
        if (!Double.isFinite(bsl)) return Direction.BEARISH;
        if (!Double.isFinite(ssl)) return Direction.BULLISH;
        if (bsl < ssl * 0.75) return Direction.BULLISH;
        if (ssl < bsl * 0.75) return Direction.BEARISH;
        return Direction.NEUTRAL;
    }

    private Direction vote(Direction d1, int s1, Direction d2, int s2, Direction d3, int s3,
                           Direction d4, int s4, Direction d5, int s5) {
        int bull = weight(d1, s1, true) + weight(d2, s2, true) + weight(d3, s3, true)
                + weight(d4, s4, true) + weight(d5, s5, true);
        int bear = weight(d1, s1, false) + weight(d2, s2, false) + weight(d3, s3, false)
                + weight(d4, s4, false) + weight(d5, s5, false);
        if (bull >= bear + 18) return Direction.BULLISH;
        if (bear >= bull + 18) return Direction.BEARISH;
        return Direction.NEUTRAL;
    }

    private int aggregateScore(Direction direction, Direction d1, int s1, Direction d2, int s2, Direction d3, int s3,
                               Direction d4, int s4, Direction d5, int s5) {
        if (direction == Direction.NEUTRAL) return 0;
        int total = 0;
        int count = 0;
        int aligned = 0;
        Direction[] dirs = {d1, d2, d3, d4, d5};
        int[] scores = {s1, s2, s3, s4, s5};
        for (int i = 0; i < dirs.length; i++) {
            if (dirs[i] == Direction.NEUTRAL || scores[i] <= 0) continue;
            count++;
            if (dirs[i] == direction) {
                aligned++;
                total += scores[i];
            } else {
                total -= scores[i] / 3;
            }
        }
        if (aligned == 0) return 0;
        return Math.max(0, Math.min(100, total / aligned + Math.min(18, aligned * 4) - Math.max(0, count - aligned) * 5));
    }

    private int weight(Direction direction, int score, boolean bullish) {
        if (direction == Direction.NEUTRAL || score <= 0) return 0;
        if (bullish && direction.bullish()) return score;
        if (!bullish && direction.bearish()) return score;
        return 0;
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

    private double avgVolume(List<Candle> candles, int lookback) {
        int from = Math.max(0, candles.size() - lookback);
        double sum = 0.0;
        int count = 0;
        for (int i = from; i < candles.size(); i++) {
            sum += Math.max(0.0, candles.get(i).volume);
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private String fmt(double value) {
        return String.format(Locale.US, "%.1f", value);
    }
}
