package app.backtest;

import app.common.ProjectPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class BacktestIntelligence {
    private static final long CACHE_TTL_MS = 15_000L;
    private static final Object CACHE_LOCK = new Object();
    private static Snapshot cachedSnapshot;
    private static long cachedAtMs;
    private static long cachedFingerprint;

    private BacktestIntelligence() {
    }

    public static Snapshot load() {
        return load(false);
    }

    public static Snapshot loadFresh() {
        return load(true);
    }

    private static Snapshot load(boolean force) {
        Path directory = ProjectPaths.backtestsDir();
        long fingerprint = fingerprint(directory);
        long now = System.currentTimeMillis();
        synchronized (CACHE_LOCK) {
            if (!force
                    && cachedSnapshot != null
                    && cachedFingerprint == fingerprint
                    && now - cachedAtMs <= CACHE_TTL_MS) {
                return cachedSnapshot;
            }
        }

        Snapshot snapshot = readSnapshot(directory);
        synchronized (CACHE_LOCK) {
            cachedSnapshot = snapshot;
            cachedFingerprint = fingerprint;
            cachedAtMs = now;
        }
        return snapshot;
    }

    private static Snapshot readSnapshot(Path directory) {
        Snapshot snapshot = new Snapshot(directory);
        if (!Files.isDirectory(directory)) return snapshot;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted()
                    .forEach(path -> readFile(snapshot, path));
        } catch (IOException ignored) {
        }
        snapshot.finish();
        return snapshot;
    }

    private static long fingerprint(Path directory) {
        if (!Files.isDirectory(directory)) return 0L;
        long hash = 17L;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted()
                    .toList()) {
                long modified = Files.getLastModifiedTime(path).toMillis();
                long size = Files.size(path);
                hash = hash * 31L + path.getFileName().toString().hashCode();
                hash = hash * 31L + modified;
                hash = hash * 31L + size;
            }
        } catch (IOException ignored) {
            return System.nanoTime();
        }
        return hash;
    }

    private static void readFile(Snapshot snapshot, Path path) {
        snapshot.files++;
        try {
            snapshot.latestModified = Math.max(snapshot.latestModified, Files.getLastModifiedTime(path).toMillis());
        } catch (IOException ignored) {
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return;
            Map<String, Integer> columns = columns(parseCsvLine(header));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                TradeSample sample = TradeSample.from(columns, values);
                if (sample == null) {
                    snapshot.skippedRows++;
                    continue;
                }
                snapshot.add(sample);
            }
        } catch (Exception ex) {
            snapshot.failedFiles++;
        }
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        return columns;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        out.add(cell.toString());
        return out;
    }

    public static final class Snapshot {
        public final Path directory;
        public int files;
        public int failedFiles;
        public int trades;
        public int skippedRows;
        public long latestModified;
        public final Bucket all = new Bucket("Все сделки");
        public final Map<String, Bucket> symbols = new LinkedHashMap<>();
        public final Map<String, Bucket> intervals = new LinkedHashMap<>();
        public final Map<String, Bucket> directions = new LinkedHashMap<>();
        public final Map<String, Bucket> setups = new LinkedHashMap<>();
        public final Map<String, Bucket> targetModels = new LinkedHashMap<>();
        public final Map<String, Bucket> regimes = new LinkedHashMap<>();
        public final Map<String, Bucket> deviations = new LinkedHashMap<>();
        public final Map<String, Bucket> imbalances = new LinkedHashMap<>();
        public final Map<String, Bucket> eventRisk = new LinkedHashMap<>();
        public final Map<String, Bucket> zones = new LinkedHashMap<>();
        public final Map<String, Bucket> sessions = new LinkedHashMap<>();
        public final Map<String, Bucket> riskReward = new LinkedHashMap<>();
        public final Map<String, Bucket> filters = new LinkedHashMap<>();
        private final Map<String, Bucket> context = new HashMap<>();

        Snapshot(Path directory) {
            this.directory = directory;
        }

        void add(TradeSample s) {
            trades++;
            all.add(s);
            add(symbols, s.symbol, s);
            add(intervals, s.ltfInterval, s);
            add(directions, s.direction, s);
            add(setups, s.setupType, s);
            add(targetModels, s.targetModel, s);
            add(regimes, s.marketRegime, s);
            add(deviations, s.deviationType, s);
            add(imbalances, s.imbalanceDirection, s);
            add(eventRisk, s.eventRiskBlocked ? "event risk blocked" : eventRiskBand(s.eventRiskScore), s);
            add(zones, s.zoneType + " · " + s.zoneQuality, s);
            add(sessions, s.session + " · " + s.phase, s);
            add(riskReward, rrBand(s.rr3), s);
            add(context, contextKey(s.symbol, s.ltfInterval, s.direction), s);
            addFilterStats(s);
        }

        private void addFilterStats(TradeSample s) {
            add(filters, "Killzone: " + yesNo(s.killzone), s);
            add(filters, "Sweep confirmed: " + yesNo(s.sweepConfirmed), s);
            add(filters, "Entry confirmed: " + yesNo(s.entryConfirmed), s);
            add(filters, "HTF displacement: " + yesNo(s.htfDisplacementAligned), s);
            add(filters, "Deviation returned: " + yesNo(s.deviationReturnedInside), s);
            add(filters, "Deviation displacement: " + yesNo(s.deviationDisplacement), s);
            add(filters, "Deviation score " + band(s.deviationScore), s);
            add(filters, "Imbalance score " + band(s.imbalanceScore), s);
            add(filters, "Event risk blocked: " + yesNo(s.eventRiskBlocked), s);
            add(filters, "Event risk " + eventRiskBand(s.eventRiskScore), s);
            add(filters, "SMC " + band(s.smcScore), s);
            add(filters, "Order Flow " + band(s.orderFlowScore), s);
            add(filters, "Zone score " + band(s.zoneScore), s);
            add(filters, "Displacement " + band(s.displacementScore), s);
            add(filters, "Liquidity " + band(s.liquidityScore), s);
            add(filters, "Volume " + band(s.volumeScore), s);
        }

        private void add(Map<String, Bucket> map, String key, TradeSample s) {
            String safe = key == null || key.isBlank() ? "unknown" : key.trim();
            map.computeIfAbsent(safe, Bucket::new).add(s);
        }

        void finish() {
            sort(symbols);
            sort(intervals);
            sort(directions);
            sort(setups);
            sort(targetModels);
            sort(regimes);
            sort(deviations);
            sort(imbalances);
            sort(eventRisk);
            sort(zones);
            sort(sessions);
            sort(riskReward);
            sort(filters);
        }

        private void sort(Map<String, Bucket> map) {
            List<Map.Entry<String, Bucket>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator
                    .<Map.Entry<String, Bucket>>comparingInt(e -> e.getValue().trades).reversed()
                    .thenComparing(e -> e.getKey().toLowerCase(Locale.ROOT)));
            map.clear();
            for (Map.Entry<String, Bucket> entry : entries) map.put(entry.getKey(), entry.getValue());
        }

        public Bucket context(String symbol, String interval, boolean longSide) {
            return context.get(contextKey(symbol, interval, longSide ? "LONG" : "SHORT"));
        }

        public List<Bucket> best(Map<String, Bucket> map, int limit, int minTrades) {
            return map.values().stream()
                    .filter(b -> b.trades >= minTrades)
                    .sorted(Comparator.comparingDouble(Bucket::expectancy).reversed()
                            .thenComparing(Comparator.comparingInt((Bucket b) -> b.trades).reversed()))
                    .limit(limit)
                    .toList();
        }

        public List<Bucket> worst(Map<String, Bucket> map, int limit, int minTrades) {
            return map.values().stream()
                    .filter(b -> b.trades >= minTrades)
                    .sorted(Comparator.comparingDouble(Bucket::expectancy)
                            .thenComparing(Comparator.comparingInt((Bucket b) -> b.trades).reversed()))
                    .limit(limit)
                    .toList();
        }
    }

    public static final class Bucket {
        public final String key;
        public int trades;
        public int wins;
        public int losses;
        public int breakeven;
        public double netR;
        public double grossProfitR;
        public double grossLossR;
        public double avgSmc;
        public double avgBarsTp1;
        public double avgBarsTp2;
        public double avgBarsTp3;
        public double avgBarsStop;
        private int smcCount;
        private int tp1Count;
        private int tp2Count;
        private int tp3Count;
        private int stopCount;

        Bucket(String key) {
            this.key = key;
        }

        void add(TradeSample s) {
            trades++;
            netR += s.resultR;
            if (s.resultR > 0.05) {
                wins++;
                grossProfitR += s.resultR;
            } else if (s.resultR < -0.05) {
                losses++;
                grossLossR += s.resultR;
            } else {
                breakeven++;
            }
            if (s.smcScore > 0) {
                avgSmc = (avgSmc * smcCount + s.smcScore) / (smcCount + 1);
                smcCount++;
            }
            if (s.barsToTp1 > 0) {
                avgBarsTp1 = (avgBarsTp1 * tp1Count + s.barsToTp1) / (tp1Count + 1);
                tp1Count++;
            }
            if (s.barsToTp2 > 0) {
                avgBarsTp2 = (avgBarsTp2 * tp2Count + s.barsToTp2) / (tp2Count + 1);
                tp2Count++;
            }
            if (s.barsToTp3 > 0) {
                avgBarsTp3 = (avgBarsTp3 * tp3Count + s.barsToTp3) / (tp3Count + 1);
                tp3Count++;
            }
            if (s.barsToStop > 0) {
                avgBarsStop = (avgBarsStop * stopCount + s.barsToStop) / (stopCount + 1);
                stopCount++;
            }
        }

        public double winrate() {
            return trades == 0 ? 0.0 : wins * 100.0 / trades;
        }

        public double expectancy() {
            return trades == 0 ? 0.0 : netR / trades;
        }

        public double profitFactor() {
            return grossLossR == 0.0 ? (grossProfitR > 0.0 ? 99.0 : 0.0) : grossProfitR / Math.abs(grossLossR);
        }
    }

    private static final class TradeSample {
        String symbol;
        String market;
        String ltfInterval;
        String direction;
        String setupType;
        String targetModel;
        String marketRegime;
        String deviationType;
        int deviationScore;
        boolean deviationReturnedInside;
        boolean deviationDisplacement;
        String imbalanceDirection;
        int imbalanceScore;
        int eventRiskScore;
        boolean eventRiskBlocked;
        String zoneType;
        String zoneQuality;
        String session;
        String phase;
        boolean killzone;
        boolean sweepConfirmed;
        boolean entryConfirmed;
        boolean htfDisplacementAligned;
        int orderFlowScore;
        int smcScore;
        int zoneScore;
        int displacementScore;
        int liquidityScore;
        int volumeScore;
        long barsToTp1;
        long barsToTp2;
        long barsToTp3;
        long barsToStop;
        double rr3;
        double resultR;

        static TradeSample from(Map<String, Integer> columns, List<String> row) {
            String symbol = text(columns, row, "symbol");
            String interval = text(columns, row, "ltf_interval");
            String direction = text(columns, row, "direction");
            if (symbol.isBlank() || interval.isBlank() || direction.isBlank()) return null;
            TradeSample s = new TradeSample();
            s.symbol = symbol.toUpperCase(Locale.ROOT);
            s.market = text(columns, row, "market");
            s.ltfInterval = interval;
            s.direction = direction.toUpperCase(Locale.ROOT);
            s.setupType = text(columns, row, "setup_type");
            s.targetModel = text(columns, row, "target_model");
            s.marketRegime = text(columns, row, "market_regime");
            s.deviationType = text(columns, row, "deviation_type");
            s.deviationScore = integer(columns, row, "deviation_score");
            s.deviationReturnedInside = bool(columns, row, "deviation_returned_inside");
            s.deviationDisplacement = bool(columns, row, "deviation_displacement");
            s.imbalanceDirection = text(columns, row, "imbalance_direction");
            s.imbalanceScore = integer(columns, row, "imbalance_score");
            s.eventRiskScore = integer(columns, row, "event_risk_score");
            s.eventRiskBlocked = bool(columns, row, "event_risk_blocked");
            s.zoneType = text(columns, row, "zone_type");
            s.zoneQuality = text(columns, row, "zone_quality");
            s.session = text(columns, row, "session");
            s.phase = text(columns, row, "phase");
            s.killzone = bool(columns, row, "killzone");
            s.sweepConfirmed = bool(columns, row, "sweep_confirmed");
            s.entryConfirmed = bool(columns, row, "entry_confirmed");
            s.htfDisplacementAligned = bool(columns, row, "htf_displacement_aligned");
            s.orderFlowScore = integer(columns, row, "order_flow_score");
            s.smcScore = integer(columns, row, "smc_score");
            s.zoneScore = integer(columns, row, "zone_score");
            s.displacementScore = integer(columns, row, "displacement_score");
            s.liquidityScore = integer(columns, row, "liquidity_score");
            s.volumeScore = integer(columns, row, "volume_score");
            s.barsToTp1 = lng(columns, row, "bars_to_tp1");
            s.barsToTp2 = lng(columns, row, "bars_to_tp2");
            s.barsToTp3 = lng(columns, row, "bars_to_tp3");
            s.barsToStop = lng(columns, row, "bars_to_stop");
            double entry = dbl(columns, row, "entry");
            double stop = dbl(columns, row, "stop");
            double tp3 = dbl(columns, row, "tp3");
            double risk = Math.abs(entry - stop);
            s.rr3 = risk <= 0.0 || !Double.isFinite(tp3) ? 0.0 : Math.abs(tp3 - entry) / risk;
            s.resultR = dbl(columns, row, "result_r");
            return Double.isFinite(s.resultR) ? s : null;
        }
    }

    private static String contextKey(String symbol, String interval, String direction) {
        return safe(symbol).toUpperCase(Locale.ROOT) + "|" + safe(interval).toLowerCase(Locale.ROOT)
                + "|" + safe(direction).toUpperCase(Locale.ROOT);
    }

    private static String text(Map<String, Integer> columns, List<String> row, String key) {
        Integer index = columns.get(key);
        if (index == null || index < 0 || index >= row.size()) return "";
        return row.get(index).trim();
    }

    private static boolean bool(Map<String, Integer> columns, List<String> row, String key) {
        return Boolean.parseBoolean(text(columns, row, key));
    }

    private static int integer(Map<String, Integer> columns, List<String> row, String key) {
        try {
            return Integer.parseInt(text(columns, row, key));
        } catch (Exception ex) {
            return 0;
        }
    }

    private static long lng(Map<String, Integer> columns, List<String> row, String key) {
        try {
            return Long.parseLong(text(columns, row, key));
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static double dbl(Map<String, Integer> columns, List<String> row, String key) {
        try {
            return Double.parseDouble(text(columns, row, key));
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    private static String yesNo(boolean value) {
        return value ? "да" : "нет";
    }

    private static String band(int score) {
        if (score >= 80) return "80+";
        if (score >= 70) return "70-79";
        if (score >= 60) return "60-69";
        if (score >= 50) return "50-59";
        return "<50";
    }

    private static String rrBand(double rr) {
        if (rr < 1.0) return "R:R < 1:1";
        if (rr < 1.5) return "R:R 1:1-1.5";
        if (rr < 2.4) return "R:R 1:1.5-2.4";
        if (rr <= 3.25) return "R:R 1:2.4-3.25";
        return "R:R > 1:3.25";
    }

    private static String eventRiskBand(int score) {
        if (score >= 75) return "75+ high";
        if (score >= 55) return "55-74 elevated";
        if (score >= 30) return "30-54 normal";
        return "<30 quiet";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
