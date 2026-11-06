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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class WebSocketTickStore {
    private static final Object WRITE_LOCK = new Object();
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(10_000),
            task -> {
                Thread thread = new Thread(task, "SMC-WS-TickStore");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private static final Path ROOT = ProjectPaths.websocketTicksRoot();

    private WebSocketTickStore() {
    }

    public static Path root() {
        return ROOT;
    }

    public static void appendAsync(String symbol, BinanceClient.Market market, Tick tick) {
        if (tick == null || tick.time <= 0L || tick.price <= 0.0) return;
        WRITER.execute(() -> append(symbol, market, tick));
    }

    public static void append(String symbol, BinanceClient.Market market, Tick tick) {
        if (tick == null || tick.time <= 0L || tick.price <= 0.0) return;
        Path path = dayPath(symbol, market, tick.time);
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(path.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
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
            } catch (IOException ignored) {
                // Live tick storage should never block chart updates or trading UI.
            }
        }
    }

    public static TickLoadResult load(String symbol, BinanceClient.Market market, long startTime, long endTime) {
        if (startTime <= 0L || endTime < startTime) return TickLoadResult.miss(ROOT.toString());
        List<Tick> ticks = new ArrayList<>();
        boolean fileSeen = false;
        LocalDate day = Instant.ofEpochMilli(startTime).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate lastDay = Instant.ofEpochMilli(endTime).atZone(ZoneOffset.UTC).toLocalDate();
        while (!day.isAfter(lastDay)) {
            Path path = dayPath(symbol, market, day);
            if (Files.exists(path)) {
                fileSeen = true;
                ticks.addAll(read(path, startTime, endTime));
            }
            day = day.plusDays(1);
        }
        if (ticks.isEmpty()) return TickLoadResult.miss(ROOT.toString(), fileSeen);
        ticks.sort(Comparator.comparingLong((Tick t) -> t.time).thenComparingLong(t -> t.aggregateId));
        return TickLoadResult.hit(unique(ticks), ROOT.toString());
    }

    private static List<Tick> read(Path path, long startTime, long endTime) {
        List<Tick> ticks = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 5) continue;
                long time = Long.parseLong(parts[1]);
                if (time < startTime || time > endTime) continue;
                double price = Double.parseDouble(parts[2]);
                if (price <= 0.0) continue;
                ticks.add(new Tick(
                        Long.parseLong(parts[0]),
                        time,
                        price,
                        Double.parseDouble(parts[3]),
                        Boolean.parseBoolean(parts[4])));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return ticks;
    }

    private static List<Tick> unique(List<Tick> ticks) {
        List<Tick> result = new ArrayList<>(ticks.size());
        Set<String> seen = new HashSet<>();
        for (Tick tick : ticks) {
            String key = tick.aggregateId + "|" + tick.time + "|" + tick.price;
            if (seen.add(key)) result.add(tick);
        }
        return result;
    }

    private static Path dayPath(String symbol, BinanceClient.Market market, long time) {
        return dayPath(symbol, market, Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate());
    }

    private static Path dayPath(String symbol, BinanceClient.Market market, LocalDate day) {
        String safeSymbol = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]", "");
        BinanceClient.Market safeMarket = market == null ? BinanceClient.Market.FUTURES : market;
        return ROOT.resolve(safeMarket.name().toLowerCase(Locale.ROOT))
                .resolve(safeSymbol)
                .resolve(DAY.format(day.atStartOfDay().toInstant(ZoneOffset.UTC)) + ".csv");
    }

    public static class TickLoadResult {
        public final List<Tick> ticks;
        public final boolean hit;
        public final boolean fileSeen;
        public final String rootPath;

        private TickLoadResult(List<Tick> ticks, boolean hit, boolean fileSeen, String rootPath) {
            this.ticks = ticks == null ? List.of() : ticks;
            this.hit = hit;
            this.fileSeen = fileSeen;
            this.rootPath = rootPath == null ? "" : rootPath;
        }

        static TickLoadResult hit(List<Tick> ticks, String rootPath) {
            return new TickLoadResult(ticks, true, true, rootPath);
        }

        static TickLoadResult miss(String rootPath) {
            return miss(rootPath, false);
        }

        static TickLoadResult miss(String rootPath, boolean fileSeen) {
            return new TickLoadResult(List.of(), false, fileSeen, rootPath);
        }
    }
}
