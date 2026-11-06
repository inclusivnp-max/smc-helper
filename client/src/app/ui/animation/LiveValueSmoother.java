package app.ui.animation;

public final class LiveValueSmoother {
    private final double halfLifeMillis;
    private double value = Double.NaN;
    private double target = Double.NaN;
    private long lastFrameNanos = 0L;

    public LiveValueSmoother(double halfLifeMillis) {
        this.halfLifeMillis = Math.max(16.0, halfLifeMillis);
    }

    public void reset(double nextValue) {
        value = nextValue;
        target = nextValue;
        lastFrameNanos = 0L;
    }

    public void target(double nextTarget) {
        if (!Double.isFinite(nextTarget)) return;
        if (!Double.isFinite(value)) value = nextTarget;
        target = nextTarget;
    }

    public double valueOr(double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    public boolean active(double tolerance) {
        return Double.isFinite(value)
                && Double.isFinite(target)
                && Math.abs(value - target) > Math.max(0.0, tolerance);
    }

    public boolean tick(long nowNanos, double tolerance) {
        if (!Double.isFinite(value) || !Double.isFinite(target)) return false;
        if (lastFrameNanos <= 0L) {
            lastFrameNanos = nowNanos;
            return active(tolerance);
        }
        double elapsedMillis = Math.max(0.0, (nowNanos - lastFrameNanos) / 1_000_000.0);
        lastFrameNanos = nowNanos;
        if (elapsedMillis <= 0.0) return active(tolerance);

        double before = value;
        double blend = 1.0 - Math.pow(0.5, elapsedMillis / halfLifeMillis);
        value += (target - value) * Math.max(0.02, Math.min(1.0, blend));
        if (Math.abs(value - target) <= Math.max(0.0, tolerance)) {
            value = target;
        }
        return Math.abs(before - value) > Math.max(0.0, tolerance) || active(tolerance);
    }
}
