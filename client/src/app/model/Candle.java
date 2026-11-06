package app.model;

public class Candle {
    public final long time;
    public final double open, high, low, close;
    public final double volume;

    public Candle(long t, double o, double h, double l, double c) {
        this(t, o, h, l, c, 0.0);
    }

    public Candle(long t, double o, double h, double l, double c, double v) {
        time = t; open = o; high = h; low = l; close = c; volume = v;
    }

    public boolean bullish() { return close > open; }
    public boolean bearish() { return close < open; }
    public double body() { return Math.abs(close - open); }
    public double range() { return Math.max(0.0, high - low); }
    public double upperWick() { return high - Math.max(open, close); }
    public double lowerWick() { return Math.min(open, close) - low; }
    public double bodyPercent() { return range() == 0.0 ? 0.0 : body() / range(); }
}
