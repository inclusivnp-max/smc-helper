package app.structure;

import app.common.Direction;

public class StructureEvent {
    public final int index;
    public final String type;
    public final Direction direction;
    public final double brokenLevel;
    public final int displacementScore;
    public final boolean bodyCloseConfirmed;
    public final boolean internal;
    public final int score;

    public StructureEvent(int index, String type, Direction direction, double brokenLevel,
                          int displacementScore, boolean bodyCloseConfirmed, boolean internal, int score) {
        this.index = index;
        this.type = type;
        this.direction = direction;
        this.brokenLevel = brokenLevel;
        this.displacementScore = displacementScore;
        this.bodyCloseConfirmed = bodyCloseConfirmed;
        this.internal = internal;
        this.score = score;
    }

    public boolean choch() {
        return "CHoCH".equals(type);
    }

    public boolean bos() {
        return "BOS".equals(type);
    }
}
