package app.common;

import java.time.Duration;

public class WeightedRateLimiter {
    private final int capacity;
    private final double refillPerMs;
    private double tokens;
    private long lastRefillMs;
    private long blockedUntilMs;

    public WeightedRateLimiter(int capacity, Duration window) {
        this.capacity = Math.max(1, capacity);
        long windowMs = Math.max(1L, window == null ? 60_000L : window.toMillis());
        this.refillPerMs = this.capacity / (double) windowMs;
        this.tokens = this.capacity;
        this.lastRefillMs = System.currentTimeMillis();
    }

    public void acquire(int weight) throws InterruptedException {
        int requested = Math.max(1, Math.min(capacity, weight));
        synchronized (this) {
            while (true) {
                refillLocked();
                long now = System.currentTimeMillis();
                if (now < blockedUntilMs) {
                    wait(Math.max(25L, blockedUntilMs - now));
                    continue;
                }
                if (tokens >= requested) {
                    tokens -= requested;
                    return;
                }
                double missing = requested - tokens;
                long waitMs = Math.max(25L, Math.min(1_000L, (long) Math.ceil(missing / refillPerMs)));
                wait(waitMs);
            }
        }
    }

    public synchronized void syncUsedWeight(long usedWeight) {
        if (usedWeight < 0) return;
        refillLocked();
        double exchangeRemaining = Math.max(0.0, capacity - usedWeight);
        tokens = Math.min(tokens, exchangeRemaining);
        notifyAll();
    }

    public synchronized void backoff(Duration duration) {
        long millis = duration == null ? 0L : Math.max(0L, duration.toMillis());
        if (millis <= 0L) return;
        blockedUntilMs = Math.max(blockedUntilMs, System.currentTimeMillis() + millis);
        notifyAll();
    }

    public synchronized String snapshot() {
        refillLocked();
        long now = System.currentTimeMillis();
        int available = (int) Math.floor(Math.max(0.0, Math.min(capacity, tokens)));
        if (now < blockedUntilMs) {
            return available + "/" + capacity + " tokens · cooldown " + (blockedUntilMs - now) + "ms";
        }
        return available + "/" + capacity + " tokens";
    }

    private void refillLocked() {
        long now = System.currentTimeMillis();
        if (now <= lastRefillMs) return;
        long elapsed = now - lastRefillMs;
        tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
        lastRefillMs = now;
    }
}
