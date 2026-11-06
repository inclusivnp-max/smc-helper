package app.infrastructure;

public class LatencySnapshot {
    public final boolean available;
    public final long rttMs;
    public final int failures;
    public final boolean safeForHft;
    public final String venue;
    public final String message;
    public final long time;

    public LatencySnapshot(boolean available, long rttMs, int failures,
                           boolean safeForHft, String venue, String message) {
        this.available = available;
        this.rttMs = Math.max(0L, rttMs);
        this.failures = Math.max(0, failures);
        this.safeForHft = safeForHft;
        this.venue = venue == null ? "" : venue;
        this.message = message == null ? "" : message;
        this.time = System.currentTimeMillis();
    }

    public static LatencySnapshot unavailable(String venue, int failures, String message) {
        return new LatencySnapshot(false, 0L, failures, false, venue, message);
    }
}
