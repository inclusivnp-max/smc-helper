package app.imbalance;

import app.common.Direction;

public class ImbalanceContext {
    public static final ImbalanceContext NONE = new ImbalanceContext(false, Direction.NEUTRAL,
            0, 0, 0, 0, 0, 0, 0, false, "", "No imbalance context");

    public final boolean available;
    public final Direction direction;
    public final int score;
    public final int candleImbalanceScore;
    public final int fvgEfficiencyScore;
    public final int deltaImbalanceScore;
    public final int bookImbalanceScore;
    public final int liquidityImbalanceScore;
    public final double fvgFilledPercent;
    public final boolean absorption;
    public final String label;
    public final String narrative;

    public ImbalanceContext(boolean available,
                            Direction direction,
                            int score,
                            int candleImbalanceScore,
                            int fvgEfficiencyScore,
                            int deltaImbalanceScore,
                            int bookImbalanceScore,
                            int liquidityImbalanceScore,
                            double fvgFilledPercent,
                            boolean absorption,
                            String label,
                            String narrative) {
        this.available = available;
        this.direction = direction == null ? Direction.NEUTRAL : direction;
        this.score = clamp(score);
        this.candleImbalanceScore = clamp(candleImbalanceScore);
        this.fvgEfficiencyScore = clamp(fvgEfficiencyScore);
        this.deltaImbalanceScore = clamp(deltaImbalanceScore);
        this.bookImbalanceScore = clamp(bookImbalanceScore);
        this.liquidityImbalanceScore = clamp(liquidityImbalanceScore);
        this.fvgFilledPercent = Math.max(0.0, Math.min(100.0, fvgFilledPercent));
        this.absorption = absorption;
        this.label = label == null ? "" : label;
        this.narrative = narrative == null ? "" : narrative;
    }

    public boolean supports(boolean longSide) {
        return available && direction != Direction.NEUTRAL && (longSide ? direction.bullish() : direction.bearish());
    }

    public boolean strong() {
        return available && score >= 68;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
