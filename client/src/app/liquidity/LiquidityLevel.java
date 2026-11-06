package app.liquidity;

public class LiquidityLevel {
    public final String side;      // BSL or SSL
    public final String scope;     // internal, external, session
    public final String label;
    public final double price;
    public final int strength;
    public final int touches;
    public final int firstIndex;
    public final int lastIndex;
    public boolean swept;
    public int sweptIndex = -1;
    public int sweepScore = 0;
    public boolean touched;

    public LiquidityLevel(String side, String scope, String label, double price,
                          int strength, int touches, int firstIndex, int lastIndex) {
        this.side = side;
        this.scope = scope;
        this.label = label;
        this.price = price;
        this.strength = strength;
        this.touches = touches;
        this.firstIndex = firstIndex;
        this.lastIndex = lastIndex;
    }

    public boolean buySide() {
        return "BSL".equals(side);
    }

    public boolean sellSide() {
        return "SSL".equals(side);
    }

    public String strengthLabel() {
        if (strength >= 75) return "strong";
        if (strength >= 50) return "medium";
        return "weak";
    }
}
