package app.derivatives;

public class DerivativesContext {
    public final boolean available;
    public final double openInterest;
    public final double openInterestChangePct;
    public final double fundingRate;
    public final int score;
    public final String state;
    public final String message;

    public DerivativesContext(boolean available, double openInterest, double openInterestChangePct,
                              double fundingRate, int score, String state, String message) {
        this.available = available;
        this.openInterest = openInterest;
        this.openInterestChangePct = openInterestChangePct;
        this.fundingRate = fundingRate;
        this.score = Math.max(0, Math.min(100, score));
        this.state = state == null ? "" : state;
        this.message = message == null ? "" : message;
    }

    public static DerivativesContext unavailable(String message) {
        return new DerivativesContext(false, Double.NaN, 0.0, 0.0, 0, "unavailable", message);
    }

    public boolean crowdShorts() {
        return fundingRate <= -0.00035;
    }

    public boolean crowdLongs() {
        return fundingRate >= 0.00035;
    }

    public boolean openInterestFallingFast() {
        return openInterestChangePct <= -4.0;
    }

    public boolean openInterestExpanding() {
        return openInterestChangePct >= 3.0;
    }

    public int adjustment(boolean longSide, boolean nearEntry) {
        if (!available) return 0;
        int value = 0;
        if (longSide && crowdShorts()) value += 10;
        if (!longSide && crowdLongs()) value += 10;
        if (openInterestExpanding()) value += 4;
        if (nearEntry && openInterestFallingFast()) value -= 8;
        return Math.max(-14, Math.min(14, value));
    }

    public boolean supports(boolean longSide, boolean nearEntry) {
        return adjustment(longSide, nearEntry) >= 6;
    }

    public boolean warns(boolean nearEntry) {
        return nearEntry && openInterestFallingFast();
    }
}
