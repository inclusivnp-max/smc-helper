package app.orderflow;

import java.util.Collections;
import java.util.List;

public class OrderFlowContext {
    public final List<FootprintCandle> candles;
    public final double cumulativeDelta;
    public final double latestDelta;
    public final double latestDeltaPercent;
    public final int buyImbalanceCount;
    public final int sellImbalanceCount;
    public final int absorptionCount;
    public final int score;
    public final boolean tickBased;
    public final int tickBackedCandles;
    public final boolean latestTickBacked;

    public OrderFlowContext(List<FootprintCandle> candles, boolean tickBased) {
        this.candles = Collections.unmodifiableList(candles);
        int tickBacked = 0;
        for (FootprintCandle fp : candles) {
            if (fp.tickBacked) tickBacked++;
        }
        this.tickBackedCandles = tickBacked;
        this.tickBased = tickBased && tickBacked > 0;
        FootprintCandle last = candles.isEmpty() ? null : candles.get(candles.size() - 1);
        this.latestTickBacked = last != null && last.tickBacked;
        this.cumulativeDelta = last == null ? 0.0 : last.cumulativeDelta;
        this.latestDelta = last == null ? 0.0 : last.delta;
        this.latestDeltaPercent = last == null ? 0.0 : last.deltaPercent;

        int buy = 0;
        int sell = 0;
        int absorption = 0;
        int lookback = Math.max(0, candles.size() - 40);
        for (int i = lookback; i < candles.size(); i++) {
            FootprintCandle fp = candles.get(i);
            if (fp.aggressiveBuy) buy++;
            if (fp.aggressiveSell) sell++;
            if (fp.absorption) absorption++;
        }
        this.buyImbalanceCount = buy;
        this.sellImbalanceCount = sell;
        this.absorptionCount = absorption;
        int raw = 35 + Math.min(30, Math.abs(buy - sell) * 4)
                + Math.min(20, absorption * 3)
                + (tickBased ? 10 : 0);
        this.score = Math.max(0, Math.min(100, raw));
    }

    public FootprintCandle at(int index) {
        if (index < 0 || index >= candles.size()) return null;
        return candles.get(index);
    }

    public boolean supportsDirection(boolean bullish) {
        if (candles.isEmpty()) return false;
        FootprintCandle last = candles.get(candles.size() - 1);
        return bullish ? last.delta > 0.0 : last.delta < 0.0;
    }
}
