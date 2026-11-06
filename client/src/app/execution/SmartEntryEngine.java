package app.execution;

import app.common.Direction;
import app.common.EngineConfig;
import app.common.ScoreUtils;
import app.liquidity.LiquidityLevel;
import app.liquidity.LiquidityMap;
import app.liquidity.LiquiditySweep;
import app.model.Candle;
import app.model.TradePlan;
import app.model.Zone;
import app.regime.MarketRegime;
import app.scoring.ScoringEngine;
import app.scoring.SmcScore;
import app.sessions.SessionContext;
import app.structure.MarketStructure;
import app.structure.StructureEvent;
import app.volatility.MarketMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SmartEntryEngine {
    private final EngineConfig config;
    private final ScoringEngine scoring = new ScoringEngine();

    public SmartEntryEngine(EngineConfig config) {
        this.config = config;
    }

    public TradePlan build(List<Candle> candles, List<Zone> zones,
                           MarketMetrics metrics, LiquidityMap liquidity,
                           MarketStructure ltfStructure,
                           MultiTimeframeContext mtf,
                           SessionContext session,
                           MarketRegime regime) {
        double currentPrice = candles.get(candles.size() - 1).close;
        Direction preferred = mtf.htfBias == Direction.NEUTRAL ? ltfStructure.bias : mtf.htfBias;
        if (preferred == Direction.NEUTRAL) return null;

        LiquiditySweep sweep = liquidity.latestSweep(preferred);
        StructureEvent choch = freshestStructure(ltfStructure, preferred);
        Zone zone = bestZone(candles, zones, currentPrice, preferred, sweep, choch, metrics);
        if (zone == null) return null;

        boolean hasSweep = sweep != null && sweep.index >= candles.size() - config.entryLookbackBars;
        boolean hasStructure = choch != null && choch.index >= zone.creationIndex - 5;
        boolean mitigation = isMitigatingRecent(candles, zone, preferred, 3);
        boolean entryConfirmed = hasSweep && hasStructure && mitigation && zone.displacementScore >= 45;
        boolean continuationConfirmed = !hasSweep && mtf.aligned && hasStructure
                && zone.displacementScore >= 62 && zone.score >= 72;
        if (!entryConfirmed && !continuationConfirmed) return null;
        if (regime != null && regime.choppy() && !entryConfirmed && zone.score < 82) return null;

        zone.sweepConfirmed = hasSweep;
        zone.entryConfirmed = entryConfirmed;
        if (sweep != null) {
            zone.liquidityScore = sweep.score;
            zone.relatedLiquidity = sweep.level.label + " @ " + String.format("%.4f", sweep.level.price);
            zone.reasons.add("Sweep: " + zone.relatedLiquidity);
        }
        if (hasStructure) zone.reasons.add(choch.type + " after sweep/displacement");
        if (mitigation) zone.reasons.add("Price mitigating selected zone");

        SmcScore score = scoring.score(preferred, zone, mtf, liquidity, choch, metrics, session);
        int confidence = score.score;
        if (!entryConfirmed) confidence = Math.min(confidence, continuationConfirmed ? 72 : 68);
        if (!hasSweep) confidence = Math.min(confidence, 55);
        if (!hasStructure) confidence = Math.min(confidence, 62);
        if (regime != null && regime.choppy()) confidence = Math.min(confidence, 64);
        if (regime != null && regime.trendLike() && regime.direction == preferred) confidence += 5;
        if (zone.highQualityModel) confidence += 4;
        if (zone.breakerBlock || zone.propulsionBlock || zone.inverted) confidence += 3;
        if (regime != null && regime.trendLike() && regime.direction == preferred.opposite() && !hasSweep) {
            confidence = Math.min(confidence, 58);
        }
        if (!score.warnings.isEmpty()) confidence = Math.min(confidence, 94);
        if (zone.volumeScore < 35) confidence = Math.min(confidence, 90);
        if (zone.liquidityScore < 45 && hasSweep) confidence = Math.min(confidence, 96);
        if (zone.displacementScore < 60) confidence = Math.min(confidence, 82);
        if (zone.volumeScore < 25) confidence = Math.min(confidence, 78);
        if (zone.score < 65) confidence = Math.min(confidence, 78);
        if (zone.mitigationDepth > 0.92) confidence = Math.min(confidence, 82);
        if (sweep != null && !sweep.displacementConfirmed) confidence = Math.min(confidence, 86);
        if (confidence < 45) return null;

        String setupType = classifySetup(entryConfirmed, hasSweep, continuationConfirmed, zone, session, regime);
        LiquidityLevel target = liquidity.nearestUntouched(currentPrice, preferred.bullish());
        double liquidityTarget = validLiquidityTarget(currentPrice, preferred, target, metrics.latestAtr())
                ? target.price : Double.NaN;
        String targetLabel = target == null ? "" : target.side + " " + target.label;
        String scenario = entryConfirmed
                ? "Sweep -> displacement -> CHoCH -> mitigation entry"
                : "Continuation: aligned HTF/LTF BOS with strong displacement";
        if (zone.mechanic != null && !zone.mechanic.isBlank()) {
            scenario = zone.mechanic + ": " + scenario;
        }
        List<String> factors = new ArrayList<>(score.factors);
        List<String> warnings = new ArrayList<>(score.warnings);
        if (!Double.isNaN(liquidityTarget)) factors.add("Liquidity target: " + targetLabel);
        if (regime != null) {
            if (regime.choppy()) warnings.add("Market regime: chop");
            else factors.add("Market regime: " + regime.label());
        }
        return TradePlan.smart(preferred.bullish(), currentPrice, zone, metrics.latestAtr(), confidence,
                scenario, entryConfirmed, sweep == null ? -1 : sweep.index, choch == null ? -1 : choch.index,
                setupType, liquidityTarget, targetLabel, factors, warnings);
    }

    private String classifySetup(boolean entryConfirmed, boolean hasSweep, boolean continuationConfirmed,
                                 Zone zone, SessionContext session, MarketRegime regime) {
        if (zone != null && zone.setupModel != null && !zone.setupModel.isBlank()) return zone.setupModel;
        if (entryConfirmed && hasSweep && session != null && session.inKillzone) return "killzone_sweep_reversal";
        if (entryConfirmed && hasSweep) return "sweep_reversal";
        if (continuationConfirmed) return "continuation_pullback";
        if ("FVG".equals(zone.type)) return "fvg_mitigation";
        if ("OB".equals(zone.type)) return "ob_mitigation";
        if (regime != null && regime.choppy()) return "range_reaction";
        return "smart_money_entry";
    }

    private boolean validLiquidityTarget(double currentPrice, Direction direction, LiquidityLevel target, double atr) {
        if (target == null || target.swept) return false;
        double minDistance = Math.max(atr * 0.65, currentPrice * 0.001);
        if (direction.bullish()) return target.buySide() && target.price > currentPrice + minDistance;
        return target.sellSide() && target.price < currentPrice - minDistance;
    }

    private Zone bestZone(List<Candle> candles, List<Zone> zones, double currentPrice,
                          Direction direction, LiquiditySweep sweep, StructureEvent structureEvent,
                          MarketMetrics metrics) {
        int minIndex = Math.max(0, candles.size() - config.entryLookbackBars);
        return zones.stream()
                .filter(z -> !z.mitigated)
                .filter(z -> z.bullish == direction.bullish())
                .filter(z -> z.creationIndex >= minIndex || z.score >= 70)
                .filter(z -> direction.bullish() ? z.top <= currentPrice + metrics.latestAtr() * 0.9
                        : z.bottom >= currentPrice - metrics.latestAtr() * 0.9)
                .max(Comparator.comparingInt(z -> adjustedScore(z, sweep, structureEvent, currentPrice, metrics)))
                .orElse(null);
    }

    private int adjustedScore(Zone zone, LiquiditySweep sweep, StructureEvent structureEvent,
                              double currentPrice, MarketMetrics metrics) {
        int score = zone.score;
        if (sweep != null && zone.creationIndex >= sweep.index - 3) score += 18;
        if (structureEvent != null && Math.abs(zone.creationIndex - structureEvent.index) <= 8) score += 12;
        double distance = Math.abs(zone.mid() - currentPrice) / metrics.latestAtr();
        score += ScoreUtils.scale(4.0 - distance, 0.0, 4.0, 12);
        score += Math.max(-6, Math.min(18, zone.mechanicsScore / 2));
        if (zone.highQualityModel) score += 8;
        if (zone.breakerBlock || zone.propulsionBlock || zone.inverted) score += 5;
        if (zone.liquidityToLiquidity) score += 4;
        return score;
    }

    private StructureEvent freshestStructure(MarketStructure structure, Direction direction) {
        for (int i = structure.events.size() - 1; i >= 0; i--) {
            StructureEvent event = structure.events.get(i);
            if (event.direction == direction && event.score >= 48) return event;
        }
        return null;
    }

    private boolean isMitigating(Candle current, Zone zone, Direction direction) {
        if (direction.bullish()) return current.low <= zone.top && current.close >= zone.bottom;
        return current.high >= zone.bottom && current.close <= zone.top;
    }

    /** Проверяет митигацию по последним lookback свечам (не только по текущей). */
    private boolean isMitigatingRecent(List<Candle> candles, Zone zone, Direction direction, int lookback) {
        int from = Math.max(0, candles.size() - lookback);
        for (int i = from; i < candles.size(); i++) {
            if (isMitigating(candles.get(i), zone, direction)) return true;
        }
        return false;
    }
}
