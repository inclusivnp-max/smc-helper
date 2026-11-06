package app.infrastructure;

import java.time.Duration;

public class ReconnectPolicy {
    public final Duration initialBackoff;
    public final Duration maxBackoff;
    public final Duration heartbeatInterval;
    public final Duration staleTimeout;
    public final Duration forcedReconnectAfter;
    public final double jitterRatio;

    public ReconnectPolicy(Duration initialBackoff,
                           Duration maxBackoff,
                           Duration heartbeatInterval,
                           Duration staleTimeout,
                           Duration forcedReconnectAfter,
                           double jitterRatio) {
        this.initialBackoff = positive(initialBackoff, Duration.ofSeconds(1));
        this.maxBackoff = positive(maxBackoff, Duration.ofSeconds(45));
        this.heartbeatInterval = positive(heartbeatInterval, Duration.ofSeconds(20));
        this.staleTimeout = positive(staleTimeout, Duration.ofSeconds(75));
        this.forcedReconnectAfter = positive(forcedReconnectAfter, Duration.ofHours(23));
        this.jitterRatio = Math.max(0.0, Math.min(0.75, jitterRatio));
    }

    public static ReconnectPolicy binanceDefault() {
        return new ReconnectPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(45),
                Duration.ofSeconds(20),
                Duration.ofSeconds(75),
                Duration.ofHours(23),
                0.20);
    }

    public long delayMillis(int attempt) {
        int safeAttempt = Math.max(0, Math.min(30, attempt));
        long base = initialBackoff.toMillis();
        long max = maxBackoff.toMillis();
        long exponential = base;
        for (int i = 0; i < safeAttempt; i++) {
            if (exponential >= max / 2L) {
                exponential = max;
                break;
            }
            exponential *= 2L;
        }
        long capped = Math.max(1L, Math.min(max, exponential));
        if (jitterRatio <= 0.0) return capped;
        double jitter = 1.0 + (Math.random() * 2.0 - 1.0) * jitterRatio;
        return Math.max(1L, (long) Math.round(capped * jitter));
    }

    private Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) return fallback;
        return value;
    }
}
