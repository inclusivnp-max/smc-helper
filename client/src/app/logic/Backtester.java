package app.logic;

import app.backtest.BacktestAssumptions;
import app.backtest.BacktestEvidenceStore;
import app.backtest.MarketImpactModel;
import app.backtest.Tick;
import app.backtest.TickCache;
import app.backtest.WebSocketTickStore;
import app.common.Direction;
import app.common.ProjectPaths;
import app.datalake.LocalDataLake;
import app.derivatives.DerivativesContext;
import app.derivatives.DerivativesEngine;
import app.displacement.DisplacementEvent;
import app.model.AnalysisResult;
import app.model.Candle;
import app.model.TradePlan;
import app.orderbook.OrderBookContext;
import app.orderbook.OrderBookEngine;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Backtester {
    private static final long TICK_API_COOLDOWN_MS = 5 * 60_000L;
    private static volatile long tickApiBlockedUntil = 0L;

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    public static class Report {
        public int totalSetups = 0;
        public int executedTrades = 0;
        public int wins = 0;
        public int losses = 0;
        public int breakeven = 0;
        public int missed = 0;
        public double winrate = 0.0;
        public double expectancy = 0.0;
        public double profitFactor = 0.0;
        public double maxDrawdown = 0.0;
        public double sharpeRatio = 0.0;
        public double netR = 0.0;
        public double avgR = 0.0;
        public double grossProfitR = 0.0;
        public double grossLossR = 0.0;
        public String executionModel = "AggTrades ticks + conservative OHLC fallback";
        public int historyCandles = 0;
        public int warmupCandles = 0;
        public int testedCandles = 0;
        public int tickExecutionCandles = 0;
        public int ohlcFallbackCandles = 0;
        public int tickFetchErrors = 0;
        public int intrabarEvents = 0;
        public int tickCacheHits = 0;
        public int tickCacheMisses = 0;
        public String tickCachePath = TickCache.root().toString();
        public boolean tickApiCooldown = false;
        public int websocketTickCandles = 0;
        public int websocketTickMisses = 0;
        public String websocketTickPath = WebSocketTickStore.root().toString();
        public int dataLakeTickCandles = 0;
        public int dataLakeMisses = 0;
        public String dataLakePath = LocalDataLake.root().toString();
        public String dataLakeSource = "";
        public boolean marketImpactEnabled = false;
        public double executionNotionalUsd = 0.0;
        public int executionSlices = 0;
        public double totalImpactR = 0.0;
        public double avgImpactR = 0.0;
        public double avgImpactBps = 0.0;
        public double avgParticipationPct = 0.0;
        public int openEndOfDataTrades = 0;
        public double floatingOpenR = 0.0;
        public final List<TradeRecord> records = new ArrayList<>();
        public final Map<String, StatsBucket> setupStats = new HashMap<>();
        public final Map<String, StatsBucket> regimeStats = new HashMap<>();
        public final Map<String, StatsBucket> factorStats = new HashMap<>();
        public double avgBarsToTp1 = 0.0;
        public double avgBarsToTp2 = 0.0;
        public double avgBarsToTp3 = 0.0;
        public double avgBarsToStop = 0.0;
        public String csvPath = "";
        public String csvError = "";
    }

    private static class Position {
        TradePlan plan;
        SetupSnapshot setup;
        double entry;
        double risk;
        double stop;
        double remaining = 1.0;
        double realizedR = 0.0;
        boolean tp1;
        boolean tp2;
        boolean tp3;
        long entryTime;
        long exitTime;
        long tp1Time;
        long tp2Time;
        long tp3Time;
        String exitReason = "";
        double entryImpactBps = 0.0;
        double entryParticipationPct = 0.0;
        int entrySlices = 1;
        double impactR = 0.0;

        Position(TradePlan plan, SetupSnapshot setup, double entry, double stop, long entryTime) {
            this.plan = plan;
            this.setup = setup;
            this.entry = entry;
            this.stop = stop;
            this.entryTime = entryTime;
            this.risk = Math.abs(entry - stop);
        }
    }

    public static class TradeRecord {
        public String symbol;
        public String market;
        public String ltfInterval;
        public String outcome;
        public String direction;
        public String setupType;
        public String targetModel;
        public String marketRegime;
        public String deviationType;
        public int deviationScore;
        public boolean deviationReturnedInside;
        public boolean deviationDisplacement;
        public String imbalanceDirection;
        public int imbalanceScore;
        public int eventRiskScore;
        public boolean eventRiskBlocked;
        public String zoneType;
        public String zoneQuality;
        public String session;
        public String phase;
        public boolean killzone;
        public boolean sweepConfirmed;
        public boolean entryConfirmed;
        public boolean htfDisplacementAligned;
        public int orderFlowScore;
        public int smcScore;
        public int zoneScore;
        public int displacementScore;
        public int liquidityScore;
        public int volumeScore;
        public int htfStrength;
        public int ltfStrength;
        public long signalTime;
        public long entryTime;
        public long exitTime;
        public long barsToTp1;
        public long barsToTp2;
        public long barsToTp3;
        public long barsToStop;
        public double signalPrice;
        public double entry;
        public double theoreticalEntry;
        public double entryImpactBps;
        public double entryParticipationPct;
        public int entrySlices;
        public double impactR;
        public double stop;
        public double tp1;
        public double tp2;
        public double tp3;
        public double resultR;
        public String exitReason;
    }

    public static class StatsBucket {
        public int trades;
        public int wins;
        public int losses;
        public double netR;

        void add(double r) {
            trades++;
            netR += r;
            if (r > 0.05) wins++;
            else if (r < -0.05) losses++;
        }

        public double winrate() {
            return trades == 0 ? 0.0 : wins * 100.0 / trades;
        }

        public double expectancy() {
            return trades == 0 ? 0.0 : netR / trades;
        }
    }

    private static class SetupSnapshot {
        String symbol;
        BinanceClient.Market market;
        TradePlan plan;
        AnalysisResult result;
        long signalTime;
        boolean htfDisplacementAligned;

        SetupSnapshot(String symbol, BinanceClient.Market market, AnalysisResult result, long signalTime,
                      boolean htfDisplacementAligned) {
            this.symbol = symbol;
            this.market = market;
            this.result = result;
            this.plan = result.plan;
            this.signalTime = signalTime;
            this.htfDisplacementAligned = htfDisplacementAligned;
        }
    }

    public static Report run(List<Candle> htfHistory, List<Candle> ltfHistory, String trendMethod) {
        return run("BACKTEST", htfHistory, ltfHistory, trendMethod, BinanceClient.Market.SPOT);
    }

    public static Report run(String symbol, List<Candle> htfHistory, List<Candle> ltfHistory,
                             String trendMethod, BinanceClient.Market market) {
        BacktestAssumptions assumptions = BacktestAssumptions.defaults();
        return run(symbol, htfHistory, ltfHistory, trendMethod, market, assumptions);
    }

    public static Report run(String symbol, List<Candle> htfHistory, List<Candle> ltfHistory,
                             String trendMethod, BinanceClient.Market market,
                             BacktestAssumptions assumptions) {
        return run(symbol, htfHistory, ltfHistory, trendMethod, market, assumptions, null);
    }

    public static Report run(String symbol, List<Candle> htfHistory, List<Candle> ltfHistory,
                             String trendMethod, BinanceClient.Market market,
                             BacktestAssumptions assumptions,
                             ProgressListener progressListener) {
        Report report = new Report();
        report.marketImpactEnabled = assumptions.useMarketImpact;
        report.executionNotionalUsd = assumptions.executionNotionalUsd;
        report.executionSlices = assumptions.executionSlices;
        report.executionModel = assumptions.useTickData
                ? "WebSocket/DataLake AggTrades + OHLC fallback"
                : "Fast conservative OHLC";
        report.executionModel += " · SMC " + assumptions.minConfidenceScore + "+"
                + (assumptions.requireHtfDisplacementAligned ? " · HTF displacement only" : "")
                + " · TP " + (int) Math.round(assumptions.tp1Size * 100)
                + "/" + (int) Math.round(assumptions.tp2Size * 100)
                + "/" + (int) Math.round(assumptions.tp3Size * 100)
                + (assumptions.useMarketImpact
                ? " · Impact $" + (int) Math.round(assumptions.executionNotionalUsd)
                + "/" + assumptions.executionSlices + " slices"
                : "");
        List<Double> tradeRs = new ArrayList<>();
        List<Double> equityRs = new ArrayList<>();
        Map<String, PricePath> pathCache = new HashMap<>();

        TradePlan activePlan = null;
        SetupSnapshot activeSetup = null;
        Position position = null;
        int htfCursor = 0;
        int windowSize = 250;
        int totalSteps = Math.max(1, ltfHistory.size() - windowSize);
        report.historyCandles = ltfHistory.size();
        report.warmupCandles = Math.min(windowSize, ltfHistory.size());
        report.testedCandles = Math.max(0, ltfHistory.size() - report.warmupCandles);
        progress(progressListener, 1, "Подготовка бэктеста...");

        for (int i = windowSize; i < ltfHistory.size(); i++) {
            Candle current = ltfHistory.get(i);
            int processed = i - windowSize + 1;
            if (processed == 1 || processed == totalSteps || processed % 10 == 0) {
                int pct = 30 + (int) Math.round(processed * 67.0 / totalSteps);
                progress(progressListener, pct, "Бэктест: " + processed + "/" + totalSteps + " свечей");
            }

            if (position != null) {
                PricePath path = pricePath(symbol, ltfHistory, i, market, assumptions, report, pathCache, position.plan.longSide);
                Double closedR = updatePosition(position, path.points, assumptions, report);
                if (closedR != null) {
                    recordTrade(report, tradeRs, equityRs, closedR, position);
                    position = null;
                    activePlan = null;
                    activeSetup = null;
                    continue;
                }
            }

            if (position == null && activePlan != null) {
                PricePath path = pricePath(symbol, ltfHistory, i, market, assumptions, report, pathCache, activePlan.longSide);
                EntryResult entryResult = processWaitingPlan(activePlan, activeSetup, current, path.points, assumptions, report);
                if (entryResult.missed) {
                    report.missed++;
                    report.records.add(toRecord(activeSetup, "MISSED", 0.0, 0L, 0L, "MISSED_TO_TP"));
                    activePlan = null;
                    activeSetup = null;
                } else if (entryResult.closedR != null) {
                    recordTrade(report, tradeRs, equityRs, entryResult.closedR, entryResult.closedPosition);
                    activePlan = null;
                    activeSetup = null;
                    position = null;
                } else if (entryResult.position != null) {
                    position = entryResult.position;
                }
            }

            if (position == null && activePlan == null) {
                List<Candle> ltfWindow = ltfHistory.subList(i - windowSize, i);
                long currentLtfTime = ltfWindow.get(ltfWindow.size() - 1).time;
                while (htfCursor < htfHistory.size() && htfHistory.get(htfCursor).time <= currentLtfTime) {
                    htfCursor++;
                }
                if (htfCursor < 30) continue;
                List<Candle> syncedHtf = htfHistory.subList(0, htfCursor);

                // Replay real historical DOM/derivatives evidence when the local data lake has
                // coverage for this candle's timestamp, instead of unconditionally skipping both
                // signals during backtest the way this call used to (includeExternalContext could
                // only ever be a live REST fetch, which is meaningless for a past candle -- so it
                // was always false here). Falls back to null/unavailable exactly like before on
                // any symbol/period the data lake hasn't collected yet.
                double ltfClose = ltfWindow.get(ltfWindow.size() - 1).close;
                DerivativesContext rawDerivatives = new DerivativesEngine().analyzeHistorical(
                        symbol, market.label, currentLtfTime);
                OrderBookContext rawOrderBook = new OrderBookEngine().analyzeHistorical(
                        symbol, market.label, ltfClose, currentLtfTime);
                // Pass null (not an "unavailable" object) on a miss -- SMCAnalyzer treats a
                // non-null historical context as real evidence and augments the score with it,
                // so a miss must look identical to "no historical context was ever supplied,"
                // preserving today's behavior everywhere the data lake has no coverage.
                DerivativesContext historicalDerivatives = rawDerivatives != null && rawDerivatives.available
                        ? rawDerivatives : null;
                OrderBookContext historicalOrderBook = rawOrderBook != null && rawOrderBook.available
                        ? rawOrderBook : null;
                AnalysisResult res = SMCAnalyzer.analyze(symbol, "LTF", syncedHtf, ltfWindow, trendMethod, market,
                        false, false, historicalDerivatives, historicalOrderBook);
                boolean htfDisplacementAligned = isHtfDisplacementAligned(res);
                if (passesSetupFilters(res, assumptions, htfDisplacementAligned)) {
                    activePlan = res.plan;
                    activeSetup = new SetupSnapshot(symbol, market, res, currentLtfTime, htfDisplacementAligned);
                    report.totalSetups++;
                }
            }
        }

        if (position != null && !ltfHistory.isEmpty()) {
            Candle last = ltfHistory.get(ltfHistory.size() - 1);
            recordOpenEndOfData(report, equityRs, position, assumptions, last.close, last.time);
        }

        finalizeStats(report, tradeRs, equityRs);
        finalizeTimingStats(report);
        exportCsv(report, symbol, market, assumptions);
        exportEvidence(report);
        progress(progressListener, 100, "Бэктест завершен");
        return report;
    }

    private static void progress(ProgressListener listener, int percent, String message) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, percent)), message);
    }

    private static EntryResult processWaitingPlan(TradePlan plan, SetupSnapshot setup, Candle executionCandle, List<PricePoint> points,
                                                  BacktestAssumptions assumptions, Report report) {
        EntryResult result = new EntryResult();
        Position position = null;
        for (PricePoint point : points) {
            if (position == null) {
                if (hitsEntry(point.price, plan.entry, plan.longSide)) {
                    MarketImpactModel.Fill fill = executionEntry(plan, executionCandle, assumptions);
                    double entry = fill.averageEntry;
                    position = new Position(plan, setup, entry, plan.stop, point.time);
                    position.entryImpactBps = fill.impactBps;
                    position.entryParticipationPct = fill.participationPct;
                    position.entrySlices = fill.slices;
                    position.impactR = Math.abs(entry - plan.entry) / Math.max(position.risk, 0.00000001);
                    report.totalImpactR += position.impactR;
                    report.avgImpactBps += fill.impactBps;
                    report.avgParticipationPct += fill.participationPct;
                    report.intrabarEvents++;
                    Double closedR = updatePositionAtPrice(position, point, assumptions, report);
                    if (closedR != null) {
                        result.closedR = closedR;
                        result.closedPosition = position;
                        return result;
                    }
                    continue;
                }
                if (hitsTargetPrice(point.price, plan.tp1, plan.longSide)) {
                    result.missed = true;
                    report.intrabarEvents++;
                    return result;
                }
                continue;
            }

            Double closedR = updatePositionAtPrice(position, point, assumptions, report);
            if (closedR != null) {
                result.closedR = closedR;
                result.closedPosition = position;
                return result;
            }
        }
        result.position = position;
        return result;
    }

    private static Double updatePosition(Position p, List<PricePoint> points,
                                         BacktestAssumptions assumptions, Report report) {
        for (PricePoint point : points) {
            Double closedR = updatePositionAtPrice(p, point, assumptions, report);
            if (closedR != null) return closedR;
        }
        return null;
    }

    private static Double updatePositionAtPrice(Position p, PricePoint point,
                                                BacktestAssumptions assumptions, Report report) {
        double price = point.price;
        boolean longSide = p.plan.longSide;
        double costR = assumptions.roundTripCostR(p.entry, p.risk);

        if (hitsStopPrice(price, p.stop, longSide)) {
            double stopR = longSide ? (p.stop - p.entry) / p.risk : (p.entry - p.stop) / p.risk;
            report.intrabarEvents++;
            p.exitTime = point.time;
            p.exitReason = "STOP";
            return p.realizedR + p.remaining * stopR - costR;
        }

        if (!p.tp1 && hitsTargetPrice(price, p.plan.tp1, longSide)) {
            p.realizedR += assumptions.tp1Size * targetR(p, p.plan.tp1);
            p.remaining -= assumptions.tp1Size;
            p.tp1 = true;
            p.tp1Time = point.time;
            p.stop = p.entry;
            report.intrabarEvents++;
        }
        if (!p.tp2 && hitsTargetPrice(price, p.plan.tp2, longSide)) {
            p.realizedR += assumptions.tp2Size * targetR(p, p.plan.tp2);
            p.remaining -= assumptions.tp2Size;
            p.tp2 = true;
            p.tp2Time = point.time;
            p.stop = p.plan.tp1;
            report.intrabarEvents++;
        }
        if (!p.tp3 && hitsTargetPrice(price, p.plan.tp3, longSide)) {
            p.realizedR += assumptions.tp3Size * targetR(p, p.plan.tp3);
            p.remaining = 0.0;
            p.tp3 = true;
            p.tp3Time = point.time;
            report.intrabarEvents++;
            p.exitTime = point.time;
            p.exitReason = "TP3";
            return p.realizedR - costR;
        }

        if (p.remaining <= 0.0001) {
            p.exitTime = point.time;
            p.exitReason = "FLAT";
            return p.realizedR - costR;
        }
        return null;
    }

    private static double targetR(Position p, double target) {
        if (p == null || p.risk <= 0.0 || !Double.isFinite(target)) return 0.0;
        return Math.abs(target - p.entry) / p.risk;
    }

    private static PricePath pricePath(String symbol, List<Candle> candles, int index,
                                       BinanceClient.Market market, BacktestAssumptions assumptions,
                                       Report report, Map<String, PricePath> cache, boolean longContext) {
        Candle candle = candles.get(index);
        String cacheKey = candle.time + "|" + (longContext ? "L" : "S");
        PricePath cached = cache.get(cacheKey);
        if (cached != null) return cached;

        long endTime = candleEndTime(candles, index);
        long span = Math.max(0L, endTime - candle.time);
        if (assumptions.useTickData && span <= 3_600_000L) {
            WebSocketTickStore.TickLoadResult ws = WebSocketTickStore.load(symbol, market, candle.time, endTime);
            report.websocketTickPath = ws.rootPath;
            if (ws.hit && !ws.ticks.isEmpty()) {
                List<PricePoint> points = new ArrayList<>(ws.ticks.size());
                for (Tick tick : ws.ticks) points.add(new PricePoint(tick.time, tick.price));
                PricePath path = new PricePath(points, true);
                cache.put(cacheKey, path);
                report.tickExecutionCandles++;
                report.websocketTickCandles++;
                return path;
            }
            if (ws.fileSeen) report.websocketTickMisses++;
        }

        if (span <= 3_600_000L) {
            LocalDataLake.TickLoadResult lake = LocalDataLake.loadAggTradeTicks(symbol, market.label, candle.time, endTime);
            if (lake.hit && !lake.ticks.isEmpty()) {
                List<PricePoint> points = new ArrayList<>(lake.ticks.size());
                for (Tick tick : lake.ticks) points.add(new PricePoint(tick.time, tick.price));
                PricePath path = new PricePath(points, true);
                cache.put(cacheKey, path);
                report.tickExecutionCandles++;
                report.dataLakeTickCandles++;
                report.dataLakePath = lake.rootPath;
                report.dataLakeSource = lake.source;
                return path;
            }
            if (LocalDataLake.hasAggTradePartition(symbol, market.label, candle.time, endTime)) {
                report.dataLakeMisses++;
            }
        }

        if (assumptions.useTickData && span <= 3_600_000L) {
            try {
                TickCache.CacheResult tickCache = TickCache.loadOrFetch(
                        symbol, candle.time, endTime, market, assumptions.maxAggTradePagesPerCandle,
                        () -> fetchAggTradesWithCircuitBreaker(
                                symbol, candle.time, endTime, market, assumptions.maxAggTradePagesPerCandle));
                List<Tick> ticks = tickCache.ticks;
                if (tickCache.hit) report.tickCacheHits++;
                else report.tickCacheMisses++;
                if (!ticks.isEmpty()) {
                    List<PricePoint> points = new ArrayList<>(ticks.size());
                    for (Tick tick : ticks) points.add(new PricePoint(tick.time, tick.price));
                    PricePath path = new PricePath(points, true);
                    cache.put(cacheKey, path);
                    report.tickExecutionCandles++;
                    return path;
                }
            } catch (Exception ex) {
                report.tickFetchErrors++;
                if (isRateLimit(ex)) {
                    tickApiBlockedUntil = System.currentTimeMillis() + TICK_API_COOLDOWN_MS;
                    report.tickApiCooldown = true;
                }
            }
        }

        PricePath fallback = new PricePath(conservativeOhlcPath(candle, endTime, longContext), false);
        cache.put(cacheKey, fallback);
        report.ohlcFallbackCandles++;
        return fallback;
    }

    private static List<PricePoint> conservativeOhlcPath(Candle candle, long endTime, boolean longContext) {
        long span = Math.max(3L, endTime - candle.time);
        List<PricePoint> points = new ArrayList<>(4);
        points.add(new PricePoint(candle.time, candle.open));
        if (longContext) {
            points.add(new PricePoint(candle.time + span / 3, candle.low));
            points.add(new PricePoint(candle.time + span * 2 / 3, candle.high));
        } else {
            points.add(new PricePoint(candle.time + span / 3, candle.high));
            points.add(new PricePoint(candle.time + span * 2 / 3, candle.low));
        }
        points.add(new PricePoint(endTime, candle.close));
        return points;
    }

    private static long candleEndTime(List<Candle> candles, int index) {
        Candle current = candles.get(index);
        if (index + 1 < candles.size()) return candles.get(index + 1).time - 1L;
        if (index > 0) {
            long step = current.time - candles.get(index - 1).time;
            return current.time + Math.max(60_000L, step) - 1L;
        }
        return current.time + 60_000L - 1L;
    }

    private static List<Tick> fetchAggTradesWithCircuitBreaker(String symbol, long startTime, long endTime,
                                                               BinanceClient.Market market, int maxPages)
            throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (now < tickApiBlockedUntil) {
            throw new RuntimeException("Tick API cooldown active");
        }
        try {
            return BinanceClient.fetchAggTrades(symbol, startTime, endTime, market, maxPages);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (isRateLimit(ex)) {
                tickApiBlockedUntil = System.currentTimeMillis() + TICK_API_COOLDOWN_MS;
            }
            throw ex;
        }
    }

    private static boolean isRateLimit(Exception ex) {
        String message = ex.getMessage();
        return message != null && (message.contains("418") || message.contains("429"));
    }

    private static MarketImpactModel.Fill executionEntry(TradePlan plan, Candle candle, BacktestAssumptions assumptions) {
        double direction = plan.longSide ? 1.0 : -1.0;
        double slipped = plan.entry + direction * plan.entry * assumptions.slippageBps / 10000.0;
        return new MarketImpactModel().estimateEntryFill(plan, candle, assumptions, slipped);
    }

    private static boolean hitsEntry(double price, double entry, boolean longSide) {
        return longSide ? price <= entry : price >= entry;
    }

    private static boolean hitsStopPrice(double price, double stop, boolean longSide) {
        return longSide ? price <= stop : price >= stop;
    }

    private static boolean hitsTargetPrice(double price, double target, boolean longSide) {
        return longSide ? price >= target : price <= target;
    }

    private static void recordTrade(Report report, List<Double> tradeRs, List<Double> equityRs,
                                    double r, Position position) {
        report.executedTrades++;
        tradeRs.add(r);
        equityRs.add(r);
        report.netR += r;
        String outcome;
        if (r > 0.05) {
            report.wins++;
            report.grossProfitR += r;
            outcome = "WIN";
        } else if (r < -0.05) {
            report.losses++;
            report.grossLossR += r;
            outcome = "LOSS";
        } else {
            report.breakeven++;
            outcome = "BREAKEVEN";
        }
        if (position != null) {
            TradeRecord record = toRecord(position.setup, outcome, r, position.entryTime,
                    position.exitTime, position.exitReason);
            record.entry = position.entry;
            fillTiming(record, position);
            record.entryImpactBps = position.entryImpactBps;
            record.entryParticipationPct = position.entryParticipationPct;
            record.entrySlices = position.entrySlices;
            record.impactR = position.impactR;
            report.records.add(record);
            addStats(report.setupStats, record.setupType, r);
            addStats(report.regimeStats, record.marketRegime, r);
            addFactorStats(report.factorStats, record, r);
        }
    }

    private static void recordOpenEndOfData(Report report, List<Double> equityRs,
                                            Position position, BacktestAssumptions assumptions,
                                            double lastPrice, long lastTime) {
        if (position == null) return;
        double r = floatingR(position, lastPrice);
        r -= assumptions.roundTripCostR(position.entry, position.risk);
        position.exitTime = lastTime;
        position.exitReason = "OPEN_EOD";
        report.openEndOfDataTrades++;
        report.floatingOpenR += r;
        equityRs.add(r);
        TradeRecord record = toRecord(position.setup, "OPEN_EOD", r, position.entryTime, lastTime, "OPEN_EOD");
        record.entry = position.entry;
        fillTiming(record, position);
        record.entryImpactBps = position.entryImpactBps;
        record.entryParticipationPct = position.entryParticipationPct;
        record.entrySlices = position.entrySlices;
        record.impactR = position.impactR;
        report.records.add(record);
    }

    private static void fillTiming(TradeRecord record, Position position) {
        if (record == null || position == null) return;
        record.barsToTp1 = barsBetween(position.setup, position.entryTime, position.tp1Time);
        record.barsToTp2 = barsBetween(position.setup, position.entryTime, position.tp2Time);
        record.barsToTp3 = barsBetween(position.setup, position.entryTime, position.tp3Time);
        if ("STOP".equals(position.exitReason)) {
            record.barsToStop = barsBetween(position.setup, position.entryTime, position.exitTime);
        }
    }

    private static long barsBetween(SetupSnapshot setup, long from, long to) {
        if (from <= 0L || to <= from) return 0L;
        long interval = setup == null || setup.result == null ? 60_000L
                : BinanceClient.intervalMillis(setup.result.ltfInterval);
        interval = Math.max(60_000L, interval);
        return Math.max(1L, Math.round((to - from) / (double) interval));
    }

    private static void addFactorStats(Map<String, StatsBucket> stats, TradeRecord record, double r) {
        if (record == null) return;
        addStats(stats, "SMC " + scoreBand(record.smcScore), r);
        addStats(stats, "OF " + scoreBand(record.orderFlowScore), r);
        addStats(stats, record.direction, r);
        if (record.killzone) addStats(stats, "killzone", r);
        if (record.htfDisplacementAligned) addStats(stats, "htf displacement", r);
        if (record.sweepConfirmed) addStats(stats, "sweep", r);
        if (record.entryConfirmed) addStats(stats, "confirmed entry", r);
        if (record.zoneQuality != null && !record.zoneQuality.isBlank()) addStats(stats, "zone " + record.zoneQuality, r);
    }

    private static String scoreBand(int score) {
        if (score >= 90) return "90+";
        if (score >= 80) return "80-89";
        if (score >= 70) return "70-79";
        if (score >= 60) return "60-69";
        return "<60";
    }

    private static double floatingR(Position position, double price) {
        if (position == null || position.risk <= 0.0 || price <= 0.0) return 0.0;
        boolean longSide = position.plan.longSide;
        double openR = longSide ? (price - position.entry) / position.risk : (position.entry - price) / position.risk;
        return position.realizedR + position.remaining * openR;
    }

    private static void addStats(Map<String, StatsBucket> stats, String key, double r) {
        String normalized = key == null || key.isBlank() ? "unknown" : key;
        stats.computeIfAbsent(normalized, k -> new StatsBucket()).add(r);
    }

    private static boolean passesSetupFilters(AnalysisResult result, BacktestAssumptions assumptions,
                                              boolean htfDisplacementAligned) {
        if (result == null || result.plan == null || !result.plan.entryConfirmed) return false;
        if (result.plan.confidence < assumptions.minConfidenceScore) return false;
        return !assumptions.requireHtfDisplacementAligned || htfDisplacementAligned;
    }

    private static boolean isHtfDisplacementAligned(AnalysisResult result) {
        if (result == null || result.plan == null || result.htfDisplacementEvents.isEmpty()) return false;
        Direction desired = result.plan.longSide ? Direction.BULLISH : Direction.BEARISH;
        for (int i = result.htfDisplacementEvents.size() - 1; i >= 0; i--) {
            DisplacementEvent event = result.htfDisplacementEvents.get(i);
            if (event.score < 55) continue;
            return event.direction == desired;
        }
        return false;
    }

    private static TradeRecord toRecord(SetupSnapshot setup, String outcome, double r,
                                        long entryTime, long exitTime, String exitReason) {
        TradeRecord rec = new TradeRecord();
        if (setup == null || setup.plan == null) {
            rec.outcome = outcome;
            rec.resultR = r;
            return rec;
        }
        TradePlan plan = setup.plan;
        rec.symbol = setup.symbol;
        rec.market = setup.market.label;
        rec.ltfInterval = setup.result.ltfInterval;
        rec.outcome = outcome;
        rec.direction = plan.longSide ? "LONG" : "SHORT";
        rec.setupType = plan.setupType;
        rec.targetModel = plan.targetModel;
        rec.marketRegime = setup.result.marketRegime == null ? "" : setup.result.marketRegime.label();
        rec.deviationType = setup.result.deviationContext == null ? "" : setup.result.deviationContext.label;
        rec.deviationScore = setup.result.deviationContext == null ? 0 : setup.result.deviationContext.score;
        rec.deviationReturnedInside = setup.result.deviationContext != null && setup.result.deviationContext.returnedInside;
        rec.deviationDisplacement = setup.result.deviationContext != null && setup.result.deviationContext.displacementAfterReturn;
        rec.imbalanceDirection = setup.result.imbalanceContext == null ? "" : setup.result.imbalanceContext.direction.label();
        rec.imbalanceScore = setup.result.imbalanceContext == null ? 0 : setup.result.imbalanceContext.score;
        rec.eventRiskScore = setup.result.eventRiskContext == null ? 0 : setup.result.eventRiskContext.score;
        rec.eventRiskBlocked = setup.result.eventRiskContext != null && setup.result.eventRiskContext.blocksNewRisk();
        rec.zoneType = plan.zone == null ? "" : plan.zone.type;
        rec.zoneQuality = plan.zone == null ? "" : plan.zone.quality;
        rec.session = setup.result.sessionContext == null ? "" : setup.result.sessionContext.activeSession;
        rec.phase = setup.result.sessionContext == null ? "" : setup.result.sessionContext.phase;
        rec.killzone = setup.result.sessionContext != null && setup.result.sessionContext.inKillzone;
        rec.sweepConfirmed = plan.zone != null && plan.zone.sweepConfirmed;
        rec.entryConfirmed = plan.entryConfirmed;
        rec.htfDisplacementAligned = setup.htfDisplacementAligned;
        rec.orderFlowScore = setup.result.orderFlowContext == null ? 0 : setup.result.orderFlowContext.score;
        rec.smcScore = plan.confidence;
        rec.zoneScore = plan.zone == null ? 0 : plan.zone.score;
        rec.displacementScore = plan.zone == null ? 0 : plan.zone.displacementScore;
        rec.liquidityScore = plan.zone == null ? 0 : plan.zone.liquidityScore;
        rec.volumeScore = plan.zone == null ? 0 : plan.zone.volumeScore;
        rec.htfStrength = setup.result.htfStructure == null ? 0 : setup.result.htfStructure.strength;
        rec.ltfStrength = setup.result.ltfStructure == null ? 0 : setup.result.ltfStructure.strength;
        rec.signalTime = setup.signalTime;
        rec.entryTime = entryTime;
        rec.exitTime = exitTime;
        rec.signalPrice = setup.result.currentPrice;
        rec.theoreticalEntry = plan.entry;
        rec.entry = plan.entry;
        rec.stop = plan.stop;
        rec.tp1 = plan.tp1;
        rec.tp2 = plan.tp2;
        rec.tp3 = plan.tp3;
        rec.resultR = r;
        rec.exitReason = exitReason == null ? "" : exitReason;
        return rec;
    }

    private static void finalizeStats(Report report, List<Double> tradeRs, List<Double> equityRs) {
        if (report.executedTrades > 0) {
            report.winrate = report.wins * 100.0 / report.executedTrades;
            report.avgR = report.netR / report.executedTrades;
            report.expectancy = report.avgR;
            report.avgImpactBps = report.avgImpactBps / report.executedTrades;
            report.avgParticipationPct = report.avgParticipationPct / report.executedTrades;
            report.avgImpactR = report.totalImpactR / report.executedTrades;
            report.profitFactor = report.grossLossR == 0.0
                    ? (report.grossProfitR > 0.0 ? 99.0 : 0.0)
                    : report.grossProfitR / Math.abs(report.grossLossR);
        }

        double equity = 0.0;
        double peak = 0.0;
        double sum = 0.0;
        List<Double> drawdownRs = equityRs.isEmpty() ? tradeRs : equityRs;
        if (drawdownRs.isEmpty()) return;
        for (double r : drawdownRs) {
            equity += r;
            peak = Math.max(peak, equity);
            report.maxDrawdown = Math.max(report.maxDrawdown, peak - equity);
            sum += r;
        }
        double mean = sum / drawdownRs.size();
        double variance = 0.0;
        for (double r : drawdownRs) variance += Math.pow(r - mean, 2);
        double stdev = drawdownRs.size() > 1 ? Math.sqrt(variance / (drawdownRs.size() - 1)) : 0.0;
        report.sharpeRatio = stdev == 0.0 ? 0.0 : mean / stdev * Math.sqrt(drawdownRs.size());
    }

    private static void finalizeTimingStats(Report report) {
        report.avgBarsToTp1 = avgBars(report.records, "TP1");
        report.avgBarsToTp2 = avgBars(report.records, "TP2");
        report.avgBarsToTp3 = avgBars(report.records, "TP3");
        report.avgBarsToStop = avgBars(report.records, "STOP");
    }

    private static double avgBars(List<TradeRecord> records, String level) {
        double sum = 0.0;
        int count = 0;
        for (TradeRecord record : records) {
            long bars = switch (level) {
                case "TP1" -> record.barsToTp1;
                case "TP2" -> record.barsToTp2;
                case "TP3" -> record.barsToTp3;
                case "STOP" -> record.barsToStop;
                default -> 0L;
            };
            if (bars > 0L) {
                sum += bars;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static void exportEvidence(Report report) {
        List<BacktestEvidenceStore.EtaSample> samples = new ArrayList<>();
        for (TradeRecord record : report.records) {
            if (record == null || record.symbol == null || record.symbol.isBlank()) continue;
            String interval = record.ltfInterval;
            if (record.barsToTp1 > 0) samples.add(new BacktestEvidenceStore.EtaSample(
                    record.symbol, record.market, "LONG".equals(record.direction), interval, "TP1", record.barsToTp1));
            if (record.barsToTp2 > 0) samples.add(new BacktestEvidenceStore.EtaSample(
                    record.symbol, record.market, "LONG".equals(record.direction), interval, "TP2", record.barsToTp2));
            if (record.barsToTp3 > 0) samples.add(new BacktestEvidenceStore.EtaSample(
                    record.symbol, record.market, "LONG".equals(record.direction), interval, "TP3", record.barsToTp3));
            if (record.barsToStop > 0) samples.add(new BacktestEvidenceStore.EtaSample(
                    record.symbol, record.market, "LONG".equals(record.direction), interval, "SL", record.barsToStop));
        }
        BacktestEvidenceStore.record(samples);
    }

    private static void exportCsv(Report report, String symbol, BinanceClient.Market market,
                                  BacktestAssumptions assumptions) {
        if (report.records.isEmpty()) return;
        try {
            Path dir = ProjectPaths.backtestsDir();
            Files.createDirectories(dir);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.now());
            String fileName = symbol.toUpperCase(Locale.ROOT)
                    + "_" + market.label.toLowerCase(Locale.ROOT)
                    + "_score" + assumptions.minConfidenceScore
                    + (assumptions.requireHtfDisplacementAligned ? "_htfdisp" : "")
                    + "_" + stamp + ".csv";
            Path path = dir.resolve(fileName);
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write("symbol,market,ltf_interval,outcome,direction,setup_type,target_model,market_regime,"
                        + "deviation_type,deviation_score,deviation_returned_inside,deviation_displacement,"
                        + "imbalance_direction,imbalance_score,event_risk_score,event_risk_blocked,"
                        + "zone_type,zone_quality,session,phase,killzone,"
                        + "sweep_confirmed,entry_confirmed,htf_displacement_aligned,order_flow_score,smc_score,zone_score,"
                        + "displacement_score,liquidity_score,volume_score,htf_strength,ltf_strength,"
                        + "signal_time,entry_time,exit_time,bars_to_tp1,bars_to_tp2,bars_to_tp3,bars_to_stop,signal_price,theoretical_entry,entry,"
                        + "entry_impact_bps,entry_participation_pct,entry_slices,impact_r,"
                        + "stop,tp1,tp2,tp3,result_r,exit_reason");
                writer.newLine();
                for (TradeRecord rec : report.records) {
                    writer.write(csv(rec.symbol)); writer.write(',');
                    writer.write(csv(rec.market)); writer.write(',');
                    writer.write(csv(rec.ltfInterval)); writer.write(',');
                    writer.write(csv(rec.outcome)); writer.write(',');
                    writer.write(csv(rec.direction)); writer.write(',');
                    writer.write(csv(rec.setupType)); writer.write(',');
                    writer.write(csv(rec.targetModel)); writer.write(',');
                    writer.write(csv(rec.marketRegime)); writer.write(',');
                    writer.write(csv(rec.deviationType)); writer.write(',');
                    writer.write(Integer.toString(rec.deviationScore)); writer.write(',');
                    writer.write(Boolean.toString(rec.deviationReturnedInside)); writer.write(',');
                    writer.write(Boolean.toString(rec.deviationDisplacement)); writer.write(',');
                    writer.write(csv(rec.imbalanceDirection)); writer.write(',');
                    writer.write(Integer.toString(rec.imbalanceScore)); writer.write(',');
                    writer.write(Integer.toString(rec.eventRiskScore)); writer.write(',');
                    writer.write(Boolean.toString(rec.eventRiskBlocked)); writer.write(',');
                    writer.write(csv(rec.zoneType)); writer.write(',');
                    writer.write(csv(rec.zoneQuality)); writer.write(',');
                    writer.write(csv(rec.session)); writer.write(',');
                    writer.write(csv(rec.phase)); writer.write(',');
                    writer.write(Boolean.toString(rec.killzone)); writer.write(',');
                    writer.write(Boolean.toString(rec.sweepConfirmed)); writer.write(',');
                    writer.write(Boolean.toString(rec.entryConfirmed)); writer.write(',');
                    writer.write(Boolean.toString(rec.htfDisplacementAligned)); writer.write(',');
                    writer.write(Integer.toString(rec.orderFlowScore)); writer.write(',');
                    writer.write(Integer.toString(rec.smcScore)); writer.write(',');
                    writer.write(Integer.toString(rec.zoneScore)); writer.write(',');
                    writer.write(Integer.toString(rec.displacementScore)); writer.write(',');
                    writer.write(Integer.toString(rec.liquidityScore)); writer.write(',');
                    writer.write(Integer.toString(rec.volumeScore)); writer.write(',');
                    writer.write(Integer.toString(rec.htfStrength)); writer.write(',');
                    writer.write(Integer.toString(rec.ltfStrength)); writer.write(',');
                    writer.write(Long.toString(rec.signalTime)); writer.write(',');
                    writer.write(Long.toString(rec.entryTime)); writer.write(',');
                    writer.write(Long.toString(rec.exitTime)); writer.write(',');
                    writer.write(Long.toString(rec.barsToTp1)); writer.write(',');
                    writer.write(Long.toString(rec.barsToTp2)); writer.write(',');
                    writer.write(Long.toString(rec.barsToTp3)); writer.write(',');
                    writer.write(Long.toString(rec.barsToStop)); writer.write(',');
                    writer.write(Double.toString(rec.signalPrice)); writer.write(',');
                    writer.write(Double.toString(rec.theoreticalEntry)); writer.write(',');
                    writer.write(Double.toString(rec.entry)); writer.write(',');
                    writer.write(Double.toString(rec.entryImpactBps)); writer.write(',');
                    writer.write(Double.toString(rec.entryParticipationPct)); writer.write(',');
                    writer.write(Integer.toString(rec.entrySlices)); writer.write(',');
                    writer.write(Double.toString(rec.impactR)); writer.write(',');
                    writer.write(Double.toString(rec.stop)); writer.write(',');
                    writer.write(Double.toString(rec.tp1)); writer.write(',');
                    writer.write(Double.toString(rec.tp2)); writer.write(',');
                    writer.write(Double.toString(rec.tp3)); writer.write(',');
                    writer.write(Double.toString(rec.resultR)); writer.write(',');
                    writer.write(csv(rec.exitReason));
                    writer.newLine();
                }
            }
            report.csvPath = path.toString();
            report.csvError = "";
        } catch (Exception ex) {
            report.csvPath = "";
            report.csvError = ex.getClass().getSimpleName()
                    + (ex.getMessage() == null || ex.getMessage().isBlank() ? "" : ": " + ex.getMessage());
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n");
        String escaped = value.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private static class PricePoint {
        final long time;
        final double price;

        PricePoint(long time, double price) {
            this.time = time;
            this.price = price;
        }
    }

    private static class PricePath {
        final List<PricePoint> points;
        final boolean tickBased;

        PricePath(List<PricePoint> points, boolean tickBased) {
            this.points = points;
            this.tickBased = tickBased;
        }
    }

    private static class EntryResult {
        Position position;
        Position closedPosition;
        Double closedR;
        boolean missed;
    }
}
