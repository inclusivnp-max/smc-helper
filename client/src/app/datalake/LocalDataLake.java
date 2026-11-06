package app.datalake;

import app.backtest.Tick;
import app.common.ProjectPaths;
import app.orderbook.OrderBookLevel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;
import java.util.stream.Stream;

public class LocalDataLake {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    public static Path root() {
        return ProjectPaths.dataLakeRoot();
    }

    public static Path partition(String dataset, String market, String symbol, long timeMillis) {
        String safeDataset = safe(dataset);
        String safeMarket = safe(market);
        String safeSymbol = safe(symbol);
        String day = DAY.format(Instant.ofEpochMilli(timeMillis));
        return root().resolve(safeDataset)
                .resolve("market=" + safeMarket)
                .resolve("symbol=" + safeSymbol)
                .resolve("date=" + day);
    }

    public static TickLoadResult loadAggTradeTicks(String symbol, String market,
                                                   long startTime, long endTime) {
        List<Path> partitions = partitions("agg_trades", market, symbol, startTime, endTime);
        if (partitions.isEmpty()) return TickLoadResult.miss(root().toString());

        List<Tick> ticks = new ArrayList<>();
        boolean parquetSeen = false;
        for (Path partition : partitions) {
            if (!Files.isDirectory(partition)) continue;
            try (Stream<Path> files = Files.list(partition)) {
                List<Path> sorted = files.sorted().toList();
                for (Path file : sorted) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".csv.gz")) {
                        ticks.addAll(readCsvGzTicks(file, startTime, endTime));
                    } else if (name.endsWith(".parquet")) {
                        parquetSeen = true;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        if (ticks.isEmpty() && parquetSeen) {
            ticks.addAll(readParquetTicksWithPython(symbol, market, startTime, endTime));
        }
        ticks.sort(Comparator.comparingLong((Tick t) -> t.time).thenComparingLong(t -> t.aggregateId));
        return ticks.isEmpty()
                ? TickLoadResult.miss(root().toString())
                : TickLoadResult.hit(ticks, parquetSeen ? "local data lake parquet" : "local data lake csv.gz",
                root().toString());
    }

    public static boolean hasAggTradePartition(String symbol, String market, long startTime, long endTime) {
        return !partitions("agg_trades", market, symbol, startTime, endTime).isEmpty();
    }

    /**
     * Nearest STRICTLY-PAST order-book depth snapshot on disk for (symbol, market) at or before
     * targetTime, within maxStalenessMs. Never looks forward in time -- a depth snapshot taken
     * after the candle it would be attached to is lookahead, the same walk-forward rule the rest
     * of the backtester already follows for candles/ticks. Returns a miss (not an exception) for
     * live/UI-only symbols where no depth_l2 collection has ever run, so callers can fall back
     * to "no evidence" instead of failing.
     */
    public static DepthSnapshotResult loadNearestDepthSnapshot(String symbol, String market,
                                                                long targetTime, long maxStalenessMs) {
        Path file = nearestPastSnapshotFile("depth_l2", market, symbol, targetTime, maxStalenessMs);
        if (file == null) return DepthSnapshotResult.miss();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return DepthSnapshotResult.miss();
            List<String> columns = List.of(header.split(",", -1));
            int timeIdx = columns.indexOf("time");
            int sideIdx = columns.indexOf("side");
            int priceIdx = columns.indexOf("price");
            int qtyIdx = columns.indexOf("quantity");
            if (timeIdx < 0 || sideIdx < 0 || priceIdx < 0 || qtyIdx < 0) return DepthSnapshotResult.miss();

            List<OrderBookLevel> bids = new ArrayList<>();
            List<OrderBookLevel> asks = new ArrayList<>();
            long snapshotTime = -1L;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                int maxIdx = Math.max(Math.max(timeIdx, sideIdx), Math.max(priceIdx, qtyIdx));
                if (parts.length <= maxIdx) continue;
                if (snapshotTime < 0L) snapshotTime = parseLongSafe(parts[timeIdx], -1L);
                double price = parseDoubleSafe(parts[priceIdx]);
                double qty = parseDoubleSafe(parts[qtyIdx]);
                String side = parts[sideIdx].trim();
                if ("bid".equalsIgnoreCase(side)) bids.add(new OrderBookLevel(price, qty));
                else if ("ask".equalsIgnoreCase(side)) asks.add(new OrderBookLevel(price, qty));
            }
            if (bids.isEmpty() && asks.isEmpty()) return DepthSnapshotResult.miss();
            return DepthSnapshotResult.hit(snapshotTime, bids, asks);
        } catch (Exception ex) {
            return DepthSnapshotResult.miss();
        }
    }

    /**
     * Nearest strictly-past derivatives (open interest / funding) snapshot on disk, same
     * walk-forward-safe lookup as loadNearestDepthSnapshot.
     */
    public static DerivativesSnapshotResult loadNearestDerivativesSnapshot(String symbol, String market,
                                                                            long targetTime, long maxStalenessMs) {
        Path file = nearestPastSnapshotFile("derivatives", market, symbol, targetTime, maxStalenessMs);
        if (file == null) return DerivativesSnapshotResult.miss();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return DerivativesSnapshotResult.miss();
            List<String> columns = List.of(header.split(",", -1));
            int timeIdx = columns.indexOf("time");
            int oiIdx = columns.indexOf("open_interest");
            int fundingIdx = columns.indexOf("funding_rate");
            int markIdx = columns.indexOf("mark_price");
            if (timeIdx < 0 || oiIdx < 0 || fundingIdx < 0) return DerivativesSnapshotResult.miss();
            String line = reader.readLine();
            if (line == null) return DerivativesSnapshotResult.miss();
            String[] parts = line.split(",", -1);
            int maxIdx = Math.max(Math.max(timeIdx, oiIdx), fundingIdx);
            if (parts.length <= maxIdx) return DerivativesSnapshotResult.miss();
            long time = parseLongSafe(parts[timeIdx], -1L);
            double openInterest = parseDoubleSafe(parts[oiIdx]);
            double fundingRate = parseDoubleSafe(parts[fundingIdx]);
            double markPrice = markIdx >= 0 && markIdx < parts.length ? parseDoubleSafe(parts[markIdx]) : Double.NaN;
            if (time < 0L) return DerivativesSnapshotResult.miss();
            return DerivativesSnapshotResult.hit(time, openInterest, fundingRate, markPrice);
        } catch (Exception ex) {
            return DerivativesSnapshotResult.miss();
        }
    }

    /**
     * Latest snapshot file (by the `time` column inside it, not the filename) whose time falls
     * in [targetTime - maxStalenessMs, targetTime]. Reads each candidate file's first data row to
     * check its timestamp -- fine at the file counts a data lake like this accumulates day to
     * day; if collection ever grows to thousands of files per symbol per day, this would want a
     * cached (dataset,symbol,day) -> sorted-times index instead of scanning every call.
     */
    private static Path nearestPastSnapshotFile(String dataset, String market, String symbol,
                                                 long targetTime, long maxStalenessMs) {
        long earliest = targetTime - Math.max(0L, maxStalenessMs);
        List<Path> dayDirs = partitions(dataset, market, symbol, earliest, targetTime);
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        for (Path dir : dayDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv.gz")).toList()) {
                    long fileTime = firstDataRowTime(file);
                    if (fileTime < 0L || fileTime > targetTime || fileTime < earliest) continue;
                    if (fileTime > bestTime) {
                        bestTime = fileTime;
                        best = file;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return best;
    }

    private static long firstDataRowTime(Path file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return -1L;
            List<String> columns = List.of(header.split(",", -1));
            int timeIdx = columns.indexOf("time");
            if (timeIdx < 0) timeIdx = 0;
            String line = reader.readLine();
            if (line == null) return -1L;
            String[] parts = line.split(",", -1);
            if (timeIdx >= parts.length) return -1L;
            return parseLongSafe(parts[timeIdx], -1L);
        } catch (Exception ex) {
            return -1L;
        }
    }

    private static long parseLongSafe(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static double parseDoubleSafe(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ex) {
            return 0.0;
        }
    }

    public static String safe(String value) {
        String text = value == null || value.isBlank() ? "unknown" : value.toUpperCase(Locale.ROOT);
        return text.replaceAll("[^A-Z0-9_\\-]", "");
    }

    private static List<Path> partitions(String dataset, String market, String symbol, long startTime, long endTime) {
        List<Path> result = new ArrayList<>();
        LocalDate start = Instant.ofEpochMilli(startTime).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(endTime).atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Path upper = root().resolve(safe(dataset))
                    .resolve("market=" + safe(market))
                    .resolve("symbol=" + safe(symbol))
                    .resolve("date=" + day);
            Path lower = root().resolve(dataset.toLowerCase(Locale.ROOT))
                    .resolve("market=" + safe(market))
                    .resolve("symbol=" + safe(symbol))
                    .resolve("date=" + day);
            if (Files.isDirectory(upper)) result.add(upper);
            if (!upper.equals(lower) && Files.isDirectory(lower)) result.add(lower);
        }
        return result;
    }

    private static List<Tick> readCsvGzTicks(Path file, long startTime, long endTime) {
        List<Tick> ticks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            int idIndex = 0, timeIndex = 1, priceIndex = 2, qtyIndex = 3, makerIndex = 4;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                if (header) {
                    header = false;
                    if (!isNumeric(parts[0])) {
                        idIndex = indexOf(parts, "aggregate_id", "aggregateId", "id");
                        timeIndex = indexOf(parts, "time");
                        priceIndex = indexOf(parts, "price");
                        qtyIndex = indexOf(parts, "quantity", "qty");
                        makerIndex = indexOf(parts, "buyer_maker", "buyerMaker");
                        continue;
                    }
                }
                if (parts.length <= Math.max(Math.max(idIndex, timeIndex), Math.max(priceIndex, Math.max(qtyIndex, makerIndex)))) {
                    continue;
                }
                long time = Long.parseLong(parts[timeIndex]);
                if (time < startTime || time > endTime) continue;
                ticks.add(new Tick(
                        Long.parseLong(parts[idIndex]),
                        time,
                        Double.parseDouble(parts[priceIndex]),
                        Double.parseDouble(parts[qtyIndex]),
                        Boolean.parseBoolean(parts[makerIndex])));
            }
        } catch (Exception ignored) {
        }
        return ticks;
    }

    private static List<Tick> readParquetTicksWithPython(String symbol, String market, long startTime, long endTime) {
        List<Tick> ticks = new ArrayList<>();
        Path script = ProjectPaths.tool("read_datalake_ticks.py");
        if (!Files.exists(script)) return ticks;
        String python = resolvePython();
        ProcessBuilder builder = new ProcessBuilder(
                python,
                script.toString(),
                "--root", root().toString(),
                "--symbol", safe(symbol),
                "--market", safe(market),
                "--start", Long.toString(startTime),
                "--end", Long.toString(endTime));
        builder.redirectErrorStream(true);
        Process process = null;
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        try {
            process = builder.start();
            Process running = process;
            Future<List<Tick>> readerFuture = readerExecutor.submit(() -> {
                List<Tick> parsed = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        running.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split(",", -1);
                    if (parts.length < 5 || !isNumeric(parts[0])) continue;
                    parsed.add(new Tick(
                            Long.parseLong(parts[0]),
                            Long.parseLong(parts[1]),
                            Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3]),
                            Boolean.parseBoolean(parts[4])));
                }
                }
                return parsed;
            });
            boolean finished = process.waitFor(4, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerFuture.cancel(true);
                return ticks;
            }
            ticks.addAll(readerFuture.get(1, TimeUnit.SECONDS));
        } catch (Exception ignored) {
            if (process != null && process.isAlive()) process.destroyForcibly();
        } finally {
            readerExecutor.shutdownNow();
        }
        return ticks;
    }

    private static String resolvePython() {
        String property = System.getProperty("smc.python.path", "");
        if (!property.isBlank()) return property;
        String env = System.getenv("SMC_PYTHON");
        if (env != null && !env.isBlank()) return env;
        Path bundled = Path.of(System.getProperty("user.home"), ".cache", "codex-runtimes",
                "codex-primary-runtime", "dependencies", "python", "python.exe");
        if (Files.exists(bundled)) return bundled.toString();
        return "python";
    }

    private static int indexOf(String[] headers, String... names) {
        for (String name : names) {
            for (int i = 0; i < headers.length; i++) {
                if (headers[i].trim().equalsIgnoreCase(name)) return i;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) return false;
        char first = value.trim().charAt(0);
        return Character.isDigit(first) || first == '-';
    }

    public static class TickLoadResult {
        public final List<Tick> ticks;
        public final boolean hit;
        public final String source;
        public final String rootPath;

        private TickLoadResult(List<Tick> ticks, boolean hit, String source, String rootPath) {
            this.ticks = ticks;
            this.hit = hit;
            this.source = source == null ? "" : source;
            this.rootPath = rootPath == null ? "" : rootPath;
        }

        static TickLoadResult hit(List<Tick> ticks, String source, String rootPath) {
            return new TickLoadResult(ticks, true, source, rootPath);
        }

        static TickLoadResult miss(String rootPath) {
            return new TickLoadResult(List.of(), false, "", rootPath);
        }
    }

    public static final class DepthSnapshotResult {
        public final boolean hit;
        public final long snapshotTime;
        public final List<OrderBookLevel> bids;
        public final List<OrderBookLevel> asks;

        private DepthSnapshotResult(boolean hit, long snapshotTime,
                                    List<OrderBookLevel> bids, List<OrderBookLevel> asks) {
            this.hit = hit;
            this.snapshotTime = snapshotTime;
            this.bids = bids;
            this.asks = asks;
        }

        static DepthSnapshotResult hit(long snapshotTime, List<OrderBookLevel> bids, List<OrderBookLevel> asks) {
            return new DepthSnapshotResult(true, snapshotTime, bids, asks);
        }

        static DepthSnapshotResult miss() {
            return new DepthSnapshotResult(false, -1L, List.of(), List.of());
        }
    }

    public static final class DerivativesSnapshotResult {
        public final boolean hit;
        public final long snapshotTime;
        public final double openInterest;
        public final double fundingRate;
        public final double markPrice;

        private DerivativesSnapshotResult(boolean hit, long snapshotTime, double openInterest,
                                          double fundingRate, double markPrice) {
            this.hit = hit;
            this.snapshotTime = snapshotTime;
            this.openInterest = openInterest;
            this.fundingRate = fundingRate;
            this.markPrice = markPrice;
        }

        static DerivativesSnapshotResult hit(long snapshotTime, double openInterest,
                                             double fundingRate, double markPrice) {
            return new DerivativesSnapshotResult(true, snapshotTime, openInterest, fundingRate, markPrice);
        }

        static DerivativesSnapshotResult miss() {
            return new DerivativesSnapshotResult(false, -1L, Double.NaN, 0.0, Double.NaN);
        }
    }
}
