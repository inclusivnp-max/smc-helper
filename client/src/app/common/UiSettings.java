package app.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class UiSettings {
    private static final UiSettings INSTANCE = new UiSettings();

    private final Path path = ProjectPaths.root().resolve("settings").resolve("ui.properties");
    private final Properties properties = new Properties();

    private UiSettings() {
        load();
    }

    public static UiSettings get() {
        return INSTANCE;
    }

    public synchronized int getInt(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
            return clamp(value, min, max);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public synchronized void putInt(String key, int value) {
        properties.setProperty(key, Integer.toString(value));
        save();
    }

    public synchronized double getDouble(String key, double fallback, double min, double max) {
        try {
            double value = Double.parseDouble(properties.getProperty(key, Double.toString(fallback)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public synchronized void putDouble(String key, double value) {
        properties.setProperty(key, Double.toString(value));
        save();
    }

    public synchronized String getString(String key, String fallback) {
        return properties.getProperty(key, fallback == null ? "" : fallback);
    }

    public synchronized void putString(String key, String value) {
        properties.setProperty(key, value == null ? "" : value);
        save();
    }

    private void load() {
        if (!Files.exists(path)) return;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
        }
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "SMC Chart UI settings");
            }
        } catch (IOException ignored) {
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
