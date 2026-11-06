package app.logic;

import app.common.Json;
import app.common.WeightedRateLimiter;
import app.model.Candle;
import app.backtest.Tick;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BinanceClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration RETRY_BASE_DELAY = Duration.ofMillis(250);
    private static final int KLINE_PAGE_LIMIT = 1000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BINANCE_WEIGHT_LIMIT_1M = 2400;
    private static final WeightedRateLimiter RATE_LIMITER =
            new WeightedRateLimiter(BINANCE_WEIGHT_LIMIT_1M, Duration.ofMinutes(1));

    public enum Market {
        SPOT("Spot", "https://api.binance.com/api/v3"),
        FUTURES("Futures", "https://fapi.binance.com/fapi/v1");

        public final String label;
        private final String baseUrl;

        Market(String label, String baseUrl) {
            this.label = label;
            this.baseUrl = baseUrl;
        }
    }

    public static Market marketFromLabel(String label) {
        if (label == null) return Market.FUTURES;
        return label.toLowerCase().contains("spot") ? Market.SPOT : Market.FUTURES;
    }

    public static List<Candle> fetchCandles(String symbol, String interval, int limit) throws IOException, InterruptedException {
        return fetchCandles(symbol, interval, limit, Market.SPOT);
    }

    public static List<Candle> fetchCandles(String symbol, String interval, int limit, Market market) throws IOException, InterruptedException {
        return fetchCandlesPage(symbol, interval, Math.min(KLINE_PAGE_LIMIT, Math.max(1, limit)), market, null);
    }

    public static List<Candle> fetchCandlesHistory(String symbol, String interval, int targetLimit,
                                                   Market market) throws IOException, InterruptedException {
        int target = Math.max(1, targetLimit);
        if (target <= KLINE_PAGE_LIMIT) return fetchCandles(symbol, interval, target, market);

        List<Candle> all = new ArrayList<>(target);
        Long endTime = null;
        while (all.size() < target) {
            int pageLimit = Math.min(KLINE_PAGE_LIMIT, target - all.size());
            List<Candle> page = fetchCandlesPage(symbol, interval, pageLimit, market, endTime);
            if (page.isEmpty()) break;

            all.addAll(page);
            long firstOpenTime = page.get(0).time;
            long nextEndTime = firstOpenTime - 1L;
            if (endTime != null && nextEndTime >= endTime) break;
            endTime = nextEndTime;
            if (page.size() < pageLimit) break;
            Thread.sleep(80L);
        }

        all.sort(Comparator.comparingLong(c -> c.time));
        List<Candle> unique = new ArrayList<>(all.size());
        long lastTime = Long.MIN_VALUE;
        for (Candle candle : all) {
            if (candle.time != lastTime) {
                unique.add(candle);
                lastTime = candle.time;
            }
        }
        if (unique.size() <= target) return unique;
        return new ArrayList<>(unique.subList(unique.size() - target, unique.size()));
    }

    public static long intervalMillis(String interval) {
        if (interval == null || interval.isBlank()) return 60_000L;
        String text = interval.trim().toLowerCase();
        long multiplier;
        try {
            multiplier = Long.parseLong(text.substring(0, text.length() - 1));
        } catch (Exception ex) {
            multiplier = 1L;
        }
        if (text.endsWith("m")) return multiplier * 60_000L;
        if (text.endsWith("h")) return multiplier * 60L * 60_000L;
        if (text.endsWith("d")) return multiplier * 24L * 60L * 60_000L;
        if (text.endsWith("w")) return multiplier * 7L * 24L * 60L * 60_000L;
        return 60_000L;
    }

    public static int matchingHtfHistoryLimit(String htfInterval, List<Candle> ltfHistory) {
        if (ltfHistory == null || ltfHistory.size() < 2) return KLINE_PAGE_LIMIT;
        long first = ltfHistory.get(0).time;
        long last = ltfHistory.get(ltfHistory.size() - 1).time;
        long span = Math.max(0L, last - first);
        long htfMs = Math.max(60_000L, intervalMillis(htfInterval));
        long needed = span / htfMs + 160L;
        return (int) Math.max(KLINE_PAGE_LIMIT, Math.min(10_000L, needed));
    }

    public static String rateLimiterStatus() {
        return RATE_LIMITER.snapshot();
    }

    private static List<Candle> fetchCandlesPage(String symbol, String interval, int limit,
                                                 Market market, Long endTime)
            throws IOException, InterruptedException {
        Market contextMarket = safeMarket(market);
        String url = contextMarket.baseUrl + "/klines?symbol=" + apiSymbol(symbol)
                + "&interval=" + interval
                + "&limit=" + Math.min(KLINE_PAGE_LIMIT, Math.max(1, limit))
                + (endTime == null ? "" : "&endTime=" + endTime);
        return parseCandles(fetchJson(url, "Binance " + contextMarket.label + " klines"));
    }

    private static List<Candle> parseCandles(Object json) throws IOException {
        List<Candle> list = new ArrayList<>();
        for (Object row : Json.asList(json)) {
            List<?> c = Json.asList(row);
            if (c.size() < 6) throw new IOException("Invalid kline row: expected >= 6 fields");
            list.add(new Candle(
                    Json.integer(c.get(0), 0L),
                    Json.number(c.get(1), 0.0),
                    Json.number(c.get(2), 0.0),
                    Json.number(c.get(3), 0.0),
                    Json.number(c.get(4), 0.0),
                    Json.number(c.get(5), 0.0)));
        }
        return list;
    }

    public static double fetchLastPrice(String symbol) throws IOException, InterruptedException {
        return fetchLastPrice(symbol, Market.SPOT);
    }

    public static double fetchLastPrice(String symbol, Market market) throws IOException, InterruptedException {
        Market contextMarket = safeMarket(market);
        String url = contextMarket.baseUrl + "/ticker/price?symbol=" + apiSymbol(symbol);
        Map<String, Object> json = Json.asObject(fetchJson(url, "Binance " + contextMarket.label + " ticker"));
        double price = Json.number(json.get("price"), Double.NaN);
        if (!Double.isFinite(price) || price <= 0.0) throw new IOException("Binance ticker response has no valid price");
        return price;
    }

    public static List<Tick> fetchAggTrades(String symbol, long startTime, long endTime,
                                            Market market, int maxPages) throws IOException, InterruptedException {
        List<Tick> ticks = new ArrayList<>();
        long cursor = startTime;
        int pages = 0;
        while (cursor <= endTime && pages++ < Math.max(1, maxPages)) {
            List<Tick> page = fetchAggTradesPage(symbol, cursor, endTime, market);
            if (page.isEmpty()) break;
            long lastTime = cursor;
            for (Tick tick : page) {
                if (tick.time >= startTime && tick.time <= endTime) ticks.add(tick);
                lastTime = Math.max(lastTime, tick.time);
            }
            if (page.size() < 1000 || lastTime <= cursor) break;
            cursor = lastTime + 1;
        }
        ticks.sort(Comparator.comparingLong((Tick t) -> t.time).thenComparingLong(t -> t.aggregateId));
        return ticks;
    }

    public static double fetchOpenInterest(String symbol) throws IOException, InterruptedException {
        String url = Market.FUTURES.baseUrl + "/openInterest?symbol=" + apiSymbol(symbol);
        Map<String, Object> json = Json.asObject(fetchJson(url, "Binance Futures openInterest"));
        return Json.number(json.get("openInterest"), 0.0);
    }

    public static double fetchFundingRate(String symbol) throws IOException, InterruptedException {
        String url = Market.FUTURES.baseUrl + "/premiumIndex?symbol=" + apiSymbol(symbol);
        Map<String, Object> json = Json.asObject(fetchJson(url, "Binance Futures premiumIndex"));
        return Json.number(json.get("lastFundingRate"), 0.0);
    }

    public static List<Double> fetchOpenInterestHistory(String symbol, String period, int limit)
            throws IOException, InterruptedException {
        String url = "https://fapi.binance.com/futures/data/openInterestHist?symbol=" + apiSymbol(symbol)
                + "&period=" + period
                + "&limit=" + Math.max(2, Math.min(30, limit));
        List<Double> values = new ArrayList<>();
        for (Object row : Json.asList(fetchJson(url, "Binance Futures openInterestHist"))) {
            values.add(Json.number(Json.asObject(row).get("sumOpenInterest"), 0.0));
        }
        return values;
    }

    public static String fetchDepthJson(String symbol, Market market, int limit) throws IOException, InterruptedException {
        Market contextMarket = safeMarket(market);
        String url = contextMarket.baseUrl + "/depth?symbol=" + apiSymbol(symbol) + "&limit=" + Math.max(5, Math.min(1000, limit));
        return fetchBody(url, "Binance " + contextMarket.label + " depth");
    }

    private static List<Tick> fetchAggTradesPage(String symbol, long startTime, long endTime,
                                                 Market market) throws IOException, InterruptedException {
        Market contextMarket = safeMarket(market);
        String url = contextMarket.baseUrl + "/aggTrades?symbol=" + apiSymbol(symbol)
                + "&startTime=" + startTime
                + "&endTime=" + endTime
                + "&limit=1000";
        return parseAggTrades(fetchJson(url, "Binance " + contextMarket.label + " aggTrades"));
    }

    private static List<Tick> parseAggTrades(Object json) throws IOException {
        List<Tick> ticks = new ArrayList<>();
        for (Object item : Json.asList(json)) {
            Map<String, Object> row = Json.asObject(item);
            long id = Json.integer(row.get("a"), 0L);
            long time = Json.integer(row.get("T"), 0L);
            double price = Json.number(row.get("p"), 0.0);
            double qty = Json.number(row.get("q"), 0.0);
            boolean buyerMaker = Json.bool(row.get("m"), false);
            if (time > 0 && price > 0.0) ticks.add(new Tick(id, time, price, qty, buyerMaker));
        }
        return ticks;
    }

    private static Object fetchJson(String url, String label) throws IOException, InterruptedException {
        String body = fetchBody(url, label);
        try {
            return Json.parse(body);
        } catch (IOException ex) {
            throw new IOException(label + " invalid JSON: " + Json.snippet(body), ex);
        }
    }

    private static String fetchBody(String url, String label) throws IOException, InterruptedException {
        IOException lastError = null;
        int weight = requestWeight(url);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            RATE_LIMITER.acquire(weight);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp;
            try {
                resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (IOException ex) {
                lastError = ex;
                if (attempt >= MAX_ATTEMPTS) break;
                sleepBeforeRetry(attempt, null);
                continue;
            }

            syncExchangeWeight(resp);
            String body = resp.body() == null ? "" : resp.body();
            int status = resp.statusCode();
            if (status == 200) return body;

            IOException httpError = new IOException(label + " HTTP " + status + ": " + Json.snippet(body));
            if (status == 429 || status == 418) {
                Duration wait = retryAfter(resp);
                RATE_LIMITER.backoff(wait);
                lastError = httpError;
                if (attempt < MAX_ATTEMPTS) continue;
            } else if (retryableStatus(status) && attempt < MAX_ATTEMPTS) {
                lastError = httpError;
                sleepBeforeRetry(attempt, null);
                continue;
            }
            throw httpError;
        }
        throw new IOException(label + " unavailable after " + MAX_ATTEMPTS + " attempts: "
                + (lastError == null ? "unknown network error" : lastError.getMessage()), lastError);
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 425 || (status >= 500 && status <= 599);
    }

    private static void sleepBeforeRetry(int attempt, Duration preferred) throws InterruptedException {
        long delay = preferred == null
                ? RETRY_BASE_DELAY.toMillis() * (1L << Math.max(0, attempt - 1))
                : preferred.toMillis();
        Thread.sleep(Math.max(100L, Math.min(2_500L, delay)));
    }

    private static void syncExchangeWeight(HttpResponse<?> resp) {
        if (resp == null) return;
        resp.headers().firstValue("X-MBX-USED-WEIGHT-1M").ifPresent(value -> {
            try {
                RATE_LIMITER.syncUsedWeight(Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
            }
        });
    }

    private static Duration retryAfter(HttpResponse<?> resp) {
        if (resp == null) return Duration.ofSeconds(10);
        return resp.headers().firstValue("Retry-After")
                .map(BinanceClient::parseRetryAfter)
                .orElse(Duration.ofSeconds(10));
    }

    private static Duration parseRetryAfter(String value) {
        try {
            double seconds = Double.parseDouble(value.trim());
            return Duration.ofMillis(Math.max(1_000L, (long) Math.ceil(seconds * 1000.0)));
        } catch (Exception ignored) {
            return Duration.ofSeconds(10);
        }
    }

    private static int requestWeight(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/depth")) return depthWeight(queryInt(uri.getQuery(), "limit", 100));
            if (path.endsWith("/klines")) return 2;
            if (path.endsWith("/aggTrades")) return 4;
            if (path.endsWith("/ticker/price")) return 1;
            if (path.endsWith("/openInterest")) return 1;
            if (path.endsWith("/premiumIndex")) return 1;
            if (path.endsWith("/openInterestHist")) return 1;
        } catch (Exception ignored) {
        }
        return 1;
    }

    private static int depthWeight(int limit) {
        if (limit <= 100) return 5;
        if (limit <= 500) return 25;
        return 50;
    }

    private static int queryInt(String query, String key, int fallback) {
        if (query == null || key == null || key.isBlank()) return fallback;
        String prefix = key + "=";
        for (String part : query.split("&")) {
            if (!part.startsWith(prefix)) continue;
            try {
                return Integer.parseInt(part.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Market safeMarket(Market market) {
        return market == null ? Market.FUTURES : market;
    }

    private static String apiSymbol(String symbol) {
        String text = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (text.isBlank()) throw new IllegalArgumentException("Symbol is blank");
        return text;
    }
}
