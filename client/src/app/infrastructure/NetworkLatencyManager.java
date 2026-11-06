package app.infrastructure;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkLatencyManager {
    private static final long CACHE_MS = 5_000L;
    private static final int CONNECT_TIMEOUT_MS = 850;
    private static final long HFT_RTT_LIMIT_MS = 240L;
    private static final Map<String, LatencySnapshot> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();

    public LatencySnapshot checkBinanceFutures() {
        return check("Binance Futures", "fapi.binance.com", 443);
    }

    public LatencySnapshot checkFixVenue(String host, int port, String venue) {
        if (host == null || host.isBlank() || port <= 0) {
            return LatencySnapshot.unavailable(venue, 1, "FIX venue is not configured");
        }
        return check(venue == null || venue.isBlank() ? host + ":" + port : venue, host, port);
    }

    public boolean safeForHft() {
        return checkBinanceFutures().safeForHft;
    }

    private LatencySnapshot check(String venue, String host, int port) {
        String key = host + ":" + port;
        LatencySnapshot cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.time <= CACHE_MS) return cached;

        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            long rtt = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
            FAILURES.put(key, 0);
            boolean safe = rtt <= HFT_RTT_LIMIT_MS;
            LatencySnapshot snapshot = new LatencySnapshot(true, rtt, 0, safe, venue,
                    safe ? "RTT normal" : "RTT spike; pause aggressive slicing");
            CACHE.put(key, snapshot);
            return snapshot;
        } catch (Exception ex) {
            int failures = FAILURES.merge(key, 1, Integer::sum);
            LatencySnapshot snapshot = LatencySnapshot.unavailable(venue, failures,
                    "Latency check failed: " + ex.getMessage());
            CACHE.put(key, snapshot);
            return snapshot;
        }
    }
}
