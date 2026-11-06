package app.scoring;

import app.common.Direction;
import app.common.ScoreUtils;
import app.execution.MultiTimeframeContext;
import app.liquidity.LiquidityMap;
import app.liquidity.LiquiditySweep;
import app.model.Zone;
import app.sessions.SessionContext;
import app.structure.StructureEvent;
import app.volatility.MarketMetrics;

import java.util.ArrayList;
import java.util.List;

public class ScoringEngine {
    public SmcScore score(Direction direction, Zone zone, MultiTimeframeContext mtf,
                          LiquidityMap liquidity, StructureEvent structureEvent,
                          MarketMetrics metrics, SessionContext session) {
        List<String> factors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int score = 0;

        if (mtf.htfBias == direction) {
            score += 18;
            factors.add("HTF bias aligned");
        } else {
            score -= 10;
            warnings.add("HTF bias conflict");
        }

        LiquiditySweep sweep = liquidity.latestSweep(direction);
        if (sweep != null) {
            score += 22 + sweep.score / 8;
            factors.add((direction.bullish() ? "SSL" : "BSL") + " swept and reclaimed");
            if (sweep.displacementConfirmed) {
                score += 5;
                factors.add("Sweep confirmed by displacement");
            } else {
                warnings.add("Sweep lacks displacement follow-through");
            }
            if (sweep.score >= 75) {
                score += 4;
                factors.add("High-quality sweep: " + sweep.score + "/100");
            }
        } else {
            warnings.add("No recent liquidity sweep");
        }

        if (zone != null) {
            score += zone.score / 3;
            if (zone.displacementScore >= 55) factors.add("Institutional displacement: " + zone.displacementScore + "/100");
            if (zone.displacementScore >= 75) {
                score += 6;
                factors.add("Strong displacement expansion");
            } else if (zone.displacementScore < 45) {
                score -= 4;
                warnings.add("Weak displacement quality");
            }
            if (zone.volumeScore >= 55) {
                score += 5;
                factors.add("Volume confirmation: " + zone.volumeScore + "/100");
            } else if (zone.volumeScore < 30) {
                score -= 4;
                warnings.add("Weak volume confirmation");
            }
            if (zone.liquidityScore > 0) factors.add("Liquidity confluence");
            if (zone.mechanicsScore != 0) {
                score += zone.mechanicsScore / 2;
                factors.add("SMC model: " + zone.mechanicLabel());
            }
            if (zone.highQualityModel) {
                score += 4;
                factors.add("Full liquidity-displacement-structure model");
            }
            if (zone.breakerBlock || zone.propulsionBlock || zone.inverted) {
                score += 3;
                factors.add("Advanced SMC mechanic: " + zone.mechanicShort());
            }
            if (zone.liquidityToLiquidity) {
                score += 3;
                factors.add("Clear liquidity-to-liquidity target");
            }
            if (!zone.premiumDiscountAligned) {
                // Штраф применяется всегда, но зависит от силы зоны:
                // сильная зона (≥70) — больший штраф, потому что от неё ждут точного входа
                int pdPenalty = zone.score >= 70 ? 5 : zone.score >= 55 ? 3 : 2;
                score -= pdPenalty;
                warnings.add("Premium/discount alignment is not ideal");
            }
            if (zone.score >= 80 || zone.institutional || "strong".equalsIgnoreCase(zone.quality)) {
                score += 5;
                factors.add("A-grade zone quality");
            } else if (zone.score < 60) {
                score -= 5;
                warnings.add("Zone quality below institutional threshold");
            }
            if (zone.mitigationDepth >= 0.18 && zone.mitigationDepth <= 0.82) {
                score += 4;
                factors.add("Healthy mitigation depth");
            } else if (zone.mitigationDepth > 0.92) {
                score -= 4;
                warnings.add("Deep mitigation; zone may be exhausted");
            }
        }

        if (structureEvent != null && structureEvent.direction == direction) {
            score += 14;
            factors.add(structureEvent.type + " body-close confirmed");
            if (structureEvent.bodyCloseConfirmed) score += 4;
            if (structureEvent.score >= 65) {
                score += 3;
                factors.add("Strong structure shift: " + structureEvent.score + "/100");
            }
        } else {
            warnings.add("Entry CHoCH/BOS not fresh");
        }

        if (session != null && session.inKillzone) {
            score += 8;
            factors.add("Active killzone: " + session.activeSession);
        }

        int last = Math.max(0, metrics.size() - 1);
        if (metrics.compressedVolatility(last)) {
            warnings.add("Low-volatility compression; avoid chop entries until expansion");
            score -= 5;
        }

        if (mtf.aligned) {
            score += 6;
            factors.add("HTF/LTF structure aligned");
        } else if (mtf.pullbackAgainstHtf) {
            score += 3;
            factors.add("LTF pullback into HTF narrative");
        }
        if (mtf.score >= 70) {
            score += 3;
            factors.add("Strong MTF context: " + mtf.score + "/100");
        }

        score += mtf.score / 6;
        return new SmcScore(ScoreUtils.clamp(score, 0, 100), factors, warnings);
    }
}
