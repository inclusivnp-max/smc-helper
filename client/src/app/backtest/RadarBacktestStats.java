package app.backtest;

import app.common.ProjectPaths;
import app.model.AnalysisResult;
import app.model.TradePlan;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class RadarBacktestStats {
    private static final long CACHE_TTL_MS = 30_000L;
    private static volatile long loadedAt = 0L;
    private static volatile List<Row> rows = List.of();

    // Below how many trades a raw win rate / expectancy is trusted for gating decisions.
    // With fewer samples we fall back to the confidence-derived heuristic winrate instead of
    // letting a lucky (or unlucky) handful of trades pass as "proven edge."
    private static final int CALIBRATION_MIN_TRADES = 20;
    // Empirical-Bayes-style shrinkage: pulls expectancy toward 0 when the sample is thin, so an
    // 8-trade streak can't produce the same confidence as an 80-trade one. 30 trades of "prior
    // strength" means a 10-trade sample is still shrunk by ~75% toward neutral.
    private static final int EXPECTANCY_SHRINKAGE_PRIOR_TRADES = 30;

    private RadarBacktestStats() {
    }

    /**
     * Wilson score interval lower bound (95%) for a win rate — a statistically honest
     * "how good is this really, worst-plausible-case" estimate instead of the raw point
     * estimate, which is what a tiny sample's win rate really is: noise with a label on it.
     */
    static double wilsonLowerBound95(double winratePct, int trades) {
        if (trades <= 0) return 0.0;
        double p = Math.max(0.0, Math.min(1.0, winratePct / 100.0));
        double n = trades;
        double z = 1.959963985; // 95% two-sided normal critical value
        double denom = 1.0 + z * z / n;
        double center = p + z * z / (2.0 * n);
        double margin = z * Math.sqrt((p * (1.0 - p) + z * z / (4.0 * n)) / n);
        double lowerFraction = (center - margin) / denom;
        return Math.max(0.0, Math.min(100.0, lowerFraction * 100.0));
    }

    /**
     * Calibrated win-rate probability (0..1) for use in EV math (see TradePlan.expectedValueR),
     * blending real historical evidence when there's enough of it and otherwise falling back to
     * the old confidence-derived heuristic. Replaces treating `confidence` as if it were itself
     * a calibrated probability.
     */
    public static double calibratedWinRate01(AnalysisResult result, TradePlan plan) {
        Snapshot snapshot = evaluate(result, plan);
        int confidence = plan == null ? 50 : plan.confidence;
        return calibratedWinRate01(snapshot.trades(), snapshot.winrate(), confidence);
    }

    public static double calibratedWinRate01(int trades, double winratePct, int confidence) {
        double fallback = Math.min(0.75, Math.max(0.25, confidence / 100.0 * 0.80));
        if (trades < CALIBRATION_MIN_TRADES) return fallback;
        double wilsonPct = wilsonLowerBound95(winratePct, trades);
        return Math.max(0.05, Math.min(0.95, wilsonPct / 100.0));
    }

    public static Snapshot evaluate(AnalysisResult result, TradePlan plan) {
        if (result == null || plan == null) return Snapshot.empty();
        List<Row> source = cachedRows();
        if (source.isEmpty()) return Snapshot.empty();

        String symbol = normalize(result.symbol);
        String ltf = normalize(result.ltfInterval);
        String direction = plan.longSide ? "LONG" : "SHORT";
        String setupType = normalize(plan.setupType);
        String zoneType = normalize(plan.zone == null ? "" : plan.zone.type);
        int scoreBand = scoreBand(Math.max(plan.confidence, result.smcScore));

        List<ScoredRow> matches = new ArrayList<>();
        for (Row row : source) {
            if (!row.belongsToCrypto()) continue;
            int similarity = similarity(row, symbol, ltf, direction, setupType, zoneType, scoreBand);
            if (similarity >= 55) matches.add(new ScoredRow(row, similarity));
        }
        if (matches.isEmpty()) return Snapshot.empty();
        matches.sort(Comparator.comparingInt((ScoredRow r) -> r.similarity).reversed());

        int limit = Math.min(250, matches.size());
        int trades = 0;
        int wins = 0;
        double netR = 0.0;
        double grossProfit = 0.0;
        double grossLoss = 0.0;
        int exact = 0;
        int similaritySum = 0;
        for (int i = 0; i < limit; i++) {
            ScoredRow scored = matches.get(i);
            Row row = scored.row;
            trades++;
            similaritySum += scored.similarity;
            if (scored.similarity >= 85) exact++;
            netR += row.resultR;
            if (row.resultR > 0.05) {
                wins++;
                grossProfit += row.resultR;
            } else if (row.resultR < -0.05) {
                grossLoss += Math.abs(row.resultR);
            }
        }
        if (trades == 0) return Snapshot.empty();
        double winrate = wins * 100.0 / trades;
        double expectancy = netR / trades;
        double profitFactor = grossLoss <= 0.0 ? (grossProfit > 0.0 ? 99.0 : 0.0) : grossProfit / grossLoss;
        double avgSimilarity = similaritySum * 1.0 / trades;
        return new Snapshot(trades, exact, winrate, expectancy, profitFactor, avgSimilarity);
    }

    private static int similarity(Row row, String symbol, String ltf, String direction,
                                  String setupType, String zoneType, int scoreBand) {
        int score = 0;
        if (row.symbol.equals(symbol)) score += 24;
        if (row.ltf.equals(ltf)) score += 16;
        if (row.direction.equals(direction)) score += 20;
        if (!setupType.isBlank() && row.setupType.equals(setupType)) score += 18;
        if (!zoneType.isBlank() && row.zoneType.equals(zoneType)) score += 12;
        if (Math.abs(row.scoreBand - scoreBand) <= 1) score += 10;
        if (row.entryConfirmed) score += 5;
        if (row.sweepConfirmed) score += 5;
        return Math.min(score, 100);
    }

    private static List<Row> cachedRows() {
        long now = System.currentTimeMillis();
        List<Row> current = rows;
        if (now - loadedAt < CACHE_TTL_MS) return current;
        synchronized (RadarBacktestStats.class) {
            if (now - loadedAt < CACHE_TTL_MS) return rows;
            rows = loadRows();
            loadedAt = now;
            return rows;
        }
    }

    private static List<Row> loadRows() {
        Path dir = ProjectPaths.backtestsDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<Row> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted(Comparator.comparingLong(RadarBacktestStats::lastModified).reversed())
                    .limit(80)
                    .toList();
            for (Path file : files) loadFile(file, result);
        } catch (Exception ignored) {
        }
        return List.copyOf(result);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static void loadFile(Path file, List<Row> out) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || header.isBlank()) return;
            List<String> columns = splitCsv(header);
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                index.put(columns.get(i).trim().toLowerCase(Locale.ROOT), i);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cells = splitCsv(line);
                Row row = Row.from(cells, index);
                if (row != null) out.add(row);
            }
        } catch (Exception ignored) {
        }
    }

    private static List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String cell(List<String> cells, Map<String, Integer> index, String name) {
        Integer i = index.get(name);
        if (i == null || i < 0 || i >= cells.size()) return "";
        return cells.get(i);
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text == null ? "" : text.trim());
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private static int scoreBand(int score) {
        if (score >= 90) return 9;
        if (score >= 80) return 8;
        if (score >= 70) return 7;
        if (score >= 60) return 6;
        return 5;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ScoredRow(Row row, int similarity) {
    }

    private static final class Row {
        final String symbol;
        final String market;
        final String ltf;
        final String direction;
        final String setupType;
        final String zoneType;
        final boolean sweepConfirmed;
        final boolean entryConfirmed;
        final int scoreBand;
        final double resultR;

        private Row(String symbol, String market, String ltf, String direction, String setupType,
                    String zoneType, boolean sweepConfirmed, boolean entryConfirmed,
                    int scoreBand, double resultR) {
            this.symbol = symbol;
            this.market = market;
            this.ltf = ltf;
            this.direction = direction;
            this.setupType = setupType;
            this.zoneType = zoneType;
            this.sweepConfirmed = sweepConfirmed;
            this.entryConfirmed = entryConfirmed;
            this.scoreBand = scoreBand;
            this.resultR = resultR;
        }

        static Row from(List<String> cells, Map<String, Integer> index) {
            String symbol = normalize(cell(cells, index, "symbol"));
            if (symbol.isBlank()) return null;
            String outcome = normalize(cell(cells, index, "outcome"));
            if ("MISSED".equals(outcome)) return null;
            double resultR = number(cell(cells, index, "result_r"));
            int smc = (int) Math.round(number(cell(cells, index, "smc_score")));
            return new Row(
                    symbol,
                    normalize(cell(cells, index, "market")),
                    normalize(cell(cells, index, "ltf_interval")),
                    normalize(cell(cells, index, "direction")),
                    normalize(cell(cells, index, "setup_type")),
                    normalize(cell(cells, index, "zone_type")),
                    Boolean.parseBoolean(cell(cells, index, "sweep_confirmed")),
                    Boolean.parseBoolean(cell(cells, index, "entry_confirmed")),
                    scoreBand(smc),
                    resultR
            );
        }

        boolean belongsToCrypto() {
            return symbol.endsWith("USDT");
        }
    }

    public record Snapshot(int trades, int exactMatches, double winrate, double expectancyR,
                           double profitFactor, double avgSimilarity) {
        public static Snapshot empty() {
            return new Snapshot(0, 0, 0.0, 0.0, 0.0, 0.0);
        }

        public boolean hasEnough(int minTrades) {
            return trades >= Math.max(1, minTrades);
        }

        /** Wilson-lower-bound win rate — the pessimistic, sample-size-aware read of `winrate`. */
        public double winrateLowerBound95() {
            return wilsonLowerBound95(winrate, trades);
        }

        /** Expectancy shrunk toward 0 by sample size, so a thin sample can't claim a big edge. */
        public double shrunkExpectancy() {
            return trades <= 0 ? 0.0 : expectancyR * trades / (trades + EXPECTANCY_SHRINKAGE_PRIOR_TRADES);
        }

        public boolean positive(double minExpectancyR) {
            return trades > 0 && shrunkExpectancy() >= minExpectancyR;
        }

        public String summary() {
            if (trades <= 0) return "stats: no backtest evidence";
            return String.format(Locale.US, "stats: %d trades · WR %.0f%% · Exp %.2fR · PF %.2f",
                    trades, winrate, expectancyR, profitFactor);
        }
    }
}
