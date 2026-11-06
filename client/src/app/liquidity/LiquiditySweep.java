package app.liquidity;

import app.common.Direction;

public class LiquiditySweep {
    public final int index;
    public final Direction reversalDirection;
    public final LiquidityLevel level;
    public final int score;
    public final double wickRatio;
    public final boolean closeReclaimed;
    public final boolean displacementConfirmed;
    public final String session;

    public LiquiditySweep(int index, Direction reversalDirection, LiquidityLevel level,
                          int score, double wickRatio, boolean closeReclaimed,
                          boolean displacementConfirmed, String session) {
        this.index = index;
        this.reversalDirection = reversalDirection;
        this.level = level;
        this.score = score;
        this.wickRatio = wickRatio;
        this.closeReclaimed = closeReclaimed;
        this.displacementConfirmed = displacementConfirmed;
        this.session = session;
    }
}
