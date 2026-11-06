package app.execution.fix;

public class InstrumentSpec {
    public final String venue;
    public final String symbol;
    public final double tickSize;
    public final double stepSize;
    public final double minQuantity;
    public final double maxQuantity;
    public final double minNotional;
    public final int pricePrecision;
    public final int quantityPrecision;
    public final long fetchedAt;

    public InstrumentSpec(String venue,
                          String symbol,
                          double tickSize,
                          double stepSize,
                          double minQuantity,
                          double maxQuantity,
                          double minNotional,
                          int pricePrecision,
                          int quantityPrecision,
                          long fetchedAt) {
        this.venue = venue == null ? "" : venue;
        this.symbol = symbol == null ? "" : symbol;
        this.tickSize = positiveOrNa(tickSize);
        this.stepSize = positiveOrNa(stepSize);
        this.minQuantity = Math.max(0.0, minQuantity);
        this.maxQuantity = maxQuantity > 0.0 ? maxQuantity : Double.NaN;
        this.minNotional = Math.max(0.0, minNotional);
        this.pricePrecision = Math.max(0, pricePrecision);
        this.quantityPrecision = Math.max(0, quantityPrecision);
        this.fetchedAt = fetchedAt <= 0L ? System.currentTimeMillis() : fetchedAt;
    }

    public boolean hasTick() {
        return Double.isFinite(tickSize) && tickSize > 0.0;
    }

    public boolean hasStep() {
        return Double.isFinite(stepSize) && stepSize > 0.0;
    }

    private static double positiveOrNa(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : Double.NaN;
    }
}
