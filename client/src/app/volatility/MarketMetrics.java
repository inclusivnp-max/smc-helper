package app.volatility;

import app.model.Candle;

import java.util.List;

public class MarketMetrics {
    private final List<Candle> candles;
    private final double[] trueRange;
    private final double[] atr;
    private final double[] avgBody;
    private final double[] avgRange;
    private final double[] avgVolume;
    /** Сглаженный DI+ - DI- в диапазоне [-100, 100]. Положительный = бычья направленность. */
    private final double[] directionalStrength;

    private MarketMetrics(List<Candle> candles, double[] trueRange, double[] atr,
                          double[] avgBody, double[] avgRange, double[] avgVolume,
                          double[] directionalStrength) {
        this.candles = candles;
        this.trueRange = trueRange;
        this.atr = atr;
        this.avgBody = avgBody;
        this.avgRange = avgRange;
        this.avgVolume = avgVolume;
        this.directionalStrength = directionalStrength;
    }

    public static MarketMetrics compute(List<Candle> candles, int period) {
        int n = candles.size();
        double[] tr = new double[n];
        double[] atr = new double[n];
        double[] avgBody = new double[n];
        double[] avgRange = new double[n];
        double[] avgVolume = new double[n];
        double[] ds = new double[n];  // directionalStrength

        double trSum = 0.0;
        double bodySum = 0.0;
        double rangeSum = 0.0;
        double volumeSum = 0.0;
        double dmPlusSum = 0.0;
        double dmMinusSum = 0.0;

        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            double prevClose = i == 0 ? c.close : candles.get(i - 1).close;
            tr[i] = Math.max(c.high - c.low,
                    Math.max(Math.abs(c.high - prevClose), Math.abs(c.low - prevClose)));

            // Directional Movement (упрощённый без Wilder smoothing, скользящее среднее)
            double dmPlus = 0.0, dmMinus = 0.0;
            if (i > 0) {
                Candle prev = candles.get(i - 1);
                double upMove   = c.high - prev.high;
                double downMove = prev.low - c.low;
                if (upMove > downMove && upMove > 0.0)   dmPlus  = upMove;
                if (downMove > upMove && downMove > 0.0) dmMinus = downMove;
            }

            trSum     += tr[i];
            bodySum   += c.body();
            rangeSum  += c.range();
            volumeSum += c.volume;
            dmPlusSum += dmPlus;
            dmMinusSum += dmMinus;

            if (i >= period) {
                Candle old = candles.get(i - period);
                trSum     -= tr[i - period];
                bodySum   -= old.body();
                rangeSum  -= old.range();
                volumeSum -= old.volume;
                // Восстанавливаем старые DM значения (храним через аналогичный проход)
                if (i - period > 0) {
                    Candle pOld = candles.get(i - period - 1);
                    Candle cOld = candles.get(i - period);
                    double upOld   = cOld.high - pOld.high;
                    double downOld = pOld.low  - cOld.low;
                    if (upOld > downOld && upOld > 0.0)   dmPlusSum  -= upOld;
                    if (downOld > upOld && downOld > 0.0) dmMinusSum -= downOld;
                }
            }

            int count = Math.min(i + 1, period);
            atr[i]     = trSum / count;
            avgBody[i] = bodySum / count;
            avgRange[i] = rangeSum / count;
            avgVolume[i] = volumeSum / count;

            // DI+ и DI-: нормированы по ATR-сумме; spread = DI+ - DI- в [-100, 100]
            double atrWindow = Math.max(trSum, 0.00000001);
            double diPlus  = 100.0 * dmPlusSum  / atrWindow;
            double diMinus = 100.0 * dmMinusSum / atrWindow;
            ds[i] = Math.max(-100.0, Math.min(100.0, diPlus - diMinus));
        }

        return new MarketMetrics(candles, tr, atr, avgBody, avgRange, avgVolume, ds);
    }

    public int size() {
        return candles.size();
    }

    public double trueRangeAt(int index) {
        return value(trueRange, index);
    }

    public double atrAt(int index) {
        return Math.max(value(atr, index), minTick());
    }

    public double latestAtr() {
        return atrAt(size() - 1);
    }

    public double avgBodyAt(int index) {
        return Math.max(value(avgBody, index), minTick());
    }

    public double avgRangeAt(int index) {
        return Math.max(value(avgRange, index), minTick());
    }

    public double avgVolumeAt(int index) {
        return Math.max(value(avgVolume, index), 0.0);
    }

    public double volumeRatioAt(int index) {
        double avg = avgVolumeAt(index);
        if (avg <= 0.0) return 1.0;
        return candles.get(index).volume / avg;
    }

    public double atrExpansionAt(int index) {
        return trueRangeAt(index) / atrAt(Math.max(0, index - 1));
    }

    public double bodyExpansionAt(int index) {
        return candles.get(index).body() / avgBodyAt(Math.max(0, index - 1));
    }

    public double normalizedRangeAt(int index) {
        return trueRangeAt(index) / atrAt(index);
    }

    /**
     * Направленная сила тренда: DI+ - DI- в диапазоне [-100, 100].
     * Положительное значение — бычье давление преобладает, отрицательное — медвежье.
     * Абсолютное значение ≥ 20 считается значимым сигналом направленности.
     */
    public double directionalStrengthAt(int index) {
        return value(directionalStrength, index);
    }

    /** Последнее значение directional strength. */
    public double latestDirectionalStrength() {
        return directionalStrengthAt(size() - 1);
    }

    public boolean compressedVolatility(int index) {
        int lookback = 20;
        if (index < lookback) return false;
        double recent = 0.0;
        double prior = 0.0;
        for (int i = index - 9; i <= index; i++) recent += trueRangeAt(i);
        for (int i = index - 19; i < index - 9; i++) prior += trueRangeAt(i);
        return prior > 0.0 && (recent / 10.0) < (prior / 10.0) * 0.72;
    }

    public double minTick() {
        if (candles.isEmpty()) return 0.00000001;
        double price = candles.get(candles.size() - 1).close;
        return Math.max(price * 0.0000001, 0.00000001);
    }

    private double value(double[] values, int index) {
        if (values.length == 0) return 0.0;
        int i = Math.max(0, Math.min(values.length - 1, index));
        return values[i];
    }
}
