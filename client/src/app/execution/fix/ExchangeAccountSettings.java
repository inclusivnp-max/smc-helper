package app.execution.fix;

import app.common.ProjectPaths;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;

public class ExchangeAccountSettings {
    private static final Path PATH = ProjectPaths.root().resolve("settings").resolve("account.properties");
    private static final String DPAPI_PREFIX = "DPAPI1:";
    private static ExchangeAccountSettings session = loadFromDisk();

    public enum Exchange {
        BINANCE("Binance Futures"),
        BYBIT("Bybit"),
        BINGX("BingX");

        public final String label;

        Exchange(String label) {
            this.label = label;
        }

        public static Exchange fromLabel(String label) {
            if (label == null) return BINANCE;
            String text = label.toLowerCase();
            if (text.contains("bybit")) return BYBIT;
            if (text.contains("bing")) return BINGX;
            return BINANCE;
        }
    }

    public enum Mode {
        PAPER("Paper (local)"),
        TESTNET("Testnet / Demo"),
        LIVE("Live");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public static Mode fromLabel(String label) {
            if (label == null) return PAPER;
            String text = label.toLowerCase();
            if (text.contains("live")) return LIVE;
            if (text.contains("test")) return TESTNET;
            return PAPER;
        }
    }

    public Exchange exchange = Exchange.BINANCE;
    public Mode mode = Mode.PAPER;
    public String profileName = "";
    public String apiKey = "";
    public String apiSecret = "";
    public String passphrase = "";
    public boolean rememberSecrets;
    public boolean liveTradingEnabled;
    public long updatedAt;

    public static synchronized ExchangeAccountSettings current() {
        return session.copy();
    }

    public static synchronized void saveSession(ExchangeAccountSettings settings, boolean persist) {
        session = settings == null ? new ExchangeAccountSettings() : settings.copy();
        session.updatedAt = System.currentTimeMillis();
        if (persist) session.saveToDisk();
    }

    public boolean configured() {
        if (mode == Mode.PAPER) return true;
        return !apiKey.isBlank() && !apiSecret.isBlank();
    }

    public boolean liveCapable() {
        return configured() && mode != Mode.PAPER;
    }

    public boolean liveTradingAllowed() {
        return mode != Mode.LIVE || liveTradingEnabled || Boolean.getBoolean("smc.liveTrading.enabled");
    }

    public String summary() {
        if (mode == Mode.PAPER) return exchange.label + " · Paper local";
        String keyState = apiKey.isBlank() ? "no key" : "key " + mask(apiKey);
        String adapter = exchange == Exchange.BINGX && mode == Mode.TESTNET ? " · no public testnet" : "";
        return exchange.label + " · " + mode.label + " · " + keyState + adapter;
    }

    public ExchangeAccountSettings copy() {
        ExchangeAccountSettings copy = new ExchangeAccountSettings();
        copy.exchange = exchange;
        copy.mode = mode;
        copy.profileName = profileName;
        copy.apiKey = apiKey;
        copy.apiSecret = apiSecret;
        copy.passphrase = passphrase;
        copy.rememberSecrets = rememberSecrets;
        copy.liveTradingEnabled = liveTradingEnabled;
        copy.updatedAt = updatedAt;
        return copy;
    }

    private static ExchangeAccountSettings loadFromDisk() {
        ExchangeAccountSettings settings = new ExchangeAccountSettings();
        if (!Files.exists(PATH)) return settings;
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            props.load(reader);
            settings.exchange = Exchange.valueOf(props.getProperty("exchange", Exchange.BINANCE.name()));
            settings.mode = Mode.valueOf(props.getProperty("mode", Mode.PAPER.name()));
            settings.profileName = props.getProperty("profileName", "");
            settings.apiKey = props.getProperty("apiKey", "");
            settings.apiSecret = firstNonBlank(
                    unprotectSecret(props.getProperty("apiSecretProtected", "")),
                    props.getProperty("apiSecret", ""));
            settings.passphrase = firstNonBlank(
                    unprotectSecret(props.getProperty("passphraseProtected", "")),
                    props.getProperty("passphrase", ""));
            settings.rememberSecrets = Boolean.parseBoolean(props.getProperty("rememberSecrets", "false"));
            settings.liveTradingEnabled = Boolean.parseBoolean(props.getProperty("liveTradingEnabled", "false"));
            settings.updatedAt = Long.parseLong(props.getProperty("updatedAt", "0"));
        } catch (Exception ignored) {
        }
        return settings;
    }

    private void saveToDisk() {
        Properties props = new Properties();
        props.setProperty("exchange", exchange.name());
        props.setProperty("mode", mode.name());
        props.setProperty("profileName", profileName == null ? "" : profileName);
        props.setProperty("apiKey", apiKey == null ? "" : apiKey);
        props.setProperty("rememberSecrets", Boolean.toString(rememberSecrets));
        props.setProperty("liveTradingEnabled", Boolean.toString(liveTradingEnabled));
        props.setProperty("updatedAt", Long.toString(updatedAt));
        if (rememberSecrets) {
            props.setProperty("apiSecretProtected", protectSecret(apiSecret));
            props.setProperty("passphraseProtected", protectSecret(passphrase));
            props.setProperty("apiSecret", "");
            props.setProperty("passphrase", "");
        } else {
            props.setProperty("apiSecretProtected", "");
            props.setProperty("passphraseProtected", "");
            props.setProperty("apiSecret", "");
            props.setProperty("passphrase", "");
        }
        try {
            Files.createDirectories(PATH.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                props.store(writer, "SMC exchange account settings");
            }
        } catch (IOException ignored) {
        }
    }

    private static String mask(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= 8) return "****";
        return text.substring(0, 4) + "..." + text.substring(text.length() - 4);
    }

    private static String protectSecret(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank() || !isWindows()) return "";
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] encrypted = Crypt32Util.cryptProtectData(plain);
            return DPAPI_PREFIX + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ignored) {
            return "";
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static String unprotectSecret(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank() || !text.startsWith(DPAPI_PREFIX) || !isWindows()) return "";
        byte[] plain = new byte[0];
        try {
            byte[] encrypted = Base64.getDecoder().decode(text.substring(DPAPI_PREFIX.length()));
            plain = Crypt32Util.cryptUnprotectData(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback == null ? "" : fallback;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
