package app.license;

import app.common.Json;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class LicenseClient {
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;
    private final String transportError;

    public LicenseClient(LicenseSettings settings) {
        this(settings.serverUrl, settings.timeout, settings.publicKeySha256,
                settings.allowInsecureLocal, settings.allowInsecureRemote);
    }

    LicenseClient(URI serverUrl,
                  Duration timeout,
                  String publicKeySha256,
                  boolean allowInsecureLocal,
                  boolean allowInsecureRemote) {
        this.endpoint = serverUrl.resolve(serverUrl.getPath().endsWith("/")
                ? "api/license/check"
                : serverUrl.getPath() + "/api/license/check");
        this.timeout = timeout;
        this.transportError = LicenseTransportSecurity.validateServerUrl(serverUrl, allowInsecureLocal, allowInsecureRemote);
        this.http = buildHttpClient(timeout, publicKeySha256);
    }

    public LicenseCheckResult check(String hwid, char[] password) {
        if (!transportError.isBlank()) return LicenseCheckResult.denied(transportError);
        String passwordText = password == null ? "" : new String(password);
        if (passwordText.isBlank()) return LicenseCheckResult.denied("Введите пароль.");
        try {
            List<String> hwids = Hwid.all();
            String payload = "{"
                    + "\"hwid\":\"" + escape(Hwid.normalize(hwid)) + "\","
                    + "\"hwids\":" + jsonArray(hwids) + ","
                    + "\"password\":\"" + escape(passwordText) + "\","
                    + "\"app\":\"smc-chart\","
                    + "\"version\":\"1.0.0\""
                    + "}";
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(response.statusCode(), response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return LicenseCheckResult.denied("Проверка лицензии прервана.");
        } catch (Exception ex) {
            return LicenseCheckResult.denied("Не удалось проверить лицензию: " + errorText(ex));
        }
    }

    private HttpClient buildHttpClient(Duration timeout, String publicKeySha256) {
        try {
            HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout);
            SSLContext context = LicenseTransportSecurity.pinnedSslContext(publicKeySha256);
            if (context != null) builder.sslContext(context);
            return builder.build();
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось настроить TLS-защиту лицензии: " + ex.getMessage(), ex);
        }
    }

    private LicenseCheckResult parseResponse(int status, String body) throws IOException {
        Map<String, Object> object = Json.object(Json.parse(body == null || body.isBlank() ? "{}" : body));
        boolean ok = Json.bool(object.get("ok"), false);
        String message = Json.string(object.get("message"), status == 200 ? "" : "HTTP " + status);
        if (status != 200) return LicenseCheckResult.denied(message + " (HTTP " + status + ")");
        if (!ok) return LicenseCheckResult.denied(message);
        return LicenseCheckResult.allowed(
                message,
                Json.string(object.get("expiresAt"), ""),
                Json.integer(object.get("secondsLeft"), 0)
        );
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(Hwid.normalize(values.get(i)))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String errorText(Exception ex) {
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) return message;
        Throwable cause = ex.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) return cause.getMessage();
        return ex.getClass().getSimpleName();
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
