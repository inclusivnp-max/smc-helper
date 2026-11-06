package app.regime;

import app.common.Direction;
import app.model.Candle;
import app.volatility.MarketMetrics;

import java.util.List;

public class MarketRegimeEngine {
    public MarketRegime analyze(List<Candle> candles, MarketMetrics metrics) {
        int n = candles == null ? 0 : candles.size();
        if (n < 30) {
            return new MarketRegime(MarketRegime.Type.RANGE, Direction.NEUTRAL, 35, 0.0, 1.0, false);
        }

        int lookback = Math.min(60, n - 1);
        int start = Math.max(0, n - lookback);
        double high = -Double.MAX_VALUE;
        double low = Double.MAX_VALUE;
        for (int i = start; i < n; i++) {
            Candle c = candles.get(i);
            high = Math.max(high, c.high);
            low = Math.min(low, c.low);
        }

        Candle first = candles.get(start);
        Candle last = candles.get(n - 1);
        double range = Math.max(high - low, metrics.minTick());
        double directionalMove = Math.abs(last.close - first.close);
        double efficiency = directionalMove / range;

        double recentAtr = metrics.atrAt(n - 1);
        double priorAtr = metrics.atrAt(Math.max(0, n - 21));
        double atrExpansion = priorAtr <= 0.0 ? 1.0 : recentAtr / priorAtr;
        boolean compressed = metrics.compressedVolatility(n - 1);

        // HH/HL structure analysis — выявляет тренды с pullback'ами, которые
        // efficiency ratio занижает из-за боковых откатов внутри тренда
        int structureScore = scoreSwingStructure(candles, n, metrics);
        Direction structureDirection = structureScore > 0 ? Direction.BULLISH
                : structureScore < 0 ? Direction.BEARISH : Direction.NEUTRAL;

        // DI-spread из MarketMetrics (DI+ - DI- в диапазоне [-100, 100])
        double diSpread = metrics.directionalStrengthAt(n - 1);
        // Нормализуем в [0, 1]: значение ≥20 считается значимым
        double diSignal = Math.min(1.0, Math.abs(diSpread) / 40.0);
        Direction diDirection = diSpread > 8.0 ? Direction.BULLISH
                : diSpread < -8.0 ? Direction.BEARISH : Direction.NEUTRAL;

        Direction direction = Direction.NEUTRAL;
        if (efficiency >= 0.22) {
            direction = last.close >= first.close ? Direction.BULLISH : Direction.BEARISH;
        } else if (Math.abs(structureScore) >= 2) {
            direction = structureDirection; // структура перевешивает при неоднозначном efficiency
        } else if (diDirection != Direction.NEUTRAL) {
            direction = diDirection; // DI-spread как третий голос
        }

        // Нормализуем структурный score в диапазон [0, 1]
        double structureWeight = Math.min(1.0, Math.abs(structureScore) / 4.0);
        // Взвешенный сигнал: 50% efficiency + 30% swing structure + 20% DI-spread
        double combinedTrendSignal = efficiency * 0.50 + structureWeight * 0.30 + diSignal * 0.20;

        MarketRegime.Type type;
        if (compressed || (efficiency < 0.18 && atrExpansion < 0.92 && Math.abs(structureScore) < 2)) {
            type = MarketRegime.Type.CHOP;
        } else if (atrExpansion >= 1.22 && combinedTrendSignal >= 0.38) {
            type = MarketRegime.Type.EXPANSION;
        } else if (combinedTrendSignal >= 0.42) {
            type = MarketRegime.Type.TREND;
        } else {
            type = MarketRegime.Type.RANGE;
        }

        int score = (int) Math.round(combinedTrendSignal * 70.0 + Math.min(1.6, atrExpansion) * 18.0);
        if (type == MarketRegime.Type.CHOP) score = Math.min(score, 42);
        if (type == MarketRegime.Type.EXPANSION) score = Math.min(100, score + 8);
        return new MarketRegime(type, direction, score, efficiency, atrExpansion, compressed);
    }

    /**
     * Считает HH/HL (bullish) vs LH/LL (bearish) на последних 5 swing-пиках/впадинах.
     * Возвращает положительное число для бычьей структуры, отрицательное для медвежьей.
     * Использует упрощённый swing-детектор (pivot 3-3) без зависимости от StructureEngine.
     */
    private int scoreSwingStructure(List<Candle> candles, int n, MarketMetrics metrics) {
        int left = 3, right = 3;
        double[] highs = new double[5];
        double[] lows = new double[5];
        int hiCount = 0, loCount = 0;

        // Собираем последние 5 swing high и 5 swing low
        for (int i = n - right - 1; i >= left && (hiCount < 5 || loCount < 5); i--) {
            Candle c = candles.get(i);
            boolean isHigh = true, isLow = true;
            for (int j = i - left; j <= i + right; j++) {
                if (j == i || j < 0 || j >= n) continue;
                if (candles.get(j).high >= c.high) isHigh = false;
                if (candles.get(j).low <= c.low) isLow = false;
                if (!isHigh && !isLow) break;
            }
            double atr = metrics.atrAt(i);
            if (isHigh && hiCount < 5 && c.high - c.low >= atr * 0.3) highs[hiCount++] = c.high;
            if (isLow && loCount < 5 && c.high - c.low >= atr * 0.3) lows[loCount++] = c.low;
        }

        if (hiCount < 2 || loCount < 2) return 0;

        // Подсчёт HH/LH для highs (индекс 0 = самый свежий)
        int hhCount = 0, lhCount = 0;
        for (int i = 0; i < hiCount - 1; i++) {
            if (highs[i] > highs[i + 1]) hhCount++; else lhCount++;
        }

        // Подсчёт HL/LL для lows
        int hlCount = 0, llCount = 0;
        for (int i = 0; i < loCount - 1; i++) {
            if (lows[i] > lows[i + 1]) hlCount++; else llCount++;
        }

        // Бычья структура: больше HH и HL; медвежья: LH и LL
        int bullScore = hhCount + hlCount;
        int bearScore = lhCount + llCount;
        return bullScore - bearScore;
    }
}
