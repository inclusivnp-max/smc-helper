package app.learning;

import app.regime.MarketRegime;

public class AdaptiveFactorWeights {
    public final String symbol;
    public final double displacementWeight;
    public final double volumeWeight;
    public final double liquidityWeight;
    public final double orderFlowWeight;
    public final double regimeWeight;
    public final long updatedAt;

    public AdaptiveFactorWeights(String symbol, double displacementWeight, double volumeWeight,
                                 double liquidityWeight, double orderFlowWeight,
                                 double regimeWeight, long updatedAt) {
        this.symbol = symbol == null ? "" : symbol;
        this.displacementWeight = clampWeight(displacementWeight);
        this.volumeWeight = clampWeight(volumeWeight);
        this.liquidityWeight = clampWeight(liquidityWeight);
        this.orderFlowWeight = clampWeight(orderFlowWeight);
        this.regimeWeight = clampWeight(regimeWeight);
        this.updatedAt = updatedAt;
    }

    public static AdaptiveFactorWeights defaults(String symbol) {
        return new AdaptiveFactorWeights(symbol, 1.0, 1.0, 1.0, 1.0, 1.0, 0L);
    }

    /**
     * Applies this symbol's learned weights to `baseScore` using typed, magnitude-aware factor
     * evidence instead of grepping narrative text (see FactorSignals' class doc for why). Each
     * factor's pull now scales with how strong that factor's own score actually was (0-100),
     * rather than firing the same flat bonus whether a factor barely qualified or maxed out.
     */
    public int apply(int baseScore, FactorSignals signals, MarketRegime regime) {
        FactorSignals s = signals == null ? FactorSignals.none() : signals;
        double adjustment = 0.0;
        adjustment += (displacementWeight - 1.0) * 8.0 * (s.displacementScore / 100.0);
        adjustment += (volumeWeight - 1.0) * 7.0 * (s.volumeScore / 100.0);
        double liquidityStrength = Math.max(s.liquidityScore / 100.0, s.liquiditySweepConfirmed ? 1.0 : 0.0);
        adjustment += (liquidityWeight - 1.0) * 8.0 * liquidityStrength;
        double orderFlowStrength = s.orderFlowSupportsDirection ? Math.max(0.5, s.orderFlowScore / 100.0) : 0.0;
        adjustment += (orderFlowWeight - 1.0) * 6.0 * orderFlowStrength;

        // Single choppy check instead of the old regime.choppy() adjustment PLUS a second,
        // separate penalty triggered whenever a warning happened to mention "chop" — those two
        // paths almost always fired off the exact same underlying regime state, so the old code
        // was quietly double-counting one condition under two different names.
        boolean choppy = s.choppy || (regime != null && regime.choppy());
        if (choppy) adjustment -= (2.0 - regimeWeight) * 4.0;

        return Math.max(0, Math.min(100, (int) Math.round(baseScore + adjustment)));
    }

    public String summary() {
        return String.format("Adaptive weights D %.2f / V %.2f / L %.2f / OF %.2f / R %.2f",
                displacementWeight, volumeWeight, liquidityWeight, orderFlowWeight, regimeWeight);
    }

    private double clampWeight(double value) {
        return Math.max(0.20, Math.min(2.20, value));
    }
}
