package app.model;

import app.displacement.DisplacementEvent;
import app.derivatives.DerivativesContext;
import app.deviation.DeviationContext;
import app.fundamental.EventRiskContext;
import app.imbalance.ImbalanceContext;
import app.liquidity.InducementEvent;
import app.liquidity.LiquidityMap;
import app.liquidity.LiquiditySweep;
import app.learning.AdaptiveFactorWeights;
import app.orderbook.OrderBookContext;
import app.orderflow.OrderFlowContext;
import app.regime.MarketRegime;
import app.sessions.SessionContext;
import app.structure.MarketStructure;
import app.structure.StructureEvent;

import java.util.Collections;
import java.util.List;

public class AnalysisResult {
    public final String symbol, ltfInterval;
    public final List<Candle> candles;
    public double currentPrice;
    public final boolean htfBullish;
    public final List<Zone> zones;
    public final TradePlan plan;
    public final double equilibrium;
    public final double structHigh, structLow;
    public final LiquidityMap liquidityMap;
    public final MarketStructure htfStructure;
    public final MarketStructure ltfStructure;
    public final List<DisplacementEvent> htfDisplacementEvents;
    public final List<DisplacementEvent> displacementEvents;
    public final List<StructureEvent> structureEvents;
    public final List<LiquiditySweep> sweeps;
    public final List<InducementEvent> inducements;
    public final SessionContext sessionContext;
    public final MarketRegime marketRegime;
    public final DeviationContext deviationContext;
    public final ImbalanceContext imbalanceContext;
    public final EventRiskContext eventRiskContext;
    public final OrderFlowContext orderFlowContext;
    public final DerivativesContext derivativesContext;
    public final OrderBookContext orderBookContext;
    public final AdaptiveFactorWeights adaptiveWeights;
    public final int smcScore;
    public final List<String> scoreFactors;
    public final List<String> warnings;
    public final String narrative;

    public AnalysisResult(String s, String i, List<Candle> c,
                          double p, boolean b, List<Zone> z,
                          TradePlan pl, double eq, double sh, double sl) {
        this(s, i, c, p, b, z, pl, eq, sh, sl, null, null, null,
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null, null, null, null, null, null, pl == null ? 0 : pl.score,
                Collections.emptyList(), Collections.emptyList(), "Legacy analysis");
    }

    public AnalysisResult(String s, String i, List<Candle> c,
                          double p, boolean b, List<Zone> z,
                          TradePlan pl, double eq, double sh, double sl,
                          LiquidityMap liquidityMap,
                          MarketStructure htfStructure,
                          MarketStructure ltfStructure,
                          List<DisplacementEvent> htfDisplacementEvents,
                          List<DisplacementEvent> displacementEvents,
                          List<StructureEvent> structureEvents,
                          List<LiquiditySweep> sweeps,
                          List<InducementEvent> inducements,
                          SessionContext sessionContext,
                          MarketRegime marketRegime,
                          DeviationContext deviationContext,
                          ImbalanceContext imbalanceContext,
                          EventRiskContext eventRiskContext,
                          OrderFlowContext orderFlowContext,
                          int smcScore,
                          List<String> scoreFactors,
                          List<String> warnings,
                          String narrative) {
        this(s, i, c, p, b, z, pl, eq, sh, sl, liquidityMap, htfStructure, ltfStructure,
                htfDisplacementEvents, displacementEvents, structureEvents, sweeps, inducements,
                sessionContext, marketRegime, deviationContext, imbalanceContext, eventRiskContext,
                orderFlowContext, null, null, null,
                smcScore, scoreFactors, warnings, narrative);
    }

    public AnalysisResult(String s, String i, List<Candle> c,
                          double p, boolean b, List<Zone> z,
                          TradePlan pl, double eq, double sh, double sl,
                          LiquidityMap liquidityMap,
                          MarketStructure htfStructure,
                          MarketStructure ltfStructure,
                          List<DisplacementEvent> htfDisplacementEvents,
                          List<DisplacementEvent> displacementEvents,
                          List<StructureEvent> structureEvents,
                          List<LiquiditySweep> sweeps,
                          List<InducementEvent> inducements,
                          SessionContext sessionContext,
                          MarketRegime marketRegime,
                          DeviationContext deviationContext,
                          ImbalanceContext imbalanceContext,
                          EventRiskContext eventRiskContext,
                          OrderFlowContext orderFlowContext,
                          DerivativesContext derivativesContext,
                          OrderBookContext orderBookContext,
                          AdaptiveFactorWeights adaptiveWeights,
                          int smcScore,
                          List<String> scoreFactors,
                          List<String> warnings,
                          String narrative) {
        symbol = s == null ? "" : s;
        ltfInterval = i == null ? "" : i;
        candles = c == null ? Collections.emptyList() : c;
        currentPrice = p;
        htfBullish = b;
        zones = z == null ? Collections.emptyList() : z;
        plan = pl; equilibrium = eq; structHigh = sh; structLow = sl;
        this.liquidityMap = liquidityMap;
        this.htfStructure = htfStructure;
        this.ltfStructure = ltfStructure;
        this.htfDisplacementEvents = htfDisplacementEvents == null ? Collections.emptyList() : htfDisplacementEvents;
        this.displacementEvents = displacementEvents == null ? Collections.emptyList() : displacementEvents;
        this.structureEvents = structureEvents == null ? Collections.emptyList() : structureEvents;
        this.sweeps = sweeps == null ? Collections.emptyList() : sweeps;
        this.inducements = inducements == null ? Collections.emptyList() : inducements;
        this.sessionContext = sessionContext;
        this.marketRegime = marketRegime;
        this.deviationContext = deviationContext == null ? DeviationContext.NONE : deviationContext;
        this.imbalanceContext = imbalanceContext == null ? ImbalanceContext.NONE : imbalanceContext;
        this.eventRiskContext = eventRiskContext == null ? EventRiskContext.NONE : eventRiskContext;
        this.orderFlowContext = orderFlowContext;
        this.derivativesContext = derivativesContext;
        this.orderBookContext = orderBookContext;
        this.adaptiveWeights = adaptiveWeights;
        this.smcScore = smcScore;
        this.scoreFactors = scoreFactors == null ? Collections.emptyList() : scoreFactors;
        this.warnings = warnings == null ? Collections.emptyList() : warnings;
        this.narrative = narrative == null ? "" : narrative;
    }
}
