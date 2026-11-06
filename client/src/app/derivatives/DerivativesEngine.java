package app.derivatives;

import app.datalake.LocalDataLake;
import app.logic.BinanceClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DerivativesEngine {
    private static final long CACHE_MS = 60_000L;
    // See OrderBookEngine's HISTORICAL_MAX_STALENESS_MS for why this is generous: today's
    // depth_l2/derivatives collection is sparse (hours between snapshots), so this widens as
    // collection density improves rather than needing a code change later.
    private static final long HISTORICAL_MAX_STALENESS_MS = 30 * 60_000L;
    // Live mode compares OI across an 8-point x 5m history (~40 minutes); historical mode
    // approximates the same lookback window from the nearest available snapshot pair.
    private static final long HISTORICAL_OI_LOOKBACK_MS = 40 * 60_000L;
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    public DerivativesContext analyze(String symbol) {
        return analyze(symbol, BinanceClient.Market.FUTURES);
    }

    public DerivativesContext analyze(String symbol, BinanceClient.Market market) {
        BinanceClient.Market contextMarket = market == null ? BinanceClient.Market.FUTURES : market;
        String key = contextMarket.label + "|" + (symbol == null ? "" : symbol.toUpperCase());
        if (contextMarket != BinanceClient.Market.FUTURES) {
            return DerivativesContext.unavailable("Derivatives are unavailable for spot market");
        }
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(key);
        if (cached != null && now - cached.time <= CACHE_MS) {
            return cached.context;
        }

        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            now = System.currentTimeMillis();
            cached = CACHE.get(key);
            if (cached != null && now - cached.time <= CACHE_MS) {
                return cached.context;
            }

            DerivativesContext context;
            String apiSymbol = symbol == null ? "" : symbol.toUpperCase();
            try {
                double oi = BinanceClient.fetchOpenInterest(apiSymbol);
                double funding = BinanceClient.fetchFundingRate(apiSymbol);
                List<Double> history = BinanceClient.fetchOpenInterestHistory(apiSymbol, "5m", 8);
                double changePct = 0.0;
                if (history.size() >= 2) {
                    double first = history.get(0);
                    double last = history.get(history.size() - 1);
                    if (first > 0.0) changePct = (last - first) / first * 100.0;
                }
                context = analyzeSnapshot(oi, changePct, funding);
            } catch (Exception ex) {
                context = DerivativesContext.unavailable("Derivatives API unavailable: " + ex.getMessage());
            }
            CACHE.put(key, new CacheEntry(System.currentTimeMillis(), context));
            return context;
        }
    }

    /**
     * Historical replay for the backtester: reads the nearest strictly-past open-interest/
     * funding snapshot pair from the local data lake instead of calling the live REST API.
     * openInterestChangePct is derived from two snapshots (~40 minutes apart, mirroring the live
     * 8x5m lookback) when both exist; with only one snapshot nearby it reports 0% change rather
     * than fabricating a trend from a comparison that was never actually made.
     */
    public DerivativesContext analyzeHistorical(String symbol, String market, long timestampMs) {
        String contextMarket = market == null || market.isBlank() ? BinanceClient.Market.FUTURES.label : market;
        LocalDataLake.DerivativesSnapshotResult current = LocalDataLake.loadNearestDerivativesSnapshot(
                symbol, contextMarket, timestampMs, HISTORICAL_MAX_STALENESS_MS);
        if (!current.hit) {
            return DerivativesContext.unavailable("No historical derivatives snapshot within staleness window");
        }
        double changePct = 0.0;
        LocalDataLake.DerivativesSnapshotResult prior = LocalDataLake.loadNearestDerivativesSnapshot(
                symbol, contextMarket, timestampMs - HISTORICAL_OI_LOOKBACK_MS, HISTORICAL_MAX_STALENESS_MS);
        if (prior.hit && prior.openInterest > 0.0 && prior.snapshotTime < current.snapshotTime) {
            changePct = (current.openInterest - prior.openInterest) / prior.openInterest * 100.0;
        }
        return analyzeSnapshot(current.openInterest, changePct, current.fundingRate);
    }

    private DerivativesContext analyzeSnapshot(double openInterest, double oiChangePct, double fundingRate) {
        int derivedScore = score(oiChangePct, fundingRate);
        String derivedState = state(oiChangePct, fundingRate);
        String message = "OI " + format(oiChangePct) + "% / funding " + format(fundingRate * 100.0) + "%";
        return new DerivativesContext(true, openInterest, oiChangePct, fundingRate, derivedScore, derivedState, message);
    }

    private int score(double oiChangePct, double fundingRate) {
        int score = 45;
        double absFunding = Math.abs(fundingRate);
        if (absFunding >= 0.00035) score += 18;
        if (absFunding >= 0.00070) score += 10;
        if (oiChangePct >= 3.0) score += 12;
        if (oiChangePct <= -4.0) score -= 14;
        return Math.max(0, Math.min(100, score));
    }

    private String state(double oiChangePct, double fundingRate) {
        if (oiChangePct <= -4.0) return "OI flush";
        if (fundingRate <= -0.00035) return "short crowding";
        if (fundingRate >= 0.00035) return "long crowding";
        if (oiChangePct >= 3.0) return "OI expansion";
        return "neutral";
    }

    private String format(double value) {
        return String.format("%.3f", value);
    }

    private static class CacheEntry {
        final long time;
        final DerivativesContext context;

        CacheEntry(long time, DerivativesContext context) {
            this.time = time;
            this.context = context;
        }
    }
}
