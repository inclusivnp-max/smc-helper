package app.deviation;

import app.common.Direction;

public class DeviationContext {
    public enum Type {
        NONE,
        RANGE_HIGH,
        RANGE_LOW,
        LIQUIDITY,
        SESSION,
        VWAP_MEAN
    }

    public static final DeviationContext NONE = new DeviationContext(
            false, Type.NONE, Direction.NEUTRAL, 0, 0, 0, 0, 0, 0,
            false, false, false, 0, 0, 0, 0, "", "No deviation");

    public final boolean available;
    public final Type type;
    public final Direction reversalDirection;
    public final int index;
    public final double rangeHigh;
    public final double rangeLow;
    public final double deviationPrice;
    public final double deviationPct;
    public final double atrMultiple;
    public final boolean returnedInside;
    public final boolean displacementAfterReturn;
    public final boolean liquiditySweep;
    public final double vwapDeviationPct;
    public final double meanDeviationAtr;
    public final double premiumDiscount;
    public final int score;
    public final String label;
    public final String narrative;

    public DeviationContext(boolean available,
                            Type type,
                            Direction reversalDirection,
                            int index,
                            double rangeHigh,
                            double rangeLow,
                            double deviationPrice,
                            double deviationPct,
                            double atrMultiple,
                            boolean returnedInside,
                            boolean displacementAfterReturn,
                            boolean liquiditySweep,
                            double vwapDeviationPct,
                            double meanDeviationAtr,
                            double premiumDiscount,
                            int score,
                            String label,
                            String narrative) {
        this.available = available;
        this.type = type == null ? Type.NONE : type;
        this.reversalDirection = reversalDirection == null ? Direction.NEUTRAL : reversalDirection;
        this.index = index;
        this.rangeHigh = rangeHigh;
        this.rangeLow = rangeLow;
        this.deviationPrice = deviationPrice;
        this.deviationPct = deviationPct;
        this.atrMultiple = atrMultiple;
        this.returnedInside = returnedInside;
        this.displacementAfterReturn = displacementAfterReturn;
        this.liquiditySweep = liquiditySweep;
        this.vwapDeviationPct = vwapDeviationPct;
        this.meanDeviationAtr = meanDeviationAtr;
        this.premiumDiscount = premiumDiscount;
        this.score = Math.max(0, Math.min(100, score));
        this.label = label == null ? "" : label;
        this.narrative = narrative == null ? "" : narrative;
    }

    public boolean supports(boolean longSide) {
        return available && reversalDirection != Direction.NEUTRAL
                && (longSide ? reversalDirection.bullish() : reversalDirection.bearish());
    }

    public boolean strongTrap() {
        return available && returnedInside && displacementAfterReturn && score >= 68;
    }
}
