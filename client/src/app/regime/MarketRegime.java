package app.regime;

import app.common.Direction;

public class MarketRegime {
    public enum Type {
        TREND,
        RANGE,
        EXPANSION,
        CHOP
    }

    public final Type type;
    public final Direction direction;
    public final int score;
    public final double efficiency;
    public final double atrExpansion;
    public final boolean compressed;

    public MarketRegime(Type type, Direction direction, int score,
                        double efficiency, double atrExpansion, boolean compressed) {
        this.type = type;
        this.direction = direction;
        this.score = Math.max(0, Math.min(100, score));
        this.efficiency = efficiency;
        this.atrExpansion = atrExpansion;
        this.compressed = compressed;
    }

    public boolean trendLike() {
        return type == Type.TREND || type == Type.EXPANSION;
    }

    public boolean choppy() {
        return type == Type.CHOP;
    }

    public String label() {
        return switch (type) {
            case TREND -> "trend";
            case RANGE -> "range";
            case EXPANSION -> "expansion";
            case CHOP -> "chop";
        };
    }
}
