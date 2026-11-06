package app.logic;

import app.common.Direction;
import app.common.EngineConfig;
import app.common.ScoreUtils;
import app.derivatives.DerivativesContext;
import app.derivatives.DerivativesEngine;
import app.deviation.DeviationContext;
import app.deviation.DeviationEngine;
import app.fundamental.EventRiskContext;
import app.fundamental.EventRiskEngine;
import app.imbalance.ImbalanceContext;
import app.imbalance.ImbalanceEngine;
import app.displacement.DisplacementContext;
import app.displacement.DisplacementEngine;
import app.datalake.LocalDataLake;
import app.execution.MultiTimeframeContext;
import app.execution.MultiTimeframeEngine;
import app.execution.SmartEntryEngine;
import app.fvg.FvgEngine;
import app.learning.AdaptiveFactorWeights;
import app.learning.FactorSignals;
import app.learning.FactorWeightOptimizer;
import app.liquidity.LiquidityEngine;
import app.liquidity.LiquidityMap;
import app.model.AnalysisResult;
import app.model.Candle;
import app.model.TradePlan;
import app.model.Zone;
import app.orderbook.OrderBookContext;
import app.orderbook.OrderBookEngine;
import app.orderflow.FootprintCandle;
import app.orderflow.OrderFlowContext;
import app.orderflow.OrderFlowEngine;
import app.orderblock.OrderBlockEngine;
import app.backtest.Tick;
import app.backtest.TickCache;
import app.regime.MarketRegime;
import app.regime.MarketRegimeEngine;
import app.sessions.SessionContext;
import app.sessions.SessionEngine;
import app.structure.MarketStructure;
import app.structure.StructureEngine;
import app.volatility.MarketMetrics;
import app.volume.VolumeContext;
import app.volume.VolumeEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SMCAnalyzer {
    private static final EngineConfig CONFIG = EngineConfig.defaults();
    private static final int LIVE_ORDER_FLOW_TICK_PAGES = 4;

    public static AnalysisResult analyze(String symbol, String ltfInterval,
                                         List<Candle> htf, List<Candle> ltf,
                                         String trendMethod) {
        return analyze(symbol, ltfInterval, htf, ltf, trendMethod, BinanceClient.Market.FUTURES, true);
    }

    public static AnalysisResult analyze(String symbol, String ltfInterval,
                                         List<Candle> htf, List<Candle> ltf,
                                         String trendMethod,
                                         BinanceClient.Market market) {
        return analyze(symbol, ltfInterval, htf, ltf, trendMethod, market, true);
    }

    public static AnalysisResult analyze(String symbol, String ltfInterval,
                                         List<Candle> htf, List<Candle> ltf,
                                         String trendMethod,
                                         boolean includeExternalContext) {
        return analyze(symbol, ltfInterval, htf, ltf, trendMethod, BinanceClient.Market.FUTURES,
                includeExternalContext);
    }

    public static AnalysisResult analyze(String symbol, String ltfInterval,
                                         List<Candle> htf, List<Candle> ltf,
                                         String trendMethod,
                                         BinanceClient.Market market,
                                         boolean includeExternalContext) {
        return analyze(symbol, ltfInterval, htf, ltf, trendMethod, market, includeExternalContext,
                includeExternalContext);
    }

    public static AnalysisResult analyze(String symbol, String ltfInterval,
                                         List<Candle> htf, List<Candle> ltf,
                                         String trendMethod,
                                         BinanceClient.Market market,
                                         boolean includeExternalContext,
                                         boolean allowLiveTickFetch) {
        return analyze(symbol, ltfInterval, htf, ltf, trendMethod, market, includeExternalContext,
                allowLiveTickFetch, null, null);
    }

    /**
     * Backtest-replay entry point: accepts derivatives/order-book context already loaded from
     * the local historical data lake (see Backtester -> DerivativesEngine.analyzeHistorical /
     * OrderBookEngine.analyzeHistorical) instead of always skipping these signals during replay.
     * When both are null this behaves identically to the overload above. `includeExternalContext`
     * still controls only the LIVE-fetch/ML-bridge paths -- passing historical context here never
     * triggers a live network call, so backtests stay fast and offline even when replaying with
     * real historical DOM/derivatives evidence.
     */
    public static AnalysisResult analyze(String symbol, String ltfInterval,
                                         List<Candle> htf, List<Candle> ltf,
                                         String trendMethod,
                                         BinanceClient.Market market,
                                         boolean includeExternalContext,
                                         boolean allowLiveTickFetch,
                                         DerivativesContext historicalDerivatives,
                                         OrderBookContext historicalOrderBook) {
        if (ltf == null || ltf.size() < 40) {
            throw new IllegalArgumentException("Need at least 40 LTF candles for institutional SMC analysis");
        }
        if (htf == null || htf.size() < 30) {
            throw new IllegalArgumentException("Need at least 30 HTF candles for bias analysis");
        }

        double currentPrice = ltf.get(ltf.size() - 1).close;
        double highest = currentPrice;
        double lowest = currentPrice;
        int skipCount = Math.max(0, ltf.size() - 80);
        for (int idx = skipCount; idx < ltf.size(); idx++) {
            Candle c = ltf.get(idx);
            if (c.high > highest) highest = c.high;
            if (c.low < lowest) lowest = c.low;
        }
        double equilibrium = lowest + (highest - lowest) * 0.5;

        MarketMetrics htfMetrics = MarketMetrics.compute(htf, CONFIG.atrPeriod);
        MarketMetrics ltfMetrics = MarketMetrics.compute(ltf, CONFIG.atrPeriod);

        VolumeEngine volumeEngine = new VolumeEngine();
        VolumeContext htfVolume = volumeEngine.analyze(htf, htfMetrics);
        VolumeContext ltfVolume = volumeEngine.analyze(ltf, ltfMetrics);
        BinanceClient.Market contextMarket = market == null ? BinanceClient.Market.FUTURES : market;
        OrderFlowContext orderFlow = resolveOrderFlow(symbol, ltf, ltfMetrics, contextMarket, allowLiveTickFetch);

        DisplacementEngine displacementEngine = new DisplacementEngine(CONFIG);
        DisplacementContext htfDisplacement = displacementEngine.analyze(htf, htfMetrics, htfVolume);
        DisplacementContext ltfDisplacement = displacementEngine.analyze(ltf, ltfMetrics, ltfVolume);

        StructureEngine structureEngine = new StructureEngine(CONFIG);
        MarketStructure htfStructure = structureEngine.analyze(htf, htfMetrics, htfDisplacement, trendMethod);
        MarketStructure ltfStructure = structureEngine.analyze(ltf, ltfMetrics, ltfDisplacement, trendMethod);

        MultiTimeframeContext mtf = new MultiTimeframeEngine().analyze(htfStructure, ltfStructure);
        Direction htfBias = mtf.htfBias == Direction.NEUTRAL ? htfStructure.bias : mtf.htfBias;
        boolean htfBullish = htfBias.bullish();

        SessionContext session = new SessionEngine().analyze(ltf);
        MarketRegime regime = new MarketRegimeEngine().analyze(ltf, ltfMetrics);
        LiquidityMap liquidityMap = new LiquidityEngine(CONFIG).analyze(
                ltf, htf, ltfMetrics, ltfStructure, htfStructure, ltfDisplacement, ltfVolume, session);
        DeviationContext deviation = new DeviationEngine().analyze(ltf, ltfMetrics, liquidityMap, session);

        List<Zone> zones = new ArrayList<>();
        zones.addAll(new FvgEngine(CONFIG).find(ltf, ltfMetrics, ltfDisplacement, ltfVolume, htfBias));
        zones.addAll(new OrderBlockEngine(CONFIG).find(ltf, ltfMetrics, ltfDisplacement, ltfVolume, ltfStructure, htfBias));
        enrichZones(zones, liquidityMap, ltfMetrics, htfBias, orderFlow);
        markMitigation(ltf, zones, ltfMetrics);
        zones = new SmartMoneyMechanicsEngine().enrich(ltf, zones, liquidityMap, ltfStructure, htfStructure,
                ltfDisplacement.events(), ltfMetrics, htfBias, equilibrium);
        markMitigation(ltf, zones, ltfMetrics);
        zones = limitZones(mergeZones(zones));

        TradePlan plan = new SmartEntryEngine(CONFIG).build(
                ltf, zones, ltfMetrics, liquidityMap, ltfStructure, mtf, session, regime);

        int smcScore = plan == null ? scoreWatchlist(htfStructure, ltfStructure, liquidityMap, ltfMetrics) : plan.confidence;
        List<String> factors = new ArrayList<>(plan == null
                ? watchlistFactors(htfStructure, ltfStructure, liquidityMap)
                : plan.factors);
        List<String> warnings = new ArrayList<>(plan == null
                ? watchlistWarnings(liquidityMap, ltfMetrics)
                : plan.warnings);
        addOrderFlowNarrative(plan, htfBias, orderFlow, factors, warnings);
        applyDeviationNarrative(plan, deviation, factors, warnings);
        // historicalDerivatives/historicalOrderBook (backtest replay) take priority over a live
        // fetch; includeExternalContext still gates ONLY the live-network paths below, so replay
        // never makes a live call just because real historical evidence happened to be available.
        DerivativesContext derivatives = historicalDerivatives != null ? historicalDerivatives
                : includeExternalContext ? new DerivativesEngine().analyze(symbol, contextMarket) : null;
        OrderBookContext orderBook = historicalOrderBook != null ? historicalOrderBook
                : includeExternalContext ? new OrderBookEngine().analyze(symbol, currentPrice, contextMarket) : null;
        boolean haveExternalContext = includeExternalContext || derivatives != null || orderBook != null;
        ImbalanceContext imbalance = new ImbalanceEngine().analyze(ltf, zones, ltfMetrics, orderFlow, orderBook, liquidityMap);
        EventRiskContext eventRisk = new EventRiskEngine().analyze(ltf, ltfMetrics, derivatives);
        applyImbalanceNarrative(plan, imbalance, factors, warnings);
        applyEventRiskNarrative(eventRisk, factors, warnings);
        FactorWeightOptimizer optimizer = new FactorWeightOptimizer();
        // The ML bridge request is a live network call in its own right -- keep it strictly on
        // includeExternalContext, not on whether historical replay evidence happened to load.
        AdaptiveFactorWeights weights = includeExternalContext
                ? optimizer.loadOrRequest(symbol, smcScore, derivatives, orderBook, regime)
                : optimizer.load(symbol);
        smcScore = applyDeviationScore(smcScore, plan, deviation, factors, warnings);
        smcScore = applyImbalanceScore(smcScore, plan, imbalance, factors, warnings);
        smcScore = applyEventRiskScore(smcScore, eventRisk, warnings);
        FactorSignals factorSignals = buildFactorSignals(plan, orderFlow, htfBias, regime);
        if (haveExternalContext) {
            smcScore = applyExternalContext(smcScore, plan, currentPrice, ltfMetrics, htfBias,
                    derivatives, orderBook, weights, factorSignals, regime, factors, warnings);
        } else if (weights != null) {
            smcScore = weights.apply(smcScore, factorSignals, regime);
        }
        String narrative = mtf.narrative + " Session: " + session.activeSession + " / " + session.phase
                + ". Regime: " + regime.label() + " (" + regime.score + "/100).";

        return new AnalysisResult(symbol, ltfInterval, ltf, currentPrice, htfBullish, zones, plan,
                equilibrium, highest, lowest, liquidityMap, htfStructure, ltfStructure,
                htfDisplacement.events(), ltfDisplacement.events(), ltfStructure.events,
                liquidityMap.sweeps, liquidityMap.inducements,
                session, regime, deviation, imbalance, eventRisk, orderFlow, derivatives, orderBook, weights,
                smcScore, factors, warnings, narrative);
    }

    private static void applyDeviationNarrative(TradePlan plan, DeviationContext deviation,
                                                List<String> factors, List<String> warnings) {
        if (deviation == null || !deviation.available) {
            warnings.add("No confirmed deviation trap");
            return;
        }
        factors.add("Deviation: " + deviation.label + " score " + deviation.score);
        if (deviation.returnedInside) factors.add("Deviation returned inside range");
        if (deviation.displacementAfterReturn) factors.add("Displacement after deviation return");
        if (deviation.liquiditySweep) factors.add("Deviation swept liquidity");
        if (plan != null && !deviation.supports(plan.longSide)) {
            warnings.add("Deviation direction conflicts with trade");
        }
        if (deviation.atrMultiple > 2.8 && !deviation.returnedInside) {
            warnings.add("Deviation may be real breakout, not stop-hunt");
        }
    }

    private static int applyDeviationScore(int score, TradePlan plan, DeviationContext deviation,
                                           List<String> factors, List<String> warnings) {
        if (deviation == null || !deviation.available) return score;
        int adjusted = score;
        if (plan != null) {
            if (deviation.supports(plan.longSide)) {
                int boost = deviation.strongTrap() ? 8 : deviation.score >= 55 ? 4 : 2;
                adjusted += boost;
                factors.add("Smart-money deviation confluence +" + boost);
            } else {
                adjusted -= deviation.score >= 60 ? 8 : 4;
                warnings.add("Deviation trap points opposite to plan");
            }
        } else if (deviation.strongTrap()) {
            adjusted += 4;
            factors.add("Watchlist deviation trap");
        }
        return Math.max(0, Math.min(100, adjusted));
    }

    private static void applyImbalanceNarrative(TradePlan plan, ImbalanceContext imbalance,
                                                List<String> factors, List<String> warnings) {
        if (imbalance == null || !imbalance.available) {
            warnings.add("No clear imbalance confluence");
            return;
        }
        factors.add("Imbalance: " + imbalance.label + " score " + imbalance.score);
        if (imbalance.fvgEfficiencyScore >= 60) factors.add("FVG imbalance efficiency " + imbalance.fvgEfficiencyScore);
        if (imbalance.deltaImbalanceScore >= 60) factors.add("Delta imbalance " + imbalance.deltaImbalanceScore);
        if (imbalance.bookImbalanceScore >= 60) factors.add("DOM imbalance " + imbalance.bookImbalanceScore);
        if (plan != null && !imbalance.supports(plan.longSide)) warnings.add("Imbalance direction conflicts with trade");
    }

    private static int applyImbalanceScore(int score, TradePlan plan, ImbalanceContext imbalance,
                                           List<String> factors, List<String> warnings) {
        if (imbalance == null || !imbalance.available) return score;
        int adjusted = score;
        if (plan != null && imbalance.supports(plan.longSide)) {
            int boost = imbalance.strong() ? 7 : imbalance.score >= 52 ? 4 : 2;
            adjusted += boost;
            factors.add("Imbalance confluence +" + boost);
        } else if (plan != null && imbalance.score >= 50) {
            adjusted -= imbalance.score >= 70 ? 9 : 5;
            warnings.add("Imbalance leans opposite to plan");
        }
        return Math.max(0, Math.min(100, adjusted));
    }

    private static void applyEventRiskNarrative(EventRiskContext eventRisk,
                                                List<String> factors, List<String> warnings) {
        if (eventRisk == null || !eventRisk.available) return;
        if (eventRisk.score >= 55) warnings.add("Event risk: " + eventRisk.message);
        else factors.add("Event risk normal: " + eventRisk.score + "/100");
    }

    /**
     * Builds typed factor evidence for AdaptiveFactorWeights.apply directly from the same
     * context objects the narrative text is generated from — see FactorSignals' class doc.
     */
    private static FactorSignals buildFactorSignals(TradePlan plan, OrderFlowContext orderFlow,
                                                     Direction htfBias, MarketRegime regime) {
        Zone zone = plan == null ? null : plan.zone;
        boolean longSide = plan == null ? htfBias.bullish() : plan.longSide;
        int displacementScore = zone == null ? 0 : zone.displacementScore;
        int volumeScore = zone == null ? 0 : zone.volumeScore;
        int liquidityScore = zone == null ? 0 : zone.liquidityScore;
        boolean sweepConfirmed = (plan != null && plan.sweepIndex >= 0) || (zone != null && zone.sweepConfirmed);
        boolean orderFlowSupports = orderFlow != null && orderFlow.supportsDirection(longSide);
        // Tick-backed footprint is real executed-flow evidence; the OHLCV-proxy fallback (no
        // tick data available) is a shape-based estimate, not real order flow — worth less.
        int orderFlowScore = orderFlow == null ? 0 : (orderFlow.tickBased ? 70 : 40);
        boolean choppy = regime != null && regime.choppy();
        return new FactorSignals(displacementScore, volumeScore, liquidityScore, sweepConfirmed,
                orderFlowScore, orderFlowSupports, choppy);
    }

    private static int applyEventRiskScore(int score, EventRiskContext eventRisk, List<String> warnings) {
        if (eventRisk == null || !eventRisk.available) return score;
        if (eventRisk.blocksNewRisk()) {
            warnings.add("High-impact event risk blocks aggressive entries");
            return Math.min(score - 18, 72);
        }
        if (eventRisk.score >= 55) return Math.max(0, score - 8);
        return score;
    }

    private static OrderFlowContext resolveOrderFlow(String symbol, List<Candle> candles,
                                                     MarketMetrics metrics,
                                                     BinanceClient.Market market,
                                                     boolean allowLiveTickFetch) {
        OrderFlowEngine engine = new OrderFlowEngine();
        if (candles == null || candles.isEmpty()) return engine.analyzeFromCandles(candles, metrics);
        int from = Math.max(0, candles.size() - 180);
        long start = candles.get(from).time;
        long end = candleEndTime(candles, candles.size() - 1);
        List<Tick> ticks = new ArrayList<>();
        try {
            LocalDataLake.TickLoadResult lake = LocalDataLake.loadAggTradeTicks(
                    symbol, market == null ? BinanceClient.Market.FUTURES.label : market.label, start, end);
            if (lake.hit && !lake.ticks.isEmpty()) {
                ticks.addAll(lake.ticks);
            }
        } catch (Exception ignored) {
        }
        OrderFlowContext context = ticks.isEmpty()
                ? engine.analyzeFromCandles(candles, metrics)
                : engine.analyzeFromTicks(candles, sortedUniqueTicks(ticks), metrics == null ? 0.00000001 : metrics.minTick());
        if (context.latestTickBacked || !allowLiveTickFetch) return context;

        List<Tick> liveTicks = fetchLatestCandleTicks(symbol, candles, market);
        if (liveTicks.isEmpty()) return context;
        ticks.addAll(liveTicks);
        return engine.analyzeFromTicks(candles, sortedUniqueTicks(ticks), metrics == null ? 0.00000001 : metrics.minTick());
    }

    private static List<Tick> fetchLatestCandleTicks(String symbol, List<Candle> candles, BinanceClient.Market market) {
        if (symbol == null || symbol.isBlank() || candles == null || candles.size() < 2) return List.of();
        int lastIndex = candles.size() - 1;
        long candleStep = Math.max(0L, candles.get(lastIndex).time - candles.get(lastIndex - 1).time);
        if (candleStep <= 0L || candleStep > 3_600_000L) return List.of();
        long start = candles.get(lastIndex).time;
        long candleEnd = candleEndTime(candles, lastIndex);
        long now = System.currentTimeMillis();
        long end = Math.min(candleEnd, now);
        if (end <= start) return List.of();
        BinanceClient.Market contextMarket = market == null ? BinanceClient.Market.FUTURES : market;
        try {
            if (end < candleEnd) {
                return BinanceClient.fetchAggTrades(symbol, start, end, contextMarket, LIVE_ORDER_FLOW_TICK_PAGES);
            }
            return TickCache.loadOrFetch(symbol, start, end, contextMarket, LIVE_ORDER_FLOW_TICK_PAGES,
                    () -> BinanceClient.fetchAggTrades(symbol, start, end, contextMarket, LIVE_ORDER_FLOW_TICK_PAGES)).ticks;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<Tick> sortedUniqueTicks(List<Tick> ticks) {
        if (ticks == null || ticks.isEmpty()) return List.of();
        ticks.sort(Comparator.comparingLong((Tick t) -> t.time).thenComparingLong(t -> t.aggregateId));
        List<Tick> unique = new ArrayList<>(ticks.size());
        Set<String> seen = new HashSet<>();
        for (Tick tick : ticks) {
            String key = tick.aggregateId + "|" + tick.time + "|" + tick.price;
            if (seen.add(key)) unique.add(tick);
        }
        return unique;
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

    private static int applyExternalContext(int score, TradePlan plan, double currentPrice,
                                            MarketMetrics metrics, Direction htfBias,
                                            DerivativesContext derivatives,
                                            OrderBookContext orderBook,
                                            AdaptiveFactorWeights weights,
                                            FactorSignals factorSignals,
                                            MarketRegime regime,
                                            List<String> factors,
                                            List<String> warnings) {
        boolean longSide = plan == null ? htfBias.bullish() : plan.longSide;
        boolean nearEntry = plan != null
                && Math.abs(currentPrice - plan.entry) <= Math.max(metrics.latestAtr() * 0.55, currentPrice * 0.0018);

        if (derivatives != null && derivatives.available) {
            int adjustment = derivatives.adjustment(longSide, nearEntry);
            score += adjustment;
            if (derivatives.supports(longSide, nearEntry)) {
                factors.add("Derivatives confluence: " + derivatives.message);
            } else if (derivatives.warns(nearEntry)) {
                warnings.add("Open interest flushed near entry; impulse fuel may be exhausted");
            } else {
                factors.add("Derivatives context: " + derivatives.message);
            }
        }

        if (orderBook != null && orderBook.available) {
            int adjustment = orderBook.adjustment(longSide);
            score += adjustment;
            if (adjustment > 0) {
                factors.add("DOM imbalance supports setup: " + orderBook.message);
            } else if (adjustment < 0) {
                warnings.add("DOM imbalance conflicts with setup: " + orderBook.message);
            } else if (orderBook.spoofingDetected) {
                factors.add("Spoofing heuristic detected: " + orderBook.spoofingSide);
            }
        }

        if (weights != null) {
            score = weights.apply(score, factorSignals, regime);
            factors.add(weights.summary());
        }
        return ScoreUtils.clamp(score, 0, 100);
    }

    private static void enrichZones(List<Zone> zones, LiquidityMap liquidityMap,
                                    MarketMetrics metrics, Direction htfBias,
                                    OrderFlowContext orderFlow) {
        for (Zone zone : zones) {
            Direction direction = zone.bullish ? Direction.BULLISH : Direction.BEARISH;
            var sweep = liquidityMap.latestSweep(direction);
            if (sweep != null && zone.creationIndex >= sweep.index - 4) {
                zone.sweepConfirmed = true;
                zone.liquidityScore = sweep.score;
                zone.relatedLiquidity = sweep.level.side + " " + sweep.level.label;
                zone.score += 12 + sweep.score / 10;
                zone.reasons.add("Liquidity sweep confirmed before zone");
            }
            if (direction == htfBias) {
                zone.score += 5;
            }
            FootprintCandle footprint = nearestFootprint(orderFlow, zone.creationIndex, 4);
            if (footprint != null) {
                int flowScore = footprint.score(zone.bullish);
                zone.volumeScore = Math.max(zone.volumeScore, flowScore);
                if ((zone.bullish && footprint.delta > 0.0) || (!zone.bullish && footprint.delta < 0.0)) {
                    zone.score += 4 + flowScore / 20;
                    zone.reasons.add("Order flow delta supports zone");
                } else if (footprint.deltaPercent > 0.34) {
                    zone.score -= 5;
                    zone.reasons.add("Order flow delta conflicts with zone");
                }
                if (footprint.absorption) {
                    zone.score += 3;
                    zone.reasons.add("Absorption footprint near zone");
                }
            }
            if (metrics.compressedVolatility(Math.max(0, zone.creationIndex - 1))
                    && zone.displacementScore < 55) {
                zone.score -= 8;
                zone.reasons.add("Filtered: weak expansion after chop");
            }
            zone.score = ScoreUtils.clamp(zone.score, 0, 100);
            zone.quality = zone.score >= 75 ? "strong" : zone.score >= 55 ? "medium" : "weak";
        }
    }

    private static FootprintCandle nearestFootprint(OrderFlowContext context, int index, int lookback) {
        if (context == null || context.candles.isEmpty()) return null;
        int from = Math.max(0, index - lookback);
        int to = Math.min(context.candles.size() - 1, index + lookback);
        FootprintCandle best = null;
        for (int i = from; i <= to; i++) {
            FootprintCandle fp = context.at(i);
            if (fp == null) continue;
            if (best == null || Math.abs(fp.delta) > Math.abs(best.delta)) best = fp;
        }
        return best;
    }

    private static void addOrderFlowNarrative(TradePlan plan, Direction htfBias,
                                              OrderFlowContext orderFlow,
                                              List<String> factors,
                                              List<String> warnings) {
        if (orderFlow == null || orderFlow.candles.isEmpty()) return;
        boolean bullishContext = plan == null ? htfBias.bullish() : plan.longSide;
        FootprintCandle latest = orderFlow.candles.get(orderFlow.candles.size() - 1);
        if (orderFlow.supportsDirection(bullishContext)) {
            factors.add("Order flow delta aligned: " + (int) Math.round(latest.deltaPercent * 100) + "%");
        } else if (latest.deltaPercent >= 0.22) {
            warnings.add("Order flow delta conflicts with setup");
        }
        if (latest.absorption) factors.add("Footprint absorption detected");
        if (orderFlow.tickBased) factors.add("Tick-based footprint data");
        else factors.add("OHLCV proxy footprint; tick data not loaded");
    }

    private static void markMitigation(List<Candle> candles, List<Zone> zones, MarketMetrics metrics) {
        for (Zone zone : zones) {
            double maxDepth = 0.0;
            for (int i = zone.creationIndex + 1; i < candles.size(); i++) {
                Candle c = candles.get(i);
                double atrBuffer = metrics.atrAt(i) * 0.04;
                if (zone.bullish) {
                    if (c.low <= zone.top) {
                        double depth = (zone.top - Math.max(c.low, zone.bottom)) / Math.max(zone.height(), metrics.minTick());
                        maxDepth = Math.max(maxDepth, Math.max(0.0, depth));
                    }
                    if (c.close < zone.bottom - atrBuffer) {
                        zone.mitigated = true;
                        zone.mitigatedIndex = i;
                        break;
                    }
                } else {
                    if (c.high >= zone.bottom) {
                        double depth = (Math.min(c.high, zone.top) - zone.bottom) / Math.max(zone.height(), metrics.minTick());
                        maxDepth = Math.max(maxDepth, Math.max(0.0, depth));
                    }
                    if (c.close > zone.top + atrBuffer) {
                        zone.mitigated = true;
                        zone.mitigatedIndex = i;
                        break;
                    }
                }
            }
            zone.mitigationDepth = Math.min(1.0, maxDepth);
            if (zone.mitigationDepth > 0.2 && !zone.mitigated) {
                String reason = "Mitigation in progress: " + (int) (zone.mitigationDepth * 100) + "%";
                if (!zone.reasons.contains(reason)) zone.reasons.add(reason);
            }
        }
    }

    private static List<Zone> mergeZones(List<Zone> zones) {
        zones.sort(Comparator.comparingInt(z -> z.creationIndex));
        List<Zone> result = new ArrayList<>();
        for (Zone zone : zones) {
            boolean merged = false;
            for (Zone existing : result) {
                boolean sameSideType = existing.bullish == zone.bullish && existing.type.equals(zone.type);
                boolean overlap = zone.bottom <= existing.top && zone.top >= existing.bottom;
                if (!sameSideType || !overlap) continue;

                existing.top = Math.max(existing.top, zone.top);
                existing.bottom = Math.min(existing.bottom, zone.bottom);
                existing.score = Math.max(existing.score, zone.score);
                existing.displacementScore = Math.max(existing.displacementScore, zone.displacementScore);
                existing.liquidityScore = Math.max(existing.liquidityScore, zone.liquidityScore);
                existing.structureScore = Math.max(existing.structureScore, zone.structureScore);
                existing.volumeScore = Math.max(existing.volumeScore, zone.volumeScore);
                mergeZoneMechanics(existing, zone);
                existing.sweepConfirmed |= zone.sweepConfirmed;
                existing.entryConfirmed |= zone.entryConfirmed;
                existing.institutional |= zone.institutional;
                existing.mitigated |= zone.mitigated;
                if (existing.mitigatedIndex < 0 || (zone.mitigatedIndex >= 0 && zone.mitigatedIndex < existing.mitigatedIndex)) {
                    existing.mitigatedIndex = zone.mitigatedIndex;
                }
                addUnique(existing.reasons, zone.reasons);
                merged = true;
                break;
            }
            if (!merged) result.add(zone);
        }
        return result;
    }

    private static void mergeZoneMechanics(Zone target, Zone source) {
        if (source == null || target == null) return;
        boolean sourceHasStrongerLabel = source.mechanic != null && !source.mechanic.isBlank()
                && (target.mechanic == null || target.mechanic.isBlank()
                || source.mechanicsScore > target.mechanicsScore);
        if (sourceHasStrongerLabel) {
            target.mechanic = source.mechanic;
            target.setupModel = source.setupModel;
        }
        if ((target.targetLiquidity == null || target.targetLiquidity.isBlank())
                && source.targetLiquidity != null && !source.targetLiquidity.isBlank()) {
            target.targetLiquidity = source.targetLiquidity;
        }
        target.mechanicsScore = Math.max(target.mechanicsScore, source.mechanicsScore);
        target.inverted |= source.inverted;
        target.breakerBlock |= source.breakerBlock;
        target.mitigationBlock |= source.mitigationBlock;
        target.propulsionBlock |= source.propulsionBlock;
        target.premiumDiscountAligned |= source.premiumDiscountAligned;
        target.liquidityToLiquidity |= source.liquidityToLiquidity;
        target.highQualityModel |= source.highQualityModel;
    }

    private static List<Zone> limitZones(List<Zone> zones) {
        zones.sort(Comparator.comparingInt((Zone z) -> z.mitigated ? 0 : 1).reversed()
                .thenComparingInt((Zone z) -> z.score).reversed()
                .thenComparingInt(z -> z.creationIndex).reversed());
        if (zones.size() <= CONFIG.maxActiveZones) return zones;
        return new ArrayList<>(zones.subList(0, CONFIG.maxActiveZones));
    }

    private static void addUnique(List<String> target, List<String> source) {
        Set<String> seen = new LinkedHashSet<>(target);
        for (String item : source) {
            if (seen.add(item)) target.add(item);
        }
    }

    private static int scoreWatchlist(MarketStructure htf, MarketStructure ltf,
                                      LiquidityMap liquidityMap, MarketMetrics metrics) {
        int score = 20 + htf.strength / 4 + ltf.strength / 5;
        if (!liquidityMap.sweeps.isEmpty()) score += liquidityMap.sweeps.get(liquidityMap.sweeps.size() - 1).score / 4;
        if (!metrics.compressedVolatility(metrics.size() - 1)) score += 8;
        return ScoreUtils.clamp(score, 0, 100);
    }

    private static List<String> watchlistFactors(MarketStructure htf, MarketStructure ltf,
                                                 LiquidityMap liquidityMap) {
        List<String> factors = new ArrayList<>();
        factors.add("HTF bias: " + htf.bias.label() + " (" + htf.strength + "/100)");
        factors.add("LTF structure: " + ltf.bias.label() + " (" + ltf.strength + "/100)");
        if (!liquidityMap.sweeps.isEmpty()) {
            factors.add("Recent liquidity sweep detected");
        }
        return factors;
    }

    private static List<String> watchlistWarnings(LiquidityMap liquidityMap, MarketMetrics metrics) {
        List<String> warnings = new ArrayList<>();
        if (liquidityMap.sweeps.isEmpty()) warnings.add("No confirmed sweep yet");
        if (metrics.compressedVolatility(metrics.size() - 1)) warnings.add("Low volatility chop filter active");
        return warnings;
    }
}
