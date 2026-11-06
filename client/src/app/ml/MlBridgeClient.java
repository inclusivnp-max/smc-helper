package app.ml;

import app.learning.AdaptiveFactorWeights;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class MlBridgeClient {
    private static final Duration TIMEOUT = Duration.ofMillis(650);
    private static final int BINARY_MAGIC = 0x534D434D; // SMCM
    private static final int BINARY_VERSION = 1;
    private static final int MAX_BINARY_RESPONSE_BYTES = 1024;

    // Shared across every MlBridgeClient instance instead of one-per-instance: FactorWeightOptimizer
    // .loadOrRequest() constructs a `new MlBridgeClient()` on every full-context SMCAnalyzer.analyze()
    // call (every live re-analysis AND every radar full-context pass, per symbol) just to check
    // enabled() -- with the bridge disabled (the default), that discarded instance's HttpClient was
    // being built and immediately abandoned every single time. On Java 17, HttpClient has no
    // explicit close() (that only arrived in JDK 21), so each abandoned client's internal
    // selector-manager thread lingered until GC got around to it -- confirmed live via jstack
    // showing HttpClient-10-SelectorManager after only ~14 minutes of light use. HttpClient is
    // explicitly documented as safe for concurrent reuse, so sharing one is the correct fix, not
    // a workaround (same pattern BinanceClient.HTTP_CLIENT already uses).
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String endpoint;
    private final String httpEndpoint;
    private final String transport;

    public MlBridgeClient() {
        this(resolveEndpoint(), resolveHttpEndpoint(), resolveTransport());
    }

    public MlBridgeClient(String endpoint) {
        this(endpoint, endpoint, endpoint != null && endpoint.startsWith("tcp://") ? "binary" : "http");
    }

    private MlBridgeClient(String endpoint, String httpEndpoint, String transport) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.httpEndpoint = httpEndpoint == null ? "" : httpEndpoint.trim();
        this.transport = transport == null ? "http" : transport.trim().toLowerCase();
    }

    public boolean enabled() {
        return !endpoint.isBlank() || !httpEndpoint.isBlank();
    }

    public String transportLabel() {
        if (binaryTransport()) {
            return "binary TCP " + endpoint + (httpEndpoint.isBlank() ? "" : " · HTTP fallback " + httpEndpoint);
        }
        return "HTTP JSON " + endpoint;
    }

    public String probe() throws IOException, InterruptedException {
        if (!enabled()) throw new IOException("ML bridge is disabled: start ML service first");
        MlFeatureVector vector = new MlFeatureVector("BTCUSDT", 70, 0.0, 0.0, 1.0, false, 55, "health");
        long started = System.nanoTime();
        AdaptiveFactorWeights weights = requestWeightsStrict(vector, AdaptiveFactorWeights.defaults(vector.symbol));
        long ms = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        return "ML bridge " + transportLabel() + " OK " + ms + " ms: " + weights.summary();
    }

    public AdaptiveFactorWeights requestWeights(MlFeatureVector vector,
                                                AdaptiveFactorWeights fallback) {
        if (!enabled() || vector == null) return fallback;
        try {
            return requestWeightsStrict(vector, fallback);
        } catch (IOException | InterruptedException | IllegalArgumentException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return fallback;
        }
    }

    private AdaptiveFactorWeights requestWeightsStrict(MlFeatureVector vector,
                                                       AdaptiveFactorWeights fallback)
            throws IOException, InterruptedException {
        if (binaryTransport() && !endpoint.isBlank()) {
            try {
                return requestBinaryWeights(vector);
            } catch (IOException | IllegalArgumentException ex) {
                if (httpEndpoint.isBlank()) throw ex;
            }
        }
        return requestHttpWeights(vector, fallback, httpEndpoint.isBlank() ? endpoint : httpEndpoint);
    }

    private AdaptiveFactorWeights requestHttpWeights(MlFeatureVector vector,
                                                     AdaptiveFactorWeights fallback,
                                                     String url)
            throws IOException, InterruptedException {
        if (url == null || url.isBlank()) return fallback;
        String payload = toJson(vector);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ML bridge HTTP " + response.statusCode());
        }
        return parseWeights(vector.symbol, response.body(), fallback);
    }

    private AdaptiveFactorWeights requestBinaryWeights(MlFeatureVector vector) throws IOException {
        URI uri = URI.create(endpoint);
        String host = uri.getHost() == null || uri.getHost().isBlank() ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 8766;
        byte[] request = binaryRequest(vector);
        try (Socket socket = new Socket()) {
            int timeoutMillis = (int) TIMEOUT.toMillis();
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(request.length);
            out.write(request);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = in.readInt();
            if (length <= 0 || length > MAX_BINARY_RESPONSE_BYTES) {
                throw new IOException("Invalid ML binary response size: " + length);
            }
            byte[] response = in.readNBytes(length);
            if (response.length != length) throw new IOException("Incomplete ML binary response");
            return parseBinaryWeights(vector.symbol, response);
        }
    }

    private byte[] binaryRequest(MlFeatureVector vector) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out);
        writeString(out, vector.symbol);
        out.writeInt(vector.smcScore);
        out.writeDouble(vector.openInterestChangePct);
        out.writeDouble(vector.fundingRate);
        out.writeDouble(vector.domImbalanceRatio);
        out.writeBoolean(vector.spoofingDetected);
        out.writeInt(vector.regimeScore);
        writeString(out, vector.regimeType);
        out.flush();
        return bytes.toByteArray();
    }

    private AdaptiveFactorWeights parseBinaryWeights(String symbol, byte[] response) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(response));
        int magic = in.readInt();
        int version = in.readUnsignedByte();
        if (magic != BINARY_MAGIC || version != BINARY_VERSION) {
            throw new IOException("Unsupported ML binary response");
        }
        double displacement = in.readDouble();
        double volume = in.readDouble();
        double liquidity = in.readDouble();
        double orderFlow = in.readDouble();
        double regime = in.readDouble();
        return new AdaptiveFactorWeights(symbol, displacement, volume, liquidity,
                orderFlow, regime, System.currentTimeMillis());
    }

    private void writeHeader(DataOutputStream out) throws IOException {
        out.writeInt(BINARY_MAGIC);
        out.writeByte(BINARY_VERSION);
    }

    private void writeString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) throw new IOException("String too long for ML binary frame");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private AdaptiveFactorWeights parseWeights(String symbol, String json, AdaptiveFactorWeights fallback) {
        if (json == null || json.isBlank()) return fallback;
        double displacement = doubleField(json, "displacement", fallback.displacementWeight);
        double volume = doubleField(json, "volume", fallback.volumeWeight);
        double liquidity = doubleField(json, "liquidity", fallback.liquidityWeight);
        double orderFlow = doubleField(json, "orderFlow", fallback.orderFlowWeight);
        double regime = doubleField(json, "regime", fallback.regimeWeight);
        return new AdaptiveFactorWeights(symbol, displacement, volume, liquidity,
                orderFlow, regime, System.currentTimeMillis());
    }

    private double doubleField(String json, String key, double fallback) {
        String quoted = "\"" + key + "\"";
        int start = json.indexOf(quoted);
        if (start < 0) return fallback;
        start = json.indexOf(':', start + quoted.length());
        if (start < 0) return fallback;
        start++;
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (!(Character.isDigit(ch) || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E')) break;
            end++;
        }
        if (end <= start) return fallback;
        try {
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String toJson(MlFeatureVector vector) {
        return "{"
                + "\"symbol\":\"" + escape(vector.symbol) + "\","
                + "\"smcScore\":" + vector.smcScore + ","
                + "\"openInterestChangePct\":" + vector.openInterestChangePct + ","
                + "\"fundingRate\":" + vector.fundingRate + ","
                + "\"domImbalanceRatio\":" + vector.domImbalanceRatio + ","
                + "\"spoofingDetected\":" + vector.spoofingDetected + ","
                + "\"regimeScore\":" + vector.regimeScore + ","
                + "\"regimeType\":\"" + escape(vector.regimeType) + "\""
                + "}";
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String resolveEndpoint() {
        if ("binary".equals(resolveTransport())) {
            String fastProperty = System.getProperty("smc.ml.bridge.fast.url", "");
            if (!fastProperty.isBlank()) return fastProperty;
            String fastEnv = System.getenv("SMC_ML_BRIDGE_FAST_URL");
            if (fastEnv != null && !fastEnv.isBlank()) return fastEnv;
            return "tcp://127.0.0.1:8766";
        }
        return resolveHttpEndpoint();
    }

    private static String resolveHttpEndpoint() {
        String property = System.getProperty("smc.ml.bridge.url", "");
        if (!property.isBlank()) return property;
        String env = System.getenv("SMC_ML_BRIDGE_URL");
        return env == null ? "" : env;
    }

    private static String resolveTransport() {
        String property = System.getProperty("smc.ml.bridge.transport", "");
        if (!property.isBlank()) return normalizeTransport(property);
        String env = System.getenv("SMC_ML_BRIDGE_TRANSPORT");
        return env == null || env.isBlank() ? "http" : normalizeTransport(env);
    }

    private boolean binaryTransport() {
        return "binary".equals(transport) || "tcp".equals(transport) || endpoint.startsWith("tcp://");
    }

    private static String normalizeTransport(String value) {
        String text = value == null ? "" : value.trim().toLowerCase();
        if ("tcp".equals(text) || "binary-tcp".equals(text)) {
            return "binary";
        }
        return "binary".equals(text) ? "binary" : "http";
    }
}
