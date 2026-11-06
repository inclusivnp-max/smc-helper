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

public class BingXFuturesRestGateway implements ExecutionGateway {
    private static final String BASE_URL = "https://open-api.bingx.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final String apiKey;
    private final String secret;
    private final String sourceKey;
    private final boolean testnet;
    private final Map<String, String> symbolByClientId = new ConcurrentHashMap<>();
    private final Map<String, String> exchangeIdByClientId = new ConcurrentHashMap<>();
    private final Map<String, InstrumentSpec> instrumentSpecs = new ConcurrentHashMap<>();

    public BingXFuturesRestGateway(String apiKey, String secret, String sourceKey, boolean testnet) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.sourceKey = sourceKey == null ? "" : sourceKey.trim();
        this.testnet = testnet;
    }

    @Override
    public ExecutionReport send(ExecutionOrder order) throws Exception {
        if (testnet) {
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "BingX public swap testnet is not configured in this adapter; use Paper or Live");
        }
        if (!isConnected()) {
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "BingX API keys are not configured");
        }
        Map<String, String> params = orderParams(order);
        Map<String, Object> json = signedRequest("POST", "/openApi/swap/v2/trade/order", params);
        Map<String, Object> data = Json.asObject(json.get("data"));
        String orderId = Json.string(data.get("orderId"), Json.string(data.get("orderID"), ""));
        symbolByClientId.put(order.clientOrderId, order.symbol);
        if (!orderId.isBlank()) exchangeIdByClientId.put(order.clientOrderId, orderId);
        return new ExecutionReport(
                order.clientOrderId,
                orderId,
                ExecutionReport.Status.ACCEPTED,
                0.0,
                order.quantity,
                0.0,
                "BingX accepted: " + Json.string(json.get("msg"), "OK"));
    }

    @Override
    public ExecutionReport cancel(String clientOrderId) throws Exception {
        if (testnet) {
            return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "BingX testnet is not configured");
        }
        if (!isConnected()) {
            return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "BingX API keys are not configured");
        }
        String symbol = symbolByClientId.get(clientOrderId);
        if (symbol == null || symbol.isBlank()) {
            return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "BingX cancel requires cached symbol; run openOrders first or cancel current-session orders");
        }
        Map<String, String> params = new TreeMap<>();
        params.put("symbol", bingxSymbol(symbol));
        String orderId = exchangeIdByClientId.get(clientOrderId);
        if (orderId != null && !orderId.isBlank()) params.put("orderId", orderId);
        else params.put("clientOrderID", clientOrderId);
        Map<String, Object> json = signedRequest("DELETE", "/openApi/swap/v2/trade/order", params);
        return new ExecutionReport(clientOrderId, orderId == null ? "" : orderId,
                ExecutionReport.Status.CANCELLED, 0.0, 0.0,
                "BingX cancel accepted: " + Json.string(json.get("msg"), "OK"));
    }

    @Override
    public List<OpenOrderSnapshot> openOrders() throws Exception {
        if (!isConnected() || testnet) return List.of();
        List<OpenOrderSnapshot> result = new ArrayList<>();
        Map<String, String> params = new TreeMap<>();
        Map<String, Object> json = signedRequest("GET", "/openApi/swap/v2/trade/openOrders", params);
        Object dataValue = json.get("data");
        List<?> rows;
        if (dataValue instanceof Map<?, ?> map) rows = Json.asList(Json.asObject(map).get("orders"));
        else rows = Json.asList(dataValue);
        for (Object item : rows) {
            Map<String, Object> row = Json.asObject(item);
            String clientId = Json.string(row.get("clientOrderID"), Json.string(row.get("clientOrderId"), ""));
            String orderId = Json.string(row.get("orderId"), Json.string(row.get("orderID"), ""));
            String symbol = normalizeSymbol(Json.string(row.get("symbol"), ""));
            double quantity = Json.number(row.get("origQty"), Json.number(row.get("quantity"), 0.0));
            double filled = Json.number(row.get("executedQty"), Json.number(row.get("filledQty"), 0.0));
            double remaining = Math.max(0.0, quantity - filled);
            double stopPrice = Json.number(row.get("stopPrice"), Json.number(row.get("triggerPrice"), 0.0));
            if (!clientId.isBlank()) {
                symbolByClientId.put(clientId, symbol);
                exchangeIdByClientId.put(clientId, orderId);
            }
            result.add(new OpenOrderSnapshot(
                    clientId,
                    orderId,
                    symbol,
                    side(Json.string(row.get("side"), "BUY")),
                    type(Json.string(row.get("type"), "LIMIT"), stopPrice),
                    tif(Json.string(row.get("timeInForce"), "GTC")),
                    quantity,
                    filled,
                    remaining,
                    Json.number(row.get("price"), 0.0),
                    stopPrice,
                    Json.integer(row.get("updateTime"), System.currentTimeMillis())));
        }
        return result;
    }

    @Override
    public AccountSnapshot accountSnapshot() throws Exception {
        if (!isConnected() || testnet) return AccountSnapshot.ordersOnly(venue(), List.of());
        List<OpenOrderSnapshot> orders = openOrders();
        Map<String, Object> balanceJson = signedRequest("GET", "/openApi/swap/v2/user/balance", new TreeMap<>());
        Map<String, Object> balance = balancePayload(balanceJson.get("data"));
        double wallet = Json.number(balance.get("balance"),
                Json.number(balance.get("walletBalance"), Double.NaN));
        double available = Json.number(balance.get("availableMargin"),
                Json.number(balance.get("availableBalance"), Double.NaN));
        double equity = Json.number(balance.get("equity"), wallet);
        double unrealized = Json.number(balance.get("unrealizedProfit"),
                Json.number(balance.get("unrealizedPnl"), Double.NaN));

        List<OpenPositionSnapshot> positions = new ArrayList<>();
        Map<String, Object> positionsJson = signedRequest("GET", "/openApi/swap/v2/user/positions", new TreeMap<>());
        for (Object item : dataRows(positionsJson.get("data"))) {
            Map<String, Object> row = Json.object(item);
            double amount = Json.number(row.get("positionAmt"),
                    Json.number(row.get("availableAmt"), Json.number(row.get("quantity"), 0.0)));
            if (Math.abs(amount) <= 0.00000001) continue;
            String sideText = Json.string(row.get("positionSide"), Json.string(row.get("side"), "LONG"));
            ExecutionOrder.Side side = sideText.equalsIgnoreCase("SHORT") || sideText.equalsIgnoreCase("SELL")
                    ? ExecutionOrder.Side.SELL
                    : ExecutionOrder.Side.BUY;
            double mark = Json.number(row.get("markPrice"), 0.0);
            positions.add(new OpenPositionSnapshot(
                    normalizeSymbol(Json.string(row.get("symbol"), "")),
                    side,
                    amount,
                    Json.number(row.get("avgPrice"),
                            Json.number(row.get("entryPrice"), 0.0)),
                    mark,
                    Json.number(row.get("positionValue"), Math.abs(amount * mark)),
                    Json.number(row.get("unrealizedProfit"),
                            Json.number(row.get("unrealizedPnl"), 0.0)),
                    Json.number(row.get("leverage"), 0.0),
                    System.currentTimeMillis()));
        }
        return new AccountSnapshot(venue(), "USDT", wallet, available, equity, unrealized,
                orders, positions, System.currentTimeMillis());
    }

    @Override
    public OrderNormalization normalizeOrder(ExecutionOrder order) throws Exception {
        return OrderNormalizer.normalize(order, instrumentSpec(order.symbol));
    }

    @Override
    public void setLeverage(String symbol, int leverage) throws Exception {
        if (!isConnected() || testnet) return;
        String value = Integer.toString(Math.max(1, Math.min(125, leverage)));
        String exchangeSymbol = bingxSymbol(symbol);
        for (String side : List.of("LONG", "SHORT")) {
            Map<String, String> params = new TreeMap<>();
            params.put("symbol", exchangeSymbol);
            params.put("side", side);
            params.put("leverage", value);
            signedRequest("POST", "/openApi/swap/v2/trade/leverage", params);
        }
    }

    @Override
    public boolean isConnected() {
        return !apiKey.isBlank() && !secret.isBlank();
    }

    @Override
    public String venue() {
        return testnet ? "BINGX-FUTURES-TESTNET" : "BINGX-FUTURES-LIVE";
    }

    private static Map<String, Object> balancePayload(Object data) {
        Map<String, Object> object = Json.object(data);
        Map<String, Object> nested = Json.object(object.get("balance"));
        if (!nested.isEmpty()) return nested;
        if (!object.isEmpty()) return object;
        List<?> rows = Json.list(data);
        return rows.isEmpty() ? Map.of() : Json.object(rows.get(0));
    }

    private static List<?> dataRows(Object data) {
        Map<String, Object> object = Json.object(data);
        if (object.containsKey("positions")) return Json.list(object.get("positions"));
        if (object.containsKey("position")) return Json.list(object.get("position"));
        if (object.containsKey("list")) return Json.list(object.get("list"));
        return Json.list(data);
    }

    private InstrumentSpec instrumentSpec(String symbol) throws Exception {
        String normalized = normalizeSymbol(symbol);
        InstrumentSpec cached = instrumentSpecs.get(normalized);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 3_600_000L) return cached;

        String exchangeSymbol = bingxSymbol(normalized);
        Map<String, Object> json = publicJson("/openApi/swap/v2/quote/contracts",
                "symbol=" + enc(exchangeSymbol));
        Map<String, Object> row = contractRow(json.get("data"), exchangeSymbol);
        if (row.isEmpty()) throw new IOException("BingX symbol spec not found: " + normalized);

        int pricePrecision = (int) Json.integer(row.get("pricePrecision"),
                Json.integer(row.get("priceScale"), 8L));
        int quantityPrecision = (int) Json.integer(row.get("quantityPrecision"),
                Json.integer(row.get("quantityScale"), 8L));
        double tick = Json.number(row.get("tickSize"),
                Json.number(row.get("priceStep"), precisionStep(pricePrecision)));
        double step = Json.number(row.get("stepSize"),
                Json.number(row.get("quantityStep"),
                        Json.number(row.get("qtyStep"), precisionStep(quantityPrecision))));
        InstrumentSpec spec = new InstrumentSpec(
                venue(),
                normalized,
                tick,
                step,
                Json.number(row.get("minQty"),
                        Json.number(row.get("minTradeNum"),
                                Json.number(row.get("tradeMinQuantity"), 0.0))),
                Json.number(row.get("maxQty"),
                        Json.number(row.get("maxTradeNum"),
                                Json.number(row.get("tradeMaxQuantity"), 0.0))),
                Json.number(row.get("minNotional"),
                        Json.number(row.get("minTradeAmount"), 0.0)),
                pricePrecision,
                quantityPrecision,
                System.currentTimeMillis());
        instrumentSpecs.put(normalized, spec);
        return spec;
    }

    private Map<String, Object> publicJson(String path, String queryString) throws Exception {
        String url = BASE_URL + path + (queryString == null || queryString.isBlank() ? "" : "?" + queryString);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(venue() + " public HTTP " + response.statusCode() + ": " + Json.snippet(body));
        }
        Map<String, Object> json = Json.asObject(Json.parse(body));
        long code = Json.integer(json.get("code"), 0L);
        boolean success = Json.bool(json.get("success"), code == 0L);
        if (code != 0L && !success) {
            throw new IOException(venue() + " public code " + code + ": " + Json.string(json.get("msg"), body));
        }
        return json;
    }

    private static Map<String, Object> contractRow(Object data, String exchangeSymbol) {
        List<?> rows = Json.list(data);
        if (rows.isEmpty()) {
            Map<String, Object> single = Json.object(data);
            if (!single.isEmpty()) return single;
        }
        for (Object item : rows) {
            Map<String, Object> row = Json.object(item);
            String rowSymbol = Json.string(row.get("symbol"), "");
            if (exchangeSymbol.equalsIgnoreCase(rowSymbol)) return row;
        }
        return rows.isEmpty() ? Map.of() : Json.object(rows.get(0));
    }

    private Map<String, String> orderParams(ExecutionOrder order) {
        Map<String, String> params = new TreeMap<>();
        params.put("symbol", bingxSymbol(order.symbol));
        params.put("side", order.side.name());
        params.put("type", bingxType(order.type));
        params.put("quantity", qty(order.quantity));
        params.put("clientOrderID", order.clientOrderId);
        params.put("positionSide", order.side == ExecutionOrder.Side.BUY ? "LONG" : "SHORT");
        if (order.strategyTag.contains("protect=")) {
            params.put("reduceOnly", "true");
            params.put("positionSide", order.side == ExecutionOrder.Side.BUY ? "SHORT" : "LONG");
        }
        if (order.type != ExecutionOrder.Type.MARKET && order.type != ExecutionOrder.Type.STOP_MARKET) {
            params.put("price", price(order.price));
            params.put("timeInForce", bingxTif(order.timeInForce));
        }
        if (order.type == ExecutionOrder.Type.STOP_LIMIT || order.type == ExecutionOrder.Type.STOP_MARKET) {
            params.put("stopPrice", price(order.stopPrice > 0.0 ? order.stopPrice : order.price));
        }
        return params;
    }

    private Map<String, Object> signedRequest(String method, String path, Map<String, String> params) throws Exception {
        Map<String, String> signed = new TreeMap<>(params);
        signed.put("timestamp", Long.toString(System.currentTimeMillis()));
        String query = query(signed);
        String signature = hmac(query);
        URI uri = URI.create(BASE_URL + path + "?" + query + "&signature=" + signature);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("X-BX-APIKEY", apiKey);
        if (!sourceKey.isBlank()) builder.header("X-SOURCE-KEY", sourceKey);
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
        Map<String, Object> json = Json.asObject(Json.parse(body));
        long code = Json.integer(json.get("code"), 0L);
        boolean success = Json.bool(json.get("success"), code == 0L);
        if (code != 0L && !success) {
            throw new IOException(venue() + " code " + code + ": " + Json.string(json.get("msg"), body));
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

    private static String bingxSymbol(String symbol) {
        String text = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT).replace("/", "-");
        if (text.endsWith("USDT") && !text.contains("-")) return text.substring(0, text.length() - 4) + "-USDT";
        return text;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.replace("-", "").toUpperCase(Locale.ROOT);
    }

    private static String bingxType(ExecutionOrder.Type type) {
        if (type == ExecutionOrder.Type.MARKET) return "MARKET";
        if (type == ExecutionOrder.Type.STOP_MARKET) return "STOP_MARKET";
        if (type == ExecutionOrder.Type.STOP_LIMIT) return "STOP";
        return "LIMIT";
    }

    private static String bingxTif(ExecutionOrder.TimeInForce tif) {
        if (tif == ExecutionOrder.TimeInForce.IOC) return "IOC";
        if (tif == ExecutionOrder.TimeInForce.FOK) return "FOK";
        return "GTC";
    }

    private static ExecutionOrder.Side side(String value) {
        return "SELL".equalsIgnoreCase(value) ? ExecutionOrder.Side.SELL : ExecutionOrder.Side.BUY;
    }

    private static ExecutionOrder.Type type(String value) {
        return type(value, 0.0);
    }

    private static ExecutionOrder.Type type(String value, double stopPrice) {
        if ("MARKET".equalsIgnoreCase(value)) return ExecutionOrder.Type.MARKET;
        if ("STOP_MARKET".equalsIgnoreCase(value)) return ExecutionOrder.Type.STOP_MARKET;
        if (stopPrice > 0.0 && value != null && value.toUpperCase(Locale.ROOT).contains("MARKET")) {
            return ExecutionOrder.Type.STOP_MARKET;
        }
        if (value != null && value.toUpperCase(Locale.ROOT).contains("STOP")) return ExecutionOrder.Type.STOP_LIMIT;
        if (stopPrice > 0.0) return ExecutionOrder.Type.STOP_LIMIT;
        return ExecutionOrder.Type.LIMIT;
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

    private static double precisionStep(int precision) {
        return BigDecimal.ONE.movePointLeft(Math.max(0, precision)).doubleValue();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
