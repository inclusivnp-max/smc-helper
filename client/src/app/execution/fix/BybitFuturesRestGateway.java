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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class BybitFuturesRestGateway implements ExecutionGateway {
    private static final String LIVE_BASE = "https://api.bybit.com";
    private static final String TESTNET_BASE = "https://api-testnet.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final String apiKey;
    private final String secret;
    private final boolean testnet;
    private final String baseUrl;
    private final Map<String, String> symbolByClientId = new ConcurrentHashMap<>();
    private final Map<String, String> exchangeIdByClientId = new ConcurrentHashMap<>();
    private final Map<String, InstrumentSpec> instrumentSpecs = new ConcurrentHashMap<>();

    public BybitFuturesRestGateway(String apiKey, String secret, boolean testnet) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.testnet = testnet;
        this.baseUrl = testnet ? TESTNET_BASE : LIVE_BASE;
    }

    @Override
    public ExecutionReport send(ExecutionOrder order) throws Exception {
        if (!isConnected()) {
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Bybit API keys are not configured");
        }
        Map<String, Object> body = orderBody(order);
        Map<String, Object> json = signedJson("POST", "/v5/order/create", body, "");
        Map<String, Object> result = Json.asObject(json.get("result"));
        String orderId = Json.string(result.get("orderId"), "");
        symbolByClientId.put(order.clientOrderId, order.symbol);
        if (!orderId.isBlank()) exchangeIdByClientId.put(order.clientOrderId, orderId);
        return new ExecutionReport(
                Json.string(result.get("orderLinkId"), order.clientOrderId),
                orderId,
                ExecutionReport.Status.ACCEPTED,
                0.0,
                order.quantity,
                0.0,
                "Bybit accepted: " + Json.string(json.get("retMsg"), "OK"));
    }

    @Override
    public ExecutionReport cancel(String clientOrderId) throws Exception {
        if (!isConnected()) {
            return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Bybit API keys are not configured");
        }
        String symbol = symbolByClientId.get(clientOrderId);
        if (symbol == null || symbol.isBlank()) {
            return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Bybit cancel requires cached symbol; run openOrders first or cancel current-session orders");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "linear");
        body.put("symbol", symbol);
        body.put("orderLinkId", clientOrderId);
        String orderId = exchangeIdByClientId.get(clientOrderId);
        if (orderId != null && !orderId.isBlank()) body.put("orderId", orderId);
        Map<String, Object> json = signedJson("POST", "/v5/order/cancel", body, "");
        Map<String, Object> result = Json.asObject(json.get("result"));
        return new ExecutionReport(
                Json.string(result.get("orderLinkId"), clientOrderId),
                Json.string(result.get("orderId"), orderId == null ? "" : orderId),
                ExecutionReport.Status.CANCELLED,
                0.0,
                0.0,
                "Bybit cancel accepted: " + Json.string(json.get("retMsg"), "OK"));
    }

    @Override
    public List<OpenOrderSnapshot> openOrders() throws Exception {
        if (!isConnected()) return List.of();
        List<OpenOrderSnapshot> result = new ArrayList<>();
        Map<String, String> params = new TreeMap<>();
        params.put("category", "linear");
        params.put("settleCoin", "USDT");
        params.put("openOnly", "0");
        params.put("limit", "50");
        Map<String, Object> json = signedJson("GET", "/v5/order/realtime", null, query(params));
        Map<String, Object> payload = Json.asObject(json.get("result"));
        for (Object item : Json.asList(payload.get("list"))) {
            Map<String, Object> row = Json.asObject(item);
            String clientId = Json.string(row.get("orderLinkId"), "");
            String orderId = Json.string(row.get("orderId"), "");
            String symbol = Json.string(row.get("symbol"), "");
            double quantity = Json.number(row.get("qty"), 0.0);
            double filled = Json.number(row.get("cumExecQty"), 0.0);
            double remaining = Json.number(row.get("leavesQty"), Math.max(0.0, quantity - filled));
            double triggerPrice = Json.number(row.get("triggerPrice"), 0.0);
            if (!clientId.isBlank()) {
                symbolByClientId.put(clientId, symbol);
                exchangeIdByClientId.put(clientId, orderId);
            }
            result.add(new OpenOrderSnapshot(
                    clientId,
                    orderId,
                    symbol,
                    side(Json.string(row.get("side"), "Buy")),
                    type(Json.string(row.get("orderType"), "Limit"), triggerPrice),
                    tif(Json.string(row.get("timeInForce"), "GTC")),
                    quantity,
                    filled,
                    remaining,
                    Json.number(row.get("price"), 0.0),
                    triggerPrice,
                    Json.integer(row.get("updatedTime"), System.currentTimeMillis())));
        }
        return result;
    }

    @Override
    public AccountSnapshot accountSnapshot() throws Exception {
        if (!isConnected()) return AccountSnapshot.ordersOnly(venue(), List.of());
        List<OpenOrderSnapshot> orders = openOrders();
        Map<String, Object> wallet = walletBalance();
        Map<String, Object> walletPayload = Json.asObject(wallet.get("result"));
        Map<String, Object> account = Json.object(first(Json.list(walletPayload.get("list"))));
        Map<String, Object> coin = findCoin(Json.list(account.get("coin")), "USDT");
        double walletBalance = Json.number(account.get("totalWalletBalance"),
                Json.number(coin.get("walletBalance"), Double.NaN));
        double available = Json.number(account.get("totalAvailableBalance"),
                Json.number(coin.get("availableToWithdraw"),
                        Json.number(coin.get("availableBalance"), Double.NaN)));
        double equity = Json.number(account.get("totalEquity"),
                Json.number(coin.get("equity"), Double.NaN));
        double unrealized = Json.number(account.get("totalPerpUPL"),
                Json.number(coin.get("unrealisedPnl"), Double.NaN));

        List<OpenPositionSnapshot> positions = new ArrayList<>();
        Map<String, String> params = new TreeMap<>();
        params.put("category", "linear");
        params.put("settleCoin", "USDT");
        Map<String, Object> positionJson = signedJson("GET", "/v5/position/list", null, query(params));
        Map<String, Object> positionPayload = Json.asObject(positionJson.get("result"));
        for (Object item : Json.list(positionPayload.get("list"))) {
            Map<String, Object> row = Json.object(item);
            double size = Json.number(row.get("size"), 0.0);
            if (Math.abs(size) <= 0.00000001) continue;
            double mark = Json.number(row.get("markPrice"), 0.0);
            positions.add(new OpenPositionSnapshot(
                    Json.string(row.get("symbol"), ""),
                    side(Json.string(row.get("side"), "Buy")),
                    size,
                    Json.number(row.get("avgPrice"), 0.0),
                    mark,
                    Json.number(row.get("positionValue"), Math.abs(size * mark)),
                    Json.number(row.get("unrealisedPnl"), 0.0),
                    Json.number(row.get("leverage"), 0.0),
                    System.currentTimeMillis()));
        }
        return new AccountSnapshot(venue(), "USDT", walletBalance, available, equity, unrealized,
                orders, positions, System.currentTimeMillis());
    }

    @Override
    public OrderNormalization normalizeOrder(ExecutionOrder order) throws Exception {
        return OrderNormalizer.normalize(order, instrumentSpec(order.symbol));
    }

    @Override
    public void setLeverage(String symbol, int leverage) throws Exception {
        if (!isConnected()) return;
        String value = Integer.toString(Math.max(1, Math.min(100, leverage)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "linear");
        body.put("symbol", symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT));
        body.put("buyLeverage", value);
        body.put("sellLeverage", value);
        try {
            signedJson("POST", "/v5/position/set-leverage", body, "");
        } catch (IOException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!message.contains("not modified") && !message.contains("110043")) throw ex;
        }
    }

    @Override
    public boolean isConnected() {
        return !apiKey.isBlank() && !secret.isBlank();
    }

    @Override
    public String venue() {
        return testnet ? "BYBIT-FUTURES-TESTNET" : "BYBIT-FUTURES-LIVE";
    }

    private Map<String, Object> walletBalance() throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("accountType", "UNIFIED");
        params.put("coin", "USDT");
        try {
            return signedJson("GET", "/v5/account/wallet-balance", null, query(params));
        } catch (IOException unifiedError) {
            params.put("accountType", "CONTRACT");
            return signedJson("GET", "/v5/account/wallet-balance", null, query(params));
        }
    }

    private InstrumentSpec instrumentSpec(String symbol) throws Exception {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        InstrumentSpec cached = instrumentSpecs.get(normalized);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 3_600_000L) return cached;

        Map<String, String> params = new TreeMap<>();
        params.put("category", "linear");
        params.put("symbol", normalized);
        Map<String, Object> json = publicJson("/v5/market/instruments-info", query(params));
        long retCode = Json.integer(json.get("retCode"), -1L);
        if (retCode != 0L) {
            throw new IOException(venue() + " instrument retCode " + retCode + ": "
                    + Json.string(json.get("retMsg"), "unknown"));
        }
        Map<String, Object> result = Json.asObject(json.get("result"));
        Map<String, Object> row = instrumentRow(Json.list(result.get("list")), normalized);
        if (row.isEmpty()) throw new IOException("Bybit symbol spec not found: " + normalized);

        Map<String, Object> priceFilter = Json.object(row.get("priceFilter"));
        Map<String, Object> lotFilter = Json.object(row.get("lotSizeFilter"));
        double tick = Json.number(priceFilter.get("tickSize"), 0.0);
        double step = Json.number(lotFilter.get("qtyStep"), 0.0);
        InstrumentSpec spec = new InstrumentSpec(
                venue(),
                normalized,
                tick,
                step,
                Json.number(lotFilter.get("minOrderQty"), 0.0),
                Json.number(lotFilter.get("maxOrderQty"), 0.0),
                Json.number(lotFilter.get("minNotionalValue"), 0.0),
                decimals(tick),
                decimals(step),
                System.currentTimeMillis());
        instrumentSpecs.put(normalized, spec);
        return spec;
    }

    private static Map<String, Object> instrumentRow(List<?> rows, String symbol) {
        for (Object item : rows) {
            Map<String, Object> row = Json.object(item);
            if (symbol.equalsIgnoreCase(Json.string(row.get("symbol"), ""))) return row;
        }
        return Map.of();
    }

    private Map<String, Object> publicJson(String path, String queryString) throws Exception {
        String url = baseUrl + path + (queryString == null || queryString.isBlank() ? "" : "?" + queryString);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(venue() + " public HTTP " + response.statusCode() + ": " + Json.snippet(body));
        }
        return Json.asObject(Json.parse(body));
    }

    private static Object first(List<?> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    private static Map<String, Object> findCoin(List<?> values, String asset) {
        for (Object value : values) {
            Map<String, Object> row = Json.object(value);
            if (asset.equalsIgnoreCase(Json.string(row.get("coin"), ""))) return row;
        }
        return Map.of();
    }

    private Map<String, Object> orderBody(ExecutionOrder order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "linear");
        body.put("symbol", order.symbol);
        body.put("side", order.side == ExecutionOrder.Side.BUY ? "Buy" : "Sell");
        body.put("orderLinkId", order.clientOrderId);
        body.put("qty", qty(order.quantity));
        body.put("positionIdx", 0);
        if (order.strategyTag.contains("protect=")) {
            body.put("reduceOnly", true);
            body.put("closeOnTrigger", true);
        }
        if (order.type == ExecutionOrder.Type.MARKET || order.type == ExecutionOrder.Type.STOP_MARKET) {
            body.put("orderType", "Market");
        } else {
            body.put("orderType", "Limit");
            body.put("price", price(order.price));
            body.put("timeInForce", bybitTif(order.timeInForce));
        }
        if (order.type == ExecutionOrder.Type.STOP_LIMIT || order.type == ExecutionOrder.Type.STOP_MARKET) {
            double trigger = order.stopPrice > 0.0 ? order.stopPrice : order.price;
            body.put("triggerPrice", price(trigger));
            body.put("triggerBy", "LastPrice");
            body.put("triggerDirection", order.side == ExecutionOrder.Side.BUY ? 1 : 2);
        }
        return body;
    }

    private Map<String, Object> signedJson(String method, String path,
                                           Map<String, Object> body,
                                           String queryString) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis());
        String payload = body == null ? queryString : json(body);
        String signature = hmac(timestamp + apiKey + RECV_WINDOW + payload);
        String url = baseUrl + path + (queryString == null || queryString.isBlank() ? "" : "?" + queryString);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("X-BAPI-API-KEY", apiKey)
                .header("X-BAPI-SIGN", signature)
                .header("X-BAPI-TIMESTAMP", timestamp)
                .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .header("Content-Type", "application/json");
        HttpRequest request = "POST".equals(method)
                ? builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build()
                : builder.GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(venue() + " HTTP " + response.statusCode() + ": " + Json.snippet(responseBody));
        }
        Map<String, Object> json = Json.asObject(Json.parse(responseBody));
        long retCode = Json.integer(json.get("retCode"), -1L);
        if (retCode != 0L) {
            throw new IOException(venue() + " retCode " + retCode + ": " + Json.string(json.get("retMsg"), responseBody));
        }
        return json;
    }

    private String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte b : raw) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static String query(Map<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            parts.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }

    private static String json(Map<String, Object> values) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            String encoded = value instanceof Number || value instanceof Boolean
                    ? value.toString()
                    : "\"" + esc(value == null ? "" : value.toString()) + "\"";
            parts.add("\"" + esc(entry.getKey()) + "\":" + encoded);
        }
        return "{" + String.join(",", parts) + "}";
    }

    private static String esc(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String bybitTif(ExecutionOrder.TimeInForce tif) {
        if (tif == ExecutionOrder.TimeInForce.FOK) return "FOK";
        if (tif == ExecutionOrder.TimeInForce.IOC) return "IOC";
        return "GTC";
    }

    private static ExecutionOrder.Side side(String value) {
        return "Sell".equalsIgnoreCase(value) ? ExecutionOrder.Side.SELL : ExecutionOrder.Side.BUY;
    }

    private static ExecutionOrder.Type type(String value) {
        return type(value, 0.0);
    }

    private static ExecutionOrder.Type type(String value, double triggerPrice) {
        if (triggerPrice > 0.0) {
            return "Market".equalsIgnoreCase(value) ? ExecutionOrder.Type.STOP_MARKET : ExecutionOrder.Type.STOP_LIMIT;
        }
        return "Market".equalsIgnoreCase(value) ? ExecutionOrder.Type.MARKET : ExecutionOrder.Type.LIMIT;
    }

    private static ExecutionOrder.TimeInForce tif(String value) {
        try {
            return ExecutionOrder.TimeInForce.valueOf(value == null || value.isBlank() ? "GTC" : value);
        } catch (Exception ex) {
            return ExecutionOrder.TimeInForce.GTC;
        }
    }

    private static String qty(double value) {
        return trim(value, 8);
    }

    private static String price(double value) {
        return trim(value, 8);
    }

    private static String trim(double value, int scale) {
        String text = String.format(Locale.US, "%." + scale + "f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static int decimals(double step) {
        if (!Double.isFinite(step) || step <= 0.0) return 8;
        String text = BigDecimal.valueOf(step).stripTrailingZeros().toPlainString();
        int dot = text.indexOf('.');
        return dot < 0 ? 0 : text.length() - dot - 1;
    }
}
