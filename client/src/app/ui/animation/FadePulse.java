package app.ui.animation;

public final class FadePulse {
    private final long durationMillis;
    private long startedAtMillis = Long.MIN_VALUE;

    public FadePulse(long durationMillis) {
        this.durationMillis = Math.max(120L, durationMillis);
    }

    public void trigger(long nowMillis) {
        startedAtMillis = nowMillis;
    }

    public float alpha(long nowMillis) {
        if (startedAtMillis == Long.MIN_VALUE) return 0.0f;
        long age = Math.max(0L, nowMillis - startedAtMillis);
        if (age >= durationMillis) return 0.0f;
        double t = age / (double) durationMillis;
        double eased = 1.0 - (t * t * (3.0 - 2.0 * t));
        return (float) Math.max(0.0, Math.min(1.0, eased));
    }

    public boolean active(long nowMillis) {
        return alpha(nowMillis) > 0.001f;
    }
}
