package app.learning;

/**
 * Structured, typed replacement for reverse-engineering "which factor fired, and how strongly"
 * by substring-matching the free-text narrative (see the old AdaptiveFactorWeights.apply, which
 * did `factor.toLowerCase().contains("displacement")` against strings meant for on-screen
 * display). That approach silently breaks the moment a narrative string is reworded, and
 * overlapping substrings ("liquidity target" vs. an actual liquidity sweep) can misfire.
 *
 * Instances are built directly from the same typed context objects the narrative text is
 * generated from (Zone scores, OrderFlowContext, MarketRegime) — never parsed back out of it —
 * so wording changes elsewhere can't affect weight application, and magnitudes (0-100 scores)
 * let the adjustment scale with how strong the evidence actually was instead of a flat
 * mentioned-or-not bonus.
 */
public final class FactorSignals {
    public final int displacementScore;
    public final int volumeScore;
    public final int liquidityScore;
    public final boolean liquiditySweepConfirmed;
    public final int orderFlowScore;
    public final boolean orderFlowSupportsDirection;
    public final boolean choppy;

    public FactorSignals(int displacementScore, int volumeScore, int liquidityScore,
                         boolean liquiditySweepConfirmed, int orderFlowScore,
                         boolean orderFlowSupportsDirection, boolean choppy) {
        this.displacementScore = clamp(displacementScore);
        this.volumeScore = clamp(volumeScore);
        this.liquidityScore = clamp(liquidityScore);
        this.liquiditySweepConfirmed = liquiditySweepConfirmed;
        this.orderFlowScore = clamp(orderFlowScore);
        this.orderFlowSupportsDirection = orderFlowSupportsDirection;
        this.choppy = choppy;
    }

    public static FactorSignals none() {
        return new FactorSignals(0, 0, 0, false, 0, false, false);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
