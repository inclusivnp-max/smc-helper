package app.datalake;

import app.logic.BinanceClient;
import app.model.Candle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DataQualityMonitor {
    public Report inspect(String symbol, BinanceClient.Market market, String interval, List<Candle> candles) {
        Report report = new Report(symbol, market == null ? "" : market.label, interval);
        if (candles == null || candles.isEmpty()) {
            report.warnings.add("no candles");
            return report;
        }
        long expectedStep = Math.max(60_000L, BinanceClient.intervalMillis(interval));
        List<Candle> sorted = new ArrayList<>(candles);
        sorted.sort(Comparator.comparingLong(c -> c.time));
        report.candles = sorted.size();
        report.firstTime = sorted.get(0).time;
        report.lastTime = sorted.get(sorted.size() - 1).time;
        report.staleMillis = Math.max(0L, System.currentTimeMillis() - report.lastTime);
        if (report.staleMillis > expectedStep * 3L) {
            report.warnings.add("stale candles " + report.staleMillis + "ms");
        }

        long previous = Long.MIN_VALUE;
        for (Candle candle : sorted) {
            if (previous != Long.MIN_VALUE) {
                long delta = candle.time - previous;
                if (delta == 0L) report.duplicates++;
                if (delta > expectedStep * 3L / 2L) report.gaps++;
                if (delta < 0L) report.outOfOrder++;
            }
            previous = candle.time;
        }

        report.tickPartitionAvailable = LocalDataLake.hasAggTradePartition(symbol,
                market == null ? "" : market.name(), report.firstTime, report.lastTime);
        if (!report.tickPartitionAvailable) report.warnings.add("no aggTrades partition in Data Lake");
        persist(report);
        return report;
    }

    private void persist(Report report) {
        try {
            Files.createDirectories(app.common.ProjectPaths.dataQualityDir());
            Path path = app.common.ProjectPaths.dataQualityDir().resolve("last-quality-report.txt");
            Files.writeString(path, String.join(System.lineSeparator(), report.lines()),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static class Report {
        public final String symbol;
        public final String market;
        public final String interval;
        public int candles;
        public long firstTime;
        public long lastTime;
        public long staleMillis;
        public int gaps;
        public int duplicates;
        public int outOfOrder;
        public boolean tickPartitionAvailable;
        public final List<String> warnings = new ArrayList<>();

        Report(String symbol, String market, String interval) {
            this.symbol = symbol == null ? "" : symbol;
            this.market = market == null ? "" : market;
            this.interval = interval == null ? "" : interval;
        }

        public boolean healthy() {
            return candles > 0 && gaps == 0 && duplicates == 0 && outOfOrder == 0 && staleMillis < 10 * 60_000L;
        }

        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("Data quality " + symbol + " " + market + " " + interval
                    + " / healthy=" + healthy());
            lines.add("Candles=" + candles + " first=" + instant(firstTime) + " last=" + instant(lastTime)
                    + " staleMs=" + staleMillis);
            lines.add("Gaps=" + gaps + " duplicates=" + duplicates + " outOfOrder=" + outOfOrder
                    + " dataLakeTicks=" + tickPartitionAvailable);
            if (!warnings.isEmpty()) lines.add("Warnings: " + String.join("; ", warnings));
            return lines;
        }

        private String instant(long time) {
            return time <= 0L ? "-" : Instant.ofEpochMilli(time).toString();
        }
    }
}
