package app.orderflow;

import app.model.Candle;

import java.util.Collections;
import java.util.List;

public class FootprintCandle {
    public final int index;
    public final long time;
    public final double buyVolume;
    public final double sellVolume;
    public final double delta;
    public final double cumulativeDelta;
    public final double deltaPercent;
    public final double imbalanceRatio;
    public final boolean aggressiveBuy;
    public final boolean aggressiveSell;
    public final boolean absorption;
    public final boolean tickBacked;
    public final List<FootprintLevel> levels;

    public FootprintCandle(int index, Candle candle, double buyVolume, double sellVolume,
                           double cumulativeDelta, boolean absorption,
                           List<FootprintLevel> levels) {
        this(index, candle, buyVolume, sellVolume, cumulativeDelta, absorption, levels, false);
    }

    public FootprintCandle(int index, Candle candle, double buyVolume, double sellVolume,
                           double cumulativeDelta, boolean absorption,
                           List<FootprintLevel> levels, boolean tickBacked) {
        this.index = index;
        this.time = candle.time;
        this.buyVolume = Math.max(0.0, buyVolume);
        this.sellVolume = Math.max(0.0, sellVolume);
        this.delta = this.buyVolume - this.sellVolume;
        this.cumulativeDelta = cumulativeDelta;
        double total = Math.max(0.00000001, this.buyVolume + this.sellVolume);
        this.deltaPercent = Math.abs(this.delta) / total;
        this.imbalanceRatio = Math.max(this.buyVolume, this.sellVolume)
                / Math.max(0.00000001, Math.min(this.buyVolume, this.sellVolume));
        this.aggressiveBuy = this.delta > 0.0 && this.deltaPercent >= 0.24;
        this.aggressiveSell = this.delta < 0.0 && this.deltaPercent >= 0.24;
        this.absorption = absorption;
        this.tickBacked = tickBacked;
        this.levels = Collections.unmodifiableList(levels);
    }

    public int score(boolean bullishContext) {
        int score = (int) Math.round(35 + deltaPercent * 45.0);
        boolean aligned = bullishContext ? delta > 0.0 : delta < 0.0;
        if (aligned) score += 15;
        if (absorption) score += 8;
        if (imbalanceRatio >= 3.0) score += 6;
        return Math.max(0, Math.min(100, score));
    }
}
