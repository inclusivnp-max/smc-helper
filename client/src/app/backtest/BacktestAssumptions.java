package app.backtest;

public class BacktestAssumptions {
    // Binance USDT-M Futures regular-tier taker fee is ~5 bps per fill; most backtested
    // strategies here enter/exit on stops or market triggers, so both legs are treated as
    // taker. Previously this cost model only counted spread+slippage (~5.5 bps round trip),
    // which understated real trading friction by roughly half and inflated every expectancy
    // number derived from it (ML weight training, RadarBacktestStats gates, UI reports).
    public static final double DEFAULT_TAKER_FEE_BPS = 5.0;

    public final double spreadBps;
    public final double slippageBps;
    public final double takerFeeBps;
    public final double tp1Size;
    public final double tp2Size;
    public final double tp3Size;
    public final boolean useTickData;
    public final int maxAggTradePagesPerCandle;
    public final int minConfidenceScore;
    public final boolean requireHtfDisplacementAligned;
    public final boolean useMarketImpact;
    public final double executionNotionalUsd;
    public final int executionSlices;
    public final double marketImpactBps;
    public final double passiveSliceImprovementBps;

    public BacktestAssumptions(double spreadBps, double slippageBps,
                               double tp1Size, double tp2Size, double tp3Size) {
        this(spreadBps, slippageBps, tp1Size, tp2Size, tp3Size, false, 1, 55, false);
    }

    public BacktestAssumptions(double spreadBps, double slippageBps,
                               double tp1Size, double tp2Size, double tp3Size,
                               boolean useTickData, int maxAggTradePagesPerCandle,
                               int minConfidenceScore, boolean requireHtfDisplacementAligned) {
        this(spreadBps, slippageBps, tp1Size, tp2Size, tp3Size,
                useTickData, maxAggTradePagesPerCandle, minConfidenceScore,
                requireHtfDisplacementAligned, true, 25_000.0, 20, 18.0, 1.2);
    }

    public BacktestAssumptions(double spreadBps, double slippageBps,
                               double tp1Size, double tp2Size, double tp3Size,
                               boolean useTickData, int maxAggTradePagesPerCandle,
                               int minConfidenceScore, boolean requireHtfDisplacementAligned,
                               boolean useMarketImpact, double executionNotionalUsd,
                               int executionSlices, double marketImpactBps,
                               double passiveSliceImprovementBps) {
        this(spreadBps, slippageBps, DEFAULT_TAKER_FEE_BPS, tp1Size, tp2Size, tp3Size,
                useTickData, maxAggTradePagesPerCandle, minConfidenceScore,
                requireHtfDisplacementAligned, useMarketImpact, executionNotionalUsd,
                executionSlices, marketImpactBps, passiveSliceImprovementBps);
    }

    public BacktestAssumptions(double spreadBps, double slippageBps, double takerFeeBps,
                               double tp1Size, double tp2Size, double tp3Size,
                               boolean useTickData, int maxAggTradePagesPerCandle,
                               int minConfidenceScore, boolean requireHtfDisplacementAligned,
                               boolean useMarketImpact, double executionNotionalUsd,
                               int executionSlices, double marketImpactBps,
                               double passiveSliceImprovementBps) {
        this.spreadBps = spreadBps;
        this.slippageBps = slippageBps;
        this.takerFeeBps = Math.max(0.0, takerFeeBps);
        this.tp1Size = tp1Size;
        this.tp2Size = tp2Size;
        this.tp3Size = tp3Size;
        this.useTickData = useTickData;
        this.maxAggTradePagesPerCandle = Math.max(1, maxAggTradePagesPerCandle);
        this.minConfidenceScore = Math.max(0, Math.min(100, minConfidenceScore));
        this.requireHtfDisplacementAligned = requireHtfDisplacementAligned;
        this.useMarketImpact = useMarketImpact;
        this.executionNotionalUsd = Math.max(0.0, executionNotionalUsd);
        this.executionSlices = Math.max(1, executionSlices);
        this.marketImpactBps = Math.max(0.0, marketImpactBps);
        this.passiveSliceImprovementBps = Math.max(0.0, passiveSliceImprovementBps);
    }

    public static BacktestAssumptions defaults() {
        return fast();
    }

    public static BacktestAssumptions fast() {
        return tuned(false, 55, false, 0.40, 0.40, 0.20);
    }

    public static BacktestAssumptions tickPrecise() {
        return tuned(true, 55, false, 0.40, 0.40, 0.20);
    }

    public static BacktestAssumptions tuned(boolean useTickData,
                                            int minConfidenceScore,
                                            boolean requireHtfDisplacementAligned,
                                            double tp1Size,
                                            double tp2Size,
                                            double tp3Size) {
        return new BacktestAssumptions(2.5, 1.5, tp1Size, tp2Size, tp3Size,
                useTickData, useTickData ? 3 : 1, minConfidenceScore, requireHtfDisplacementAligned,
                true, useTickData ? 50_000.0 : 25_000.0, useTickData ? 50 : 20, 18.0, 1.2);
    }

    public double roundTripCostR(double entry, double risk) {
        if (risk <= 0.0) return 0.0;
        // spread once (paid crossing in) + slippage on both legs + taker fee on both legs.
        double cost = entry * ((spreadBps + slippageBps * 2.0 + takerFeeBps * 2.0) / 10000.0);
        return cost / risk;
    }
}
