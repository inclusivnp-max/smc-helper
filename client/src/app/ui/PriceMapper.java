package app.ui;

public class PriceMapper {
    private final int top, height;
    private final double min, max;

    public PriceMapper(int t, int h, double mn, double mx) {
        top = t; height = h; min = mn; max = mx;
    }

    public int y(double price) {
        double ratio = (price - min) / (max - min);
        return (int) (top + height - ratio * height);
    }

    public double priceAt(int y) {
        double ratio = (top + height - y) / (double) height;
        return min + ratio * (max - min);
    }
}