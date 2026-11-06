package app.execution.fix;

import app.common.ProjectPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class RiskSettings {
    private static final Path PATH = ProjectPaths.root().resolve("settings").resolve("risk.properties");

    public double maxOrderNotional = 10_000.0;
    public double maxRiskNotional = 120.0;
    public int minSmcScore = 80;
    public boolean requireConfirmedEntry = true;
    public double maxDailyLossNotional = 300.0;
    public double maxDailyLossPct = 3.0;
    public int maxTradesPerDay = 12;
    public int maxConsecutiveLosses = 3;
    public int maxOpenPositions = 3;
    public int duplicateOrderLockSeconds = 45;
    public double maxSingleOrderQuantity = 500.0;
    public double maxEntryDeviationPct = 0.85;
    public double maxStopDistancePct = 2.75;
    public int reconcileSeconds = 60;
    public boolean autoCancelOrphans = true;
    public boolean autoProtectiveOrders = true;

    public static RiskSettings load() {
        RiskSettings settings = new RiskSettings();
        if (!Files.exists(PATH)) return settings;
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            props.load(reader);
            settings.maxOrderNotional = doubleValue(props, "maxOrderNotional", settings.maxOrderNotional, 0.0, 1_000_000_000.0);
            settings.maxRiskNotional = doubleValue(props, "maxRiskNotional", settings.maxRiskNotional, 0.0, 1_000_000_000.0);
            settings.minSmcScore = intValue(props, "minSmcScore", settings.minSmcScore, 0, 100);
            settings.requireConfirmedEntry = boolValue(props, "requireConfirmedEntry", settings.requireConfirmedEntry);
            settings.maxDailyLossNotional = doubleValue(props, "maxDailyLossNotional", settings.maxDailyLossNotional, 0.0, 1_000_000_000.0);
            settings.maxDailyLossPct = doubleValue(props, "maxDailyLossPct", settings.maxDailyLossPct, 0.0, 100.0);
            settings.maxTradesPerDay = intValue(props, "maxTradesPerDay", settings.maxTradesPerDay, 0, 10_000);
            settings.maxConsecutiveLosses = intValue(props, "maxConsecutiveLosses", settings.maxConsecutiveLosses, 0, 10_000);
            settings.maxOpenPositions = intValue(props, "maxOpenPositions", settings.maxOpenPositions, 0, 10_000);
            settings.duplicateOrderLockSeconds = intValue(props, "duplicateOrderLockSeconds", settings.duplicateOrderLockSeconds, 0, 3600);
            settings.maxSingleOrderQuantity = doubleValue(props, "maxSingleOrderQuantity", settings.maxSingleOrderQuantity, 0.0, 1_000_000_000.0);
            settings.maxEntryDeviationPct = doubleValue(props, "maxEntryDeviationPct", settings.maxEntryDeviationPct, 0.0, 100.0);
            settings.maxStopDistancePct = doubleValue(props, "maxStopDistancePct", settings.maxStopDistancePct, 0.0, 100.0);
            settings.reconcileSeconds = intValue(props, "reconcileSeconds", settings.reconcileSeconds, 5, 3600);
            settings.autoCancelOrphans = boolValue(props, "autoCancelOrphans", settings.autoCancelOrphans);
            settings.autoProtectiveOrders = boolValue(props, "autoProtectiveOrders", settings.autoProtectiveOrders);
        } catch (IOException ignored) {
        }
        return settings;
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("maxOrderNotional", Double.toString(maxOrderNotional));
        props.setProperty("maxRiskNotional", Double.toString(maxRiskNotional));
        props.setProperty("minSmcScore", Integer.toString(minSmcScore));
        props.setProperty("requireConfirmedEntry", Boolean.toString(requireConfirmedEntry));
        props.setProperty("maxDailyLossNotional", Double.toString(maxDailyLossNotional));
        props.setProperty("maxDailyLossPct", Double.toString(maxDailyLossPct));
        props.setProperty("maxTradesPerDay", Integer.toString(maxTradesPerDay));
        props.setProperty("maxConsecutiveLosses", Integer.toString(maxConsecutiveLosses));
        props.setProperty("maxOpenPositions", Integer.toString(maxOpenPositions));
        props.setProperty("duplicateOrderLockSeconds", Integer.toString(duplicateOrderLockSeconds));
        props.setProperty("maxSingleOrderQuantity", Double.toString(maxSingleOrderQuantity));
        props.setProperty("maxEntryDeviationPct", Double.toString(maxEntryDeviationPct));
        props.setProperty("maxStopDistancePct", Double.toString(maxStopDistancePct));
        props.setProperty("reconcileSeconds", Integer.toString(reconcileSeconds));
        props.setProperty("autoCancelOrphans", Boolean.toString(autoCancelOrphans));
        props.setProperty("autoProtectiveOrders", Boolean.toString(autoProtectiveOrders));
        try {
            Files.createDirectories(PATH.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                props.store(writer, "SMC execution risk settings");
            }
        } catch (IOException ignored) {
        }
    }

    public RiskGate toRiskGate() {
        return new RiskGate(maxOrderNotional, maxRiskNotional, minSmcScore, requireConfirmedEntry,
                maxDailyLossNotional, maxSingleOrderQuantity, maxEntryDeviationPct, maxStopDistancePct,
                maxDailyLossPct, maxTradesPerDay, maxConsecutiveLosses, maxOpenPositions,
                duplicateOrderLockSeconds);
    }

    public String summary() {
        return "maxOrder=" + maxOrderNotional
                + " maxRisk=" + maxRiskNotional
                + " minSMC=" + minSmcScore
                + " dailyLoss=" + maxDailyLossNotional + "/" + maxDailyLossPct + "%"
                + " tradesDay=" + maxTradesPerDay
                + " lossStreak=" + maxConsecutiveLosses
                + " maxPos=" + maxOpenPositions
                + " autoProtective=" + autoProtectiveOrders
                + " reconcile=" + reconcileSeconds + "s";
    }

    private static double doubleValue(Properties props, String key, double fallback, double min, double max) {
        try {
            return clamp(Double.parseDouble(props.getProperty(key, Double.toString(fallback))), min, max);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int intValue(Properties props, String key, int fallback, int min, int max) {
        try {
            return (int) clamp(Integer.parseInt(props.getProperty(key, Integer.toString(fallback))), min, max);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean boolValue(Properties props, String key, boolean fallback) {
        String value = props.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
