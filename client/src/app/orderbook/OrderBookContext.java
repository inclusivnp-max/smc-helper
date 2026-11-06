package app.orderbook;

public class OrderBookContext {
    public final boolean available;
    public final double bidNotional;
    public final double askNotional;
    public final double imbalanceRatio;
    public final double largestBidWallPrice;
    public final double largestAskWallPrice;
    public final double largestBidWallNotional;
    public final double largestAskWallNotional;
    public final boolean spoofingDetected;
    public final String spoofingSide;
    public final int score;
    public final String message;

    public OrderBookContext(boolean available,
                            double bidNotional,
                            double askNotional,
                            double imbalanceRatio,
                            double largestBidWallPrice,
                            double largestAskWallPrice,
                            double largestBidWallNotional,
                            double largestAskWallNotional,
                            boolean spoofingDetected,
                            String spoofingSide,
                            int score,
                            String message) {
        this.available = available;
        this.bidNotional = bidNotional;
        this.askNotional = askNotional;
        this.imbalanceRatio = imbalanceRatio;
        this.largestBidWallPrice = largestBidWallPrice;
        this.largestAskWallPrice = largestAskWallPrice;
        this.largestBidWallNotional = largestBidWallNotional;
        this.largestAskWallNotional = largestAskWallNotional;
        this.spoofingDetected = spoofingDetected;
        this.spoofingSide = spoofingSide == null ? "" : spoofingSide;
        this.score = Math.max(0, Math.min(100, score));
        this.message = message == null ? "" : message;
    }

    public static OrderBookContext unavailable(String message) {
        return new OrderBookContext(false, 0.0, 0.0, 1.0, Double.NaN, Double.NaN,
                0.0, 0.0, false, "", 0, message);
    }

    public boolean bidDominant() {
        return imbalanceRatio >= 1.25;
    }

    public boolean askDominant() {
        return imbalanceRatio <= 0.80;
    }

    public int adjustment(boolean longSide) {
        if (!available) return 0;
        int value = 0;
        if (longSide && bidDominant()) value += 7;
        if (!longSide && askDominant()) value += 7;
        if (longSide && askDominant()) value -= 5;
        if (!longSide && bidDominant()) value -= 5;
        if (spoofingDetected) value += 4;
        return Math.max(-10, Math.min(12, value));
    }
}
