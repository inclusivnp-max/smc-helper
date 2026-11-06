package app.orderbook;

import app.common.Json;
import app.datalake.LocalDataLake;
import app.logic.BinanceClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OrderBookEngine {
    private static final long CACHE_MS = 8_000L;
    // How stale a historical depth snapshot is allowed to be before it's not trusted to
    // represent the book at the candle's close. The live lookback data collected so far has
    // gaps of hours between snapshots (still an early/sparse archive), so this is deliberately
    // generous -- it widens automatically as collection density improves over time; it does not
    // pretend a 3-hour-old book is fresh, it just accepts that "somewhat stale real data" beats
    // "no data at all" for a backtest that would otherwise skip DOM evidence unconditionally.
    private static final long HISTORICAL_MAX_STALENESS_MS = 30 * 60_000L;
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, OrderBookContext> LAST_CONTEXT = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    public OrderBookContext analyze(String symbol, double currentPrice) {
        return analyze(symbol, currentPrice, BinanceClient.Market.FUTURES);
    }

    public OrderBookContext analyze(String symbol, double currentPrice, BinanceClient.Market market) {
        BinanceClient.Market contextMarket = market == null ? BinanceClient.Market.FUTURES : market;
        String apiSymbol = symbol == null ? "" : symbol.toUpperCase();
        String key = contextMarket.label + "|" + apiSymbol;
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

            OrderBookContext context;
            try {
                String json = BinanceClient.fetchDepthJson(apiSymbol, contextMarket, 100);
                Map<String, Object> book = Json.asObject(Json.parse(json));
                List<OrderBookLevel> bids = parseSide(book.get("bids"));
                List<OrderBookLevel> asks = parseSide(book.get("asks"));
                context = analyzeSnapshot(currentPrice, bids, asks, LAST_CONTEXT.get(key));
            } catch (Exception ex) {
                context = OrderBookContext.unavailable("Order book unavailable: " + ex.getMessage());
            }
            CACHE.put(key, new CacheEntry(System.currentTimeMillis(), context));
            if (context.available) LAST_CONTEXT.put(key, context);
            return context;
        }
    }

    /**
     * Historical replay for the backtester: loads the nearest strictly-past depth_l2 snapshot
     * from the local data lake for (symbol, market, timestamp) instead of hitting the live REST
     * API (which is meaningless for a past candle anyway). Returns OrderBookContext.unavailable
     * when the data lake has no coverage there -- callers treat that exactly like "no context"
     * today, so backtests over symbols/periods with no collected depth data behave unchanged.
     *
     * Spoofing detection is deliberately skipped in historical mode: it compares consecutive
     * snapshots roughly 8 seconds apart in live mode, where a wall vanishing that fast is
     * meaningful. Historical snapshots are sparse and irregular (currently gaps of hours) --
     * comparing across that gap would label ordinary book turnover as "spoofing," which is worse
     * than not claiming a spoofing signal at all.
     */
    public OrderBookContext analyzeHistorical(String symbol, String market, double currentPrice, long timestampMs) {
        String contextMarket = market == null || market.isBlank() ? BinanceClient.Market.FUTURES.label : market;
        LocalDataLake.DepthSnapshotResult snapshot = LocalDataLake.loadNearestDepthSnapshot(
                symbol, contextMarket, timestampMs, HISTORICAL_MAX_STALENESS_MS);
        if (!snapshot.hit) {
            return OrderBookContext.unavailable("No historical depth snapshot within staleness window");
        }
        return analyzeSnapshot(currentPrice, snapshot.bids, snapshot.asks, null);
    }

    private OrderBookContext analyzeSnapshot(double currentPrice,
                                             List<OrderBookLevel> bids,
                                             List<OrderBookLevel> asks,
                                             OrderBookContext previous) {
        double band = Math.max(currentPrice * 0.006, 0.00000001);
        double bidNotional = 0.0;
        double askNotional = 0.0;
        OrderBookLevel largestBid = null;
        OrderBookLevel largestAsk = null;

        for (OrderBookLevel level : bids) {
            if (Math.abs(level.price - currentPrice) <= band) bidNotional += level.notional();
            if (largestBid == null || level.notional() > largestBid.notional()) largestBid = level;
        }
        for (OrderBookLevel level : asks) {
            if (Math.abs(level.price - currentPrice) <= band) askNotional += level.notional();
            if (largestAsk == null || level.notional() > largestAsk.notional()) largestAsk = level;
        }

        double ratio = bidNotional / Math.max(askNotional, 0.00000001);
        boolean spoof = false;
        String spoofSide = "";
        if (previous != null) {
            boolean bidWallPulled = sameWall(previous.largestBidWallPrice, largestBid == null ? Double.NaN : largestBid.price, currentPrice)
                    && largestBid != null
                    && previous.largestBidWallNotional > largestBid.notional() * 3.5;
            boolean askWallPulled = sameWall(previous.largestAskWallPrice, largestAsk == null ? Double.NaN : largestAsk.price, currentPrice)
                    && largestAsk != null
                    && previous.largestAskWallNotional > largestAsk.notional() * 3.5;
            spoof = bidWallPulled || askWallPulled;
            spoofSide = bidWallPulled ? "bid wall pulled" : askWallPulled ? "ask wall pulled" : "";
        }

        int score = 45;
        if (ratio >= 1.25 || ratio <= 0.80) score += 18;
        if (ratio >= 1.75 || ratio <= 0.57) score += 10;
        if (spoof) score += 12;
        String message = "DOM bid/ask " + String.format("%.2f", ratio) + "x"
                + (spoof ? " / spoofing: " + spoofSide : "");
        return new OrderBookContext(true, bidNotional, askNotional, ratio,
                largestBid == null ? Double.NaN : largestBid.price,
                largestAsk == null ? Double.NaN : largestAsk.price,
                largestBid == null ? 0.0 : largestBid.notional(),
                largestAsk == null ? 0.0 : largestAsk.notional(),
                spoof, spoofSide, score, message);
    }

    private boolean sameWall(double previousPrice, double currentPrice, double referencePrice) {
        if (Double.isNaN(previousPrice) || Double.isNaN(currentPrice) || referencePrice <= 0.0) return false;
        return Math.abs(previousPrice - currentPrice) / referencePrice <= 0.0015;
    }

    private List<OrderBookLevel> parseSide(Object side) throws java.io.IOException {
        List<OrderBookLevel> levels = new ArrayList<>();
        if (side == null) return levels;
        for (Object rowValue : Json.asList(side)) {
            List<?> row = Json.asList(rowValue);
            if (row.size() < 2) continue;
            levels.add(new OrderBookLevel(Json.number(row.get(0), 0.0), Json.number(row.get(1), 0.0)));
        }
        return levels;
    }

    private static class CacheEntry {
        final long time;
        final OrderBookContext context;

        CacheEntry(long time, OrderBookContext context) {
            this.time = time;
            this.context = context;
        }
    }
}
