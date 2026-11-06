package app.structure;

public class SwingPoint {
    public final int index;
    public final double price;
    public final boolean high;
    public final boolean external;
    public final int strength;

    public SwingPoint(int index, double price, boolean high, boolean external, int strength) {
        this.index = index;
        this.price = price;
        this.high = high;
        this.external = external;
        this.strength = strength;
    }

    public String label() {
        return (high ? "high" : "low") + (external ? " external" : " internal");
    }
}
