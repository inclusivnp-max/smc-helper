package app.execution.fix;

import app.common.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class BinanceFuturesRestGateway implements ExecutionGateway {
    private static final String LIVE_BASE = "https://fapi.binance.com/fapi/v1";
    private static final String TESTNET_BASE = "https://testnet.binancefuture.com/fapi/v1";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final String apiKey;
    private final String secret;
    private final String baseUrl;
    private final String apiRoot;
    private final boolean testnet;
    private final Map<String, InstrumentSpec> instrumentSpecs = new ConcurrentHashMap<>();

    public BinanceFuturesRestGateway() {
        this(System.getenv("SMC_BINANCE_API_KEY"),
                System.getenv("SMC_BINANCE_API_SECRET"),
                Boolean.parseBoolean(System.getenv().getOrDefault("SMC_BINANCE_TESTNET", "true")));
    }

    public BinanceFuturesRestGateway(String apiKey, String secret, boolean testnet) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.testnet = testnet;
        this.baseUrl = testnet ? TESTNET_BASE : LIVE_BASE;
        this.apiRoot = testnet ? "https://testnet.binancefuture.com" : "https://fapi.binance.com";
    }

    @Override
    public ExecutionReport send(ExecutionOrder order) throws Exception {
        if (!isConnected()) {
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Binance API keys are not configured");
        }
        Map<String, String> params = baseOrderParams(order);
        String body = signedRequest("POST", "/order", params);
        Map<String, Object> json = Json.asObject(Json.parse(body));
        return reportFromOrderResponse(order.clientOrderId, json, "Binance order accepted");
    }

    @Override
    public ExecutionReport cancel(String clientOrderId) throws Exception {
        if (!isConnected()) {
            return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Binance API keys are not configured");
        }
        Map<String, String> params = new TreeMap<>();
        params.put("origClientOrderId", clientOrderId);
        String body = signedRequest("DELETE", "/order", params);
        Map<String, Object> json = Json.asObject(Json.parse(body));
        return reportFromOrderResponse(clientOrderId, json, "Binance order cancelled");
    }

    @Override
    public List<OpenOrderSnapshot> openOrders() throws Exception {
        if (!isConnected()) return List.of();
        List<OpenOrderSnapshot> result = new ArrayList<>();
        String body = signedRequest("GET", "/openOrders", new TreeMap<>());
        for (Object item : Json.asList(Json.parse(body))) {
            Map<String, Object> row = Json.asObject(item);
            String clientId = Json.string(row.get("clientOrderId"), "");
            String symbol = Json.string(row.get("symbol"), "");
            double quantity = Json.number(row.get("origQty"), 0.0);
            double executed = Json.number(row.get("executedQty"), 0.0);
            double remaining = Math.max(0.0, quantity - executed);
            double price = Json.number(row.get("price"), 0.0);
            double stopPrice = Json.number(row.get("stopPrice"), 0.0);
            result.add(new OpenOrderSnapshot(clientId,
                    Json.string(row.get("orderId"), ""),
                    symbol,
                    side(Json.string(row.get("side"), "BUY")),
                    type(Json.string(row.get("type"), "LIMIT")),
                    tif(Json.string(row.get("timeInForce"), "GTC")),
                    quantity,
                    executed,
                    remaining,
                    price,
                    stopPrice,
                    System.currentTimeMillis()));
        }
        return result;
    }

    @Override
    public AccountSnapshot accountSnapshot() throws Exception {
        if (!isConnected()) return AccountSnapshot.ordersOnly(venue(), List.of());
        List<OpenOrderSnapshot> orders = openOrders();
        Map<String, Object> account = binanceAccount();
        List<AccountAssetSnapshot> assets = accountAssets(account);
        Map<String, Object> usdtAsset = asset(account, "USDT");
        double wallet = Json.number(account.get("totalWalletBalance"),
                firstFinite(sumWallet(assets), Json.number(usdtAsset.get("walletBalance"), Double.NaN)));
        double available = Json.number(account.get("availableBalance"),
                firstFinite(sumAvailable(assets), Json.number(usdtAsset.get("availableBalance"), Double.NaN)));
        double unrealized = Json.number(account.get("totalUnrealizedProfit"),
                firstFinite(sumUnrealized(assets), Json.number(usdtAsset.get("unrealizedProfit"), Double.NaN)));
        double equity = Json.number(account.get("totalMarginBalance"),
                firstFinite(sumMargin(assets),
                        Double.isFinite(wallet) && Double.isFinite(unrealized) ? wallet + unrealized : Double.NaN));
        List<OpenPositionSnapshot> positions;
        try {
            positions = positionRiskSnapshots();
        } catch (Exception ignored) {
            positions = new ArrayList<>();
        }
        for (Object item : Json.list(account.get("positions"))) {
            Map<String, Object> row = Json.object(item);
            double amount = Json.number(row.get("positionAmt"), 0.0);
            if (Math.abs(amount) <= 0.00000001) continue;
            if (containsPosition(positions, Json.string(row.get("symbol"), ""), amount)) continue;
            double entry = Json.number(row.get("entryPrice"), 0.0);
            double mark = Json.number(row.get("markPrice"), 0.0);
            double notional = Json.number(row.get("notional"), Math.abs(amount * mark));
            positions.add(new OpenPositionSnapshot(
                    Json.string(row.get("symbol"), ""),
                    amount >= 0.0 ? ExecutionOrder.Side.BUY : ExecutionOrder.Side.SELL,
                    amount,
                    entry,
                    mark,
                    notional,
                    Json.number(row.get("unrealizedProfit"), 0.0),
                    Json.number(row.get("leverage"), 0.0),
                    System.currentTimeMillis()));
        }
        return new AccountSnapshot(venue(), "USDT", wallet, available, equity, unrealized,
                orders, positions, assets, System.currentTimeMillis());
    }

    private List<OpenPositionSnapshot> positionRiskSnapshots() throws Exception {
        List<OpenPositionSnapshot> positions = new ArrayList<>();
        String body;
        try {
            body = signedRequest("GET", "/fapi/v3/positionRisk", new TreeMap<>());
        } catch (IOException v3Error) {
            body = signedRequest("GET", "/fapi/v2/positionRisk", new TreeMap<>());
        }
        for (Object item : Json.asList(Json.parse(body))) {
            Map<String, Object> row = Json.asObject(item);
            double amount = Json.number(row.get("positionAmt"), 0.0);
            if (Math.abs(amount) <= 0.00000001) continue;
            String positionSide = Json.string(row.get("positionSide"), "BOTH");
            boolean shortSide = "SHORT".equalsIgnoreCase(positionSide) || amount < 0.0;
            double mark = Json.number(row.get("markPrice"), 0.0);
            double entry = Json.number(row.get("entryPrice"), 0.0);
            positions.add(new OpenPositionSnapshot(
                    Json.string(row.get("symbol"), ""),
                    shortSide ? ExecutionOrder.Side.SELL : ExecutionOrder.Side.BUY,
                    amount,
                    entry,
                    mark,
                    Json.number(row.get("notional"), Math.abs(amount * (mark > 0.0 ? mark : entry))),
                    Json.number(row.get("unRealizedProfit"),
                            Json.number(row.get("unrealizedProfit"), 0.0)),
                    Json.number(row.get("leverage"), 0.0),
                    System.currentTimeMillis()));
        }
        return positions;
    }

    private static boolean containsPosition(List<OpenPositionSnapshot> positions, String symbol, double amount) {
        boolean shortSide = amount < 0.0;
        for (OpenPositionSnapshot position : positions) {
            if (symbol.equalsIgnoreCase(position.symbol) && position.longSide() != shortSide) return true;
        }
        return false;
    }

    private static Map<String, Object> asset(Map<String, Object> account, String asset) {
        for (Object item : Json.list(account.get("assets"))) {
            Map<String, Object> row = Json.object(item);
            if (asset.equalsIgnoreCase(Json.string(row.get("asset"), ""))) return row;
        }
        return Map.of();
    }

    private static List<AccountAssetSnapshot> accountAssets(Map<String, Object> account) {
        List<AccountAssetSnapshot> result = new ArrayList<>();
        for (Object item : Json.list(account.get("assets"))) {
            Map<String, Object> row = Json.object(item);
            String asset = Json.string(row.get("asset"), "");
            if (asset.isBlank()) continue;
            double wallet = Json.number(row.get("walletBalance"), Double.NaN);
            double unrealized = Json.number(row.get("unrealizedProfit"),
                    Json.number(row.get("crossUnPnl"), Double.NaN));
            double margin = Json.number(row.get("marginBalance"),
                    Double.isFinite(wallet) && Double.isFinite(unrealized) ? wallet + unrealized : Double.NaN);
            double available = Json.number(row.get("availableBalance"),
                    Json.number(row.get("maxWithdrawAmount"), Double.NaN));
            AccountAssetSnapshot snapshot = new AccountAssetSnapshot(asset, wallet, margin, available,
                    unrealized, Json.integer(row.get("updateTime"), System.currentTimeMillis()));
            if (snapshot.hasValue() || "USDT".equalsIgnoreCase(asset) || "USDC".equalsIgnoreCase(asset)) {
                result.add(snapshot);
            }
        }
        return result;
    }

    private static double sumWallet(List<AccountAssetSnapshot> assets) {
        double total = 0.0;
        boolean any = false;
        for (AccountAssetSnapshot asset : assets) {
            if (!Double.isFinite(asset.walletBalance)) continue;
            total += asset.walletBalance;
            any = true;
        }
        return any ? total : Double.NaN;
    }

    private static double sumMargin(List<AccountAssetSnapshot> assets) {
        double total = 0.0;
        boolean any = false;
        for (AccountAssetSnapshot asset : assets) {
            if (!Double.isFinite(asset.marginBalance)) continue;
            total += asset.marginBalance;
            any = true;
        }
        return any ? total : Double.NaN;
    }

    private static double sumAvailable(List<AccountAssetSnapshot> assets) {
        double total = 0.0;
        boolean any = false;
        for (AccountAssetSnapshot asset : assets) {
            if (!Double.isFinite(asset.availableBalance)) continue;
            total += asset.availableBalance;
            any = true;
        }
        return any ? total : Double.NaN;
    }

    private static double sumUnrealized(List<AccountAssetSnapshot> assets) {
        double total = 0.0;
        boolean any = false;
        for (AccountAssetSnapshot asset : assets) {
            if (!Double.isFinite(asset.unrealizedPnl)) continue;
            total += asset.unrealizedPnl;
            any = true;
        }
        return any ? total : Double.NaN;
    }

    private static double firstFinite(double first, double second) {
        return Double.isFinite(first) ? first : second;
    }

    @Override
    public OrderNormalization normalizeOrder(ExecutionOrder order) throws Exception {
        return OrderNormalizer.normalize(order, instrumentSpec(order.symbol));
    }

    @Override
    public void setLeverage(String symbol, int leverage) throws Exception {
        if (!isConnected()) return;
        Map<String, String> params = new TreeMap<>();
        params.put("symbol", symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT));
        params.put("leverage", Integer.toString(Math.max(1, Math.min(125, leverage))));
        signedRequest("POST", "/leverage", params);
    }

    private Map<String, Object> binanceAccount() throws Exception {
        try {
            return Json.asObject(Json.parse(signedRequest("GET", "/fapi/v3/account", new TreeMap<>())));
        } catch (IOException v3Error) {
            return Json.asObject(Json.parse(signedRequest("GET", "/fapi/v2/account", new TreeMap<>())));
        }
    }

    private InstrumentSpec instrumentSpec(String symbol) throws Exception {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        InstrumentSpec cached = instrumentSpecs.get(normalized);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 3_600_000L) return cached;

        String body = publicRequest("/fapi/v1/exchangeInfo?symbol=" + enc(normalized));
        Map<String, Object> json = Json.asObject(Json.parse(body));
        Map<String, Object> row = symbolRow(Json.list(json.get("symbols")), normalized);
        if (row.isEmpty()) throw new IOException("Binance symbol spec not found: " + normalized);

        double tick = 0.0;
        double step = 0.0;
        double minQty = 0.0;
        double maxQty = 0.0;
        double minNotional = 0.0;
        for (Object item : Json.list(row.get("filters"))) {
            Map<String, Object> filter = Json.object(item);
            String type = Json.string(filter.get("filterType"), "");
            if ("PRICE_FILTER".equals(type)) {
                tick = Json.number(filter.get("tickSize"), tick);
            } else if ("LOT_SIZE".equals(type)) {
                step = Json.number(filter.get("stepSize"), step);
                minQty = Json.number(filter.get("minQty"), minQty);
                maxQty = Json.number(filter.get("maxQty"), maxQty);
            } else if ("MIN_NOTIONAL".equals(type) || "NOTIONAL".equals(type)) {
                minNotional = Json.number(filter.get("notional"),
                        Json.number(filter.get("minNotional"), minNotional));
            }
        }
        InstrumentSpec spec = new InstrumentSpec(
                venue(),
                normalized,
                tick,
                step,
                minQty,
                maxQty,
                minNotional,
                (int) Json.integer(row.get("pricePrecision"), decimals(tick)),
                (int) Json.integer(row.get("quantityPrecision"), decimals(step)),
                System.currentTimeMillis());
        instrumentSpecs.put(normalized, spec);
        return spec;
    }

    private static Map<String, Object> symbolRow(List<?> symbols, String symbol) {
        for (Object item : symbols) {
            Map<String, Object> row = Json.object(item);
            if (symbol.equalsIgnoreCase(Json.string(row.get("symbol"), ""))) return row;
        }
        return Map.of();
    }

    @Override
    public boolean isConnected() {
        return !apiKey.isBlank() && !secret.isBlank();
    }

    @Override
    public String venue() {
        return testnet ? "BINANCE-FUTURES-TESTNET" : "BINANCE-FUTURES-LIVE";
    }

    private Map<String, String> baseOrderParams(ExecutionOrder order) {
        Map<String, String> params = new TreeMap<>();
        params.put("symbol", order.symbol);
        params.put("side", order.side.name());
        params.put("newClientOrderId", order.clientOrderId);
        params.put("newOrderRespType", "RESULT");
        params.put("quantity", qty(order.quantity));
        if (order.strategyTag.contains("protect=")) params.put("reduceOnly", "true");
        switch (order.type) {
            case MARKET -> params.put("type", "MARKET");
            case LIMIT -> {
                params.put("type", "LIMIT");
                params.put("timeInForce", order.timeInForce.name());
                params.put("price", price(order.price));
            }
            case STOP_LIMIT -> {
                params.put("type", "STOP");
                params.put("timeInForce", order.timeInForce.name());
                params.put("price", price(order.price));
                params.put("stopPrice", price(order.stopPrice > 0.0 ? order.stopPrice : order.price));
            }
            case STOP_MARKET -> {
                params.put("type", "STOP_MARKET");
                params.put("stopPrice", price(order.stopPrice > 0.0 ? order.stopPrice : order.price));
                params.put("workingType", "CONTRACT_PRICE");
            }
        }
        return params;
    }

    private String signedRequest(String method, String path, Map<String, String> params) throws Exception {
        Map<String, String> signed = new TreeMap<>(params);
        signed.put("recvWindow", "5000");
        signed.put("timestamp", Long.toString(System.currentTimeMillis()));
        String query = query(signed);
        query += "&signature=" + hmacSha256(query);
        String endpoint = path.startsWith("/fapi/") ? apiRoot + path : baseUrl + path;
        URI uri = URI.create(endpoint + "?" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("X-MBX-APIKEY", apiKey);
        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "DELETE" -> builder.DELETE().build();
            default -> builder.GET().build();
        };
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(venue() + " HTTP " + response.statusCode() + ": " + Json.snippet(body));
        }
        return body;
    }

    private String publicRequest(String pathAndQuery) throws Exception {
        URI uri = URI.create(apiRoot + pathAndQuery);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(venue() + " public HTTP " + response.statusCode() + ": " + Json.snippet(body));
        }
        return body;
    }

    private ExecutionReport reportFromOrderResponse(String clientOrderId, Map<String, Object> json, String fallbackMessage) {
        String statusText = Json.string(json.get("status"), "");
        ExecutionReport.Status status = switch (statusText) {
            case "FILLED" -> ExecutionReport.Status.FILLED;
            case "PARTIALLY_FILLED" -> ExecutionReport.Status.PARTIALLY_FILLED;
            case "CANCELED", "CANCELLED", "EXPIRED" -> ExecutionReport.Status.CANCELLED;
            case "REJECTED" -> ExecutionReport.Status.REJECTED;
            default -> ExecutionReport.Status.ACCEPTED;
        };
        double origQty = Json.number(json.get("origQty"), 0.0);
        double executed = Json.number(json.get("executedQty"), 0.0);
        double avg = Json.number(json.get("avgPrice"), Json.number(json.get("price"), 0.0));
        return new ExecutionReport(
                Json.string(json.get("clientOrderId"), clientOrderId),
                Json.string(json.get("orderId"), ""),
                status,
                executed,
                Math.max(0.0, origQty - executed),
                avg,
                fallbackMessage + " / " + statusText);
    }

    private String query(Map<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            parts.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }

    private String hmacSha256(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte b : raw) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static String qty(double value) {
        return String.format(Locale.US, "%.8f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String price(double value) {
        return String.format(Locale.US, "%.8f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static ExecutionOrder.Side side(String value) {
        return "SELL".equalsIgnoreCase(value) ? ExecutionOrder.Side.SELL : ExecutionOrder.Side.BUY;
    }

    private static ExecutionOrder.Type type(String value) {
        if ("MARKET".equalsIgnoreCase(value)) return ExecutionOrder.Type.MARKET;
        if ("STOP_MARKET".equalsIgnoreCase(value)) return ExecutionOrder.Type.STOP_MARKET;
        if (value != null && value.toUpperCase(Locale.ROOT).contains("STOP")) return ExecutionOrder.Type.STOP_LIMIT;
        return ExecutionOrder.Type.LIMIT;
    }

    private static ExecutionOrder.TimeInForce tif(String value) {
        try {
            return ExecutionOrder.TimeInForce.valueOf(value == null || value.isBlank() ? "GTC" : value);
        } catch (Exception ex) {
            return ExecutionOrder.TimeInForce.GTC;
        }
    }

    private static Object first(List<?> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static int decimals(double step) {
        if (!Double.isFinite(step) || step <= 0.0) return 8;
        String text = BigDecimal.valueOf(step).stripTrailingZeros().toPlainString();
        int dot = text.indexOf('.');
        return dot < 0 ? 0 : text.length() - dot - 1;
    }
}
