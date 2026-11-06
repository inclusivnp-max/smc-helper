package app.backtest;

import app.common.ProjectPaths;
import app.logic.BinanceClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BacktestEvidenceStore {
    private static final Object LOCK = new Object();
    private static final Path PATH = ProjectPaths.backtestEvidenceDir().resolve("eta_stats.csv");

    private BacktestEvidenceStore() {
    }

    public static Path path() {
        return PATH;
    }

    public static void record(List<EtaSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        synchronized (LOCK) {
            Map<String, Stats> stats = loadStats();
            for (EtaSample sample : samples) {
                if (sample == null || sample.bars <= 0) continue;
                stats.computeIfAbsent(sample.key(), k -> new Stats(sample)).add(sample.bars);
            }
            writeStats(stats);
        }
    }

    public static Long estimateMillis(String symbol,
                                      BinanceClient.Market market,
                                      boolean longSide,
                                      String interval,
                                      String level) {
        long intervalMs = BinanceClient.intervalMillis(interval);
        if (intervalMs <= 0L) intervalMs = 60_000L;
        String key = key(symbol, market, longSide, interval, level);
        synchronized (LOCK) {
            Stats stats = loadStats().get(key);
            if (stats == null || stats.count < 3 || stats.avgBars <= 0.0) return null;
            return Math.max(intervalMs, Math.round(stats.avgBars * intervalMs));
        }
    }

    private static Map<String, Stats> loadStats() {
        Map<String, Stats> stats = new HashMap<>();
        if (!Files.exists(PATH)) return stats;
        try (BufferedReader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("symbol,")) continue;
                String[] p = line.split(",", -1);
                if (p.length < 7) continue;
                Stats s = new Stats(p[0], p[1], Boolean.parseBoolean(p[2]), p[3], p[4],
                        Integer.parseInt(p[5]), Double.parseDouble(p[6]));
                stats.put(s.key(), s);
            }
        } catch (Exception ignored) {
            return new HashMap<>();
        }
        return stats;
    }

    private static void writeStats(Map<String, Stats> stats) {
        try {
            Files.createDirectories(PATH.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("symbol,market,long_side,interval,level,count,avg_bars");
                writer.newLine();
                for (Stats s : stats.values()) {
                    writer.write(s.symbol); writer.write(',');
                    writer.write(s.market); writer.write(',');
                    writer.write(Boolean.toString(s.longSide)); writer.write(',');
                    writer.write(s.interval); writer.write(',');
                    writer.write(s.level); writer.write(',');
                    writer.write(Integer.toString(s.count)); writer.write(',');
                    writer.write(Double.toString(s.avgBars));
                    writer.newLine();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String key(String symbol, BinanceClient.Market market, boolean longSide, String interval, String level) {
        String safeSymbol = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT).trim();
        String safeMarket = market == null ? "" : market.label;
        String safeInterval = interval == null || interval.isBlank() ? "?" : interval.trim().toLowerCase(Locale.ROOT);
        String safeLevel = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        return safeSymbol + "|" + safeMarket + "|" + longSide + "|" + safeInterval + "|" + safeLevel;
    }

    public static class EtaSample {
        public final String symbol;
        public final String market;
        public final boolean longSide;
        public final String interval;
        public final String level;
        public final long bars;

        public EtaSample(String symbol, String market, boolean longSide, String interval, String level, long bars) {
            this.symbol = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT).trim();
            this.market = market == null ? "" : market;
            this.longSide = longSide;
            this.interval = interval == null || interval.isBlank() ? "?" : interval.trim().toLowerCase(Locale.ROOT);
            this.level = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
            this.bars = bars;
        }

        String key() {
            return symbol + "|" + market + "|" + longSide + "|" + interval + "|" + level;
        }
    }

    private static class Stats {
        final String symbol;
        final String market;
        final boolean longSide;
        final String interval;
        final String level;
        int count;
        double avgBars;

        Stats(EtaSample sample) {
            this(sample.symbol, sample.market, sample.longSide, sample.interval, sample.level, 0, 0.0);
        }

        Stats(String symbol, String market, boolean longSide, String interval, String level, int count, double avgBars) {
            this.symbol = symbol;
            this.market = market;
            this.longSide = longSide;
            this.interval = interval;
            this.level = level;
            this.count = Math.max(0, count);
            this.avgBars = Math.max(0.0, avgBars);
        }

        void add(long bars) {
            avgBars = count == 0 ? bars : (avgBars * count + bars) / (count + 1);
            count++;
        }

        String key() {
            return symbol + "|" + market + "|" + longSide + "|" + interval + "|" + level;
        }
    }
}
