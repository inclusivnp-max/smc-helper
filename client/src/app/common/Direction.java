package app.common;

public enum Direction {
    BULLISH,
    BEARISH,
    NEUTRAL;

    public boolean bullish() {
        return this == BULLISH;
    }

    public boolean bearish() {
        return this == BEARISH;
    }

    public Direction opposite() {
        if (this == BULLISH) return BEARISH;
        if (this == BEARISH) return BULLISH;
        return NEUTRAL;
    }

    public String label() {
        if (this == BULLISH) return "bullish";
        if (this == BEARISH) return "bearish";
        return "neutral";
    }
}
