package app.portfolio;

import app.model.AnalysisResult;
import app.model.Candle;

import java.util.List;

public class CorrelationEngine {
    public double pearsonReturns(AnalysisResult a, AnalysisResult b, int lookback) {
        if (a == null || b == null || a.candles == null || b.candles == null) return 0.0;
        int count = Math.min(Math.min(a.candles.size(), b.candles.size()), Math.max(12, lookback));
        if (count < 12) return 0.0;

        double[] ar = returns(a.candles, count);
        double[] br = returns(b.candles, count);
        return pearson(ar, br);
    }

    public boolean highlyCorrelated(AnalysisResult a, AnalysisResult b, double threshold) {
        return Math.abs(pearsonReturns(a, b, 120)) >= threshold;
    }

    private double[] returns(List<Candle> candles, int count) {
        double[] values = new double[count - 1];
        int start = candles.size() - count;
        for (int i = 1; i < count; i++) {
            double prev = candles.get(start + i - 1).close;
            double close = candles.get(start + i).close;
            values[i - 1] = prev <= 0.0 ? 0.0 : (close - prev) / prev;
        }
        return values;
    }

    private double pearson(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 2) return 0.0;
        double meanA = 0.0;
        double meanB = 0.0;
        for (int i = 0; i < n; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= n;
        meanB /= n;

        double numerator = 0.0;
        double varA = 0.0;
        double varB = 0.0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            numerator += da * db;
            varA += da * da;
            varB += db * db;
        }
        double denom = Math.sqrt(varA * varB);
        return denom <= 0.0 ? 0.0 : numerator / denom;
    }
}
