package app.backtest;

import app.model.Candle;
import app.model.TradePlan;

public class MarketImpactModel {
    public Fill estimateEntryFill(TradePlan plan, Candle candle, BacktestAssumptions assumptions) {
        return estimateEntryFill(plan, candle, assumptions, plan == null ? 0.0 : plan.entry);
    }

    public Fill estimateEntryFill(TradePlan plan, Candle candle, BacktestAssumptions assumptions, double baseEntry) {
        if (plan == null || candle == null || assumptions == null || !assumptions.useMarketImpact) {
            return new Fill(baseEntry, 0.0, 0.0, 1);
        }

        int slices = Math.max(1, assumptions.executionSlices);
        double referencePrice = Math.max(0.00000001, baseEntry);
        double candleTypical = Math.max(0.00000001, (candle.high + candle.low + candle.close) / 3.0);
        double barNotional = Math.max(candle.volume * candleTypical, referencePrice * 1_000.0);
        double participation = Math.max(0.0, assumptions.executionNotionalUsd / barNotional);

        double direction = plan.longSide ? 1.0 : -1.0;
        double avgImpactBps = 0.0;
        double avgFill = 0.0;
        for (int i = 0; i < slices; i++) {
            double progress = (i + 1.0) / slices;
            double sliceParticipation = participation * progress;
            double impactBps = assumptions.marketImpactBps
                    * Math.sqrt(Math.max(0.0, sliceParticipation))
                    * liquidityPenalty(candle);
            double passiveOffsetBps = assumptions.passiveSliceImprovementBps
                    * Math.max(0.0, 1.0 - progress * 0.65);
            double netImpactBps = Math.max(0.0, impactBps - passiveOffsetBps);
            avgImpactBps += netImpactBps;
            avgFill += baseEntry + direction * baseEntry * netImpactBps / 10000.0;
        }
        avgImpactBps /= slices;
        avgFill /= slices;
        return new Fill(avgFill, avgImpactBps, participation * 100.0, slices);
    }

    private double liquidityPenalty(Candle candle) {
        double rangePct = candle.close <= 0.0 ? 0.0 : Math.abs(candle.high - candle.low) / candle.close;
        if (rangePct >= 0.018) return 1.35;
        if (rangePct >= 0.010) return 1.15;
        return 1.0;
    }

    public static class Fill {
        public final double averageEntry;
        public final double impactBps;
        public final double participationPct;
        public final int slices;

        public Fill(double averageEntry, double impactBps, double participationPct, int slices) {
            this.averageEntry = averageEntry;
            this.impactBps = impactBps;
            this.participationPct = participationPct;
            this.slices = slices;
        }
    }
}
