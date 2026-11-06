package app.orderflow;

import app.backtest.Tick;
import app.model.Candle;
import app.volatility.MarketMetrics;

import java.util.ArrayList;
import java.util.List;

public class OrderFlowEngine {
    private static final int DEFAULT_BINS = 8;

    public OrderFlowContext analyzeFromCandles(List<Candle> candles, MarketMetrics metrics) {
        if (candles == null || candles.isEmpty()) return new OrderFlowContext(List.of(), false);
        List<FootprintCandle> result = new ArrayList<>();
        double cvd = 0.0;
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double range = Math.max(c.high - c.low, metrics == null ? 0.00000001 : metrics.minTick());
            double closeLocation = ((c.close - c.low) / range) - 0.5;
            double bodyBias = (c.close - c.open) / range;
            double volume = Math.max(0.0, c.volume);

            double buyShare = clamp(0.50 + closeLocation * 0.38 + bodyBias * 0.28, 0.08, 0.92);
            double buy = volume * buyShare;
            double sell = Math.max(0.0, volume - buy);
            cvd += buy - sell;

            boolean highVolumeSmallBody = metrics != null
                    && c.volume > avgVolume(candles, i, 20) * 1.45
                    && c.bodyPercent() < 0.35;
            boolean absorption = highVolumeSmallBody && Math.abs(closeLocation) < 0.28;

            result.add(new FootprintCandle(i, c, buy, sell, cvd, absorption,
                    syntheticLevels(c, buy, sell, DEFAULT_BINS)));
        }
        return new OrderFlowContext(result, false);
    }

    public OrderFlowContext analyzeFromTicks(List<Candle> candles, List<Tick> ticks, double priceStep) {
        List<FootprintCandle> result = new ArrayList<>();
        double cvd = 0.0;
        int cursor = 0;
        double step = Math.max(priceStep, 0.00000001);

        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            long start = candle.time;
            long end = i + 1 < candles.size() ? candles.get(i + 1).time : Long.MAX_VALUE;
            int bins = Math.max(4, Math.min(18, (int) Math.ceil((candle.high - candle.low) / step) + 1));
            double[] buy = new double[bins];
            double[] sell = new double[bins];
            int ticksUsed = 0;

            while (cursor < ticks.size() && ticks.get(cursor).time < start) cursor++;
            int scan = cursor;
            while (scan < ticks.size() && ticks.get(scan).time < end) {
                Tick tick = ticks.get(scan++);
                ticksUsed++;
                int bin = (int) Math.floor((tick.price - candle.low) / step);
                bin = Math.max(0, Math.min(bins - 1, bin));
                if (tick.buyerMaker) sell[bin] += tick.quantity;
                else buy[bin] += tick.quantity;
            }

            double totalBuy = 0.0;
            double totalSell = 0.0;
            List<FootprintLevel> levels = new ArrayList<>();
            for (int b = 0; b < bins; b++) {
                double price = candle.low + b * step;
                totalBuy += buy[b];
                totalSell += sell[b];
                levels.add(new FootprintLevel(price, buy[b], sell[b]));
            }
            if (totalBuy + totalSell <= 0.0) {
                FootprintCandle fallback = analyzeFromCandles(List.of(candle), null).candles.get(0);
                totalBuy = fallback.buyVolume;
                totalSell = fallback.sellVolume;
                levels = fallback.levels;
            }
            cvd += totalBuy - totalSell;
            boolean absorption = (totalBuy + totalSell) > 0.0
                    && Math.abs(totalBuy - totalSell) / Math.max(totalBuy + totalSell, 0.00000001) < 0.18
                    && candle.bodyPercent() < 0.30;
            result.add(new FootprintCandle(i, candle, totalBuy, totalSell, cvd, absorption, levels, ticksUsed > 0));
        }
        return new OrderFlowContext(result, true);
    }

    private List<FootprintLevel> syntheticLevels(Candle c, double buyVolume, double sellVolume, int bins) {
        List<FootprintLevel> levels = new ArrayList<>();
        double range = Math.max(c.high - c.low, 0.00000001);
        double closeWeight = (c.close - c.low) / range;
        double openWeight = (c.open - c.low) / range;
        double buySum = 0.0;
        double sellSum = 0.0;
        double[] buyWeights = new double[bins];
        double[] sellWeights = new double[bins];

        for (int i = 0; i < bins; i++) {
            double p = i / (double) Math.max(1, bins - 1);
            buyWeights[i] = 0.45 + (1.0 - Math.abs(p - closeWeight)) * 0.85 + p * 0.20;
            sellWeights[i] = 0.45 + (1.0 - Math.abs(p - openWeight)) * 0.85 + (1.0 - p) * 0.20;
            buySum += buyWeights[i];
            sellSum += sellWeights[i];
        }

        for (int i = 0; i < bins; i++) {
            double price = c.low + range * i / Math.max(1, bins - 1);
            levels.add(new FootprintLevel(price,
                    buyVolume * buyWeights[i] / Math.max(buySum, 0.00000001),
                    sellVolume * sellWeights[i] / Math.max(sellSum, 0.00000001)));
        }
        return levels;
    }

    private double avgVolume(List<Candle> candles, int index, int period) {
        int from = Math.max(0, index - period + 1);
        double sum = 0.0;
        int count = 0;
        for (int i = from; i <= index; i++) {
            sum += Math.max(0.0, candles.get(i).volume);
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
