package app.license;

import app.common.ProjectPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public final class LicenseSettings {
    private static final String DEFAULT_URL = "http://127.0.0.1:8787";
    private static final Path PATH = ProjectPaths.root().resolve("settings").resolve("license.properties");

    public final boolean enabled;
    public final URI serverUrl;
    public final Duration timeout;
    public final String publicKeySha256;
    public final boolean allowInsecureLocal;
    public final boolean allowInsecureRemote;
    public final Path path;

    private LicenseSettings(boolean enabled,
                            URI serverUrl,
                            Duration timeout,
                            String publicKeySha256,
                            boolean allowInsecureLocal,
                            boolean allowInsecureRemote,
                            Path path) {
        this.enabled = enabled;
        this.serverUrl = serverUrl;
        this.timeout = timeout;
        this.publicKeySha256 = publicKeySha256 == null ? "" : publicKeySha256.trim();
        this.allowInsecureLocal = allowInsecureLocal;
        this.allowInsecureRemote = allowInsecureRemote;
        this.path = path;
    }

    public static LicenseSettings load() {
        ensureDefaultFile();
        Properties props = new Properties();
        if (Files.exists(PATH)) {
            try (BufferedReader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException ignored) {
            }
        }

        boolean enabled = boolOverride("smc.license.enabled", "SMC_LICENSE_ENABLED",
                Boolean.parseBoolean(props.getProperty("enabled", "true")));
        String url = stringOverride("smc.license.url", "SMC_LICENSE_URL",
                props.getProperty("server.url", DEFAULT_URL));
        String publicKeySha256 = stringOverride("smc.license.publicKeySha256", "SMC_LICENSE_PUBLIC_KEY_SHA256",
                props.getProperty("server.publicKeySha256", ""));
        boolean allowInsecureLocal = boolOverride("smc.license.allowInsecureLocal", "SMC_LICENSE_ALLOW_INSECURE_LOCAL",
                Boolean.parseBoolean(props.getProperty("allow.insecure.local", "true")));
        boolean allowInsecureRemote = boolOverride("smc.license.allowInsecureRemote", "SMC_LICENSE_ALLOW_INSECURE_REMOTE",
                Boolean.parseBoolean(props.getProperty("allow.insecure.remote", "true")));
        int timeoutSeconds = intValue(props.getProperty("timeout.seconds", "8"), 2, 60);
        return new LicenseSettings(enabled, sanitizeUrl(url), Duration.ofSeconds(timeoutSeconds),
                publicKeySha256, allowInsecureLocal, allowInsecureRemote, PATH);
    }

    private static URI sanitizeUrl(String value) {
        String url = value == null || value.isBlank() ? DEFAULT_URL : value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return URI.create(url);
    }

    private static void ensureDefaultFile() {
        if (Files.exists(PATH)) return;
        Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("server.url", DEFAULT_URL);
        props.setProperty("server.publicKeySha256", "");
        props.setProperty("allow.insecure.local", "true");
        props.setProperty("allow.insecure.remote", "true");
        props.setProperty("timeout.seconds", "8");
        props.setProperty("note", "Default VPS license endpoint. HTTPS is recommended for production.");
        try {
            Files.createDirectories(PATH.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                props.store(writer, "SMC Chart license settings");
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean boolOverride(String property, String env, boolean fallback) {
        String prop = System.getProperty(property, "");
        if (!prop.isBlank()) return Boolean.parseBoolean(prop);
        String envValue = System.getenv(env);
        if (envValue != null && !envValue.isBlank()) return Boolean.parseBoolean(envValue);
        return fallback;
    }

    private static String stringOverride(String property, String env, String fallback) {
        String prop = System.getProperty(property, "");
        if (!prop.isBlank()) return prop;
        String envValue = System.getenv(env);
        if (envValue != null && !envValue.isBlank()) return envValue;
        return fallback;
    }

    private static int intValue(String text, int min, int max) {
        try {
            int value = Integer.parseInt(text == null ? "" : text.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ex) {
            return min;
        }
    }
}
