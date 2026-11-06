package app.backtest;

import app.common.ProjectPaths;
import app.logic.BinanceClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class TickCache {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private static final Path ROOT = ProjectPaths.tickCacheRoot();

    public interface TickFetcher {
        List<Tick> fetch() throws IOException, InterruptedException;
    }

    public static CacheResult loadOrFetch(String symbol,
                                          long startTime,
                                          long endTime,
                                          BinanceClient.Market market,
                                          int maxPages,
                                          TickFetcher fetcher) throws IOException, InterruptedException {
        Path path = cachePath(symbol, startTime, endTime, market, maxPages);
        if (Files.exists(path)) {
            return new CacheResult(read(path), true);
        }

        List<Tick> ticks = fetcher.fetch();
        writeAtomic(path, ticks);
        return new CacheResult(ticks, false);
    }

    public static Path root() {
        return ROOT;
    }

    private static Path cachePath(String symbol, long startTime, long endTime,
                                  BinanceClient.Market market, int maxPages) {
        String safeSymbol = symbol.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]", "");
        String day = DAY.format(Instant.ofEpochMilli(startTime));
        String file = startTime + "_" + endTime + "_p" + maxPages + ".csv.gz";
        return ROOT.resolve(market.name().toLowerCase(Locale.ROOT)).resolve(safeSymbol).resolve(day).resolve(file);
    }

    private static List<Tick> read(Path path) throws IOException {
        List<Tick> ticks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 5) continue;
                ticks.add(new Tick(
                        Long.parseLong(parts[0]),
                        Long.parseLong(parts[1]),
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]),
                        Boolean.parseBoolean(parts[4])));
            }
        }
        return ticks;
    }

    private static void writeAtomic(Path path, List<Tick> ticks) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8))) {
            for (Tick tick : ticks) {
                writer.write(Long.toString(tick.aggregateId));
                writer.write(',');
                writer.write(Long.toString(tick.time));
                writer.write(',');
                writer.write(Double.toString(tick.price));
                writer.write(',');
                writer.write(Double.toString(tick.quantity));
                writer.write(',');
                writer.write(Boolean.toString(tick.buyerMaker));
                writer.newLine();
            }
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static class CacheResult {
        public final List<Tick> ticks;
        public final boolean hit;

        CacheResult(List<Tick> ticks, boolean hit) {
            this.ticks = ticks;
            this.hit = hit;
        }
    }
}
