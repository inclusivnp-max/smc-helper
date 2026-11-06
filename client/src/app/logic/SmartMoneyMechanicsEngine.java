package app.logic;

import app.common.Direction;
import app.common.ScoreUtils;
import app.displacement.DisplacementEvent;
import app.liquidity.LiquidityLevel;
import app.liquidity.LiquidityMap;
import app.liquidity.LiquiditySweep;
import app.model.Candle;
import app.model.Zone;
import app.structure.MarketStructure;
import app.structure.StructureEvent;
import app.volatility.MarketMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Adds higher-level SMC mechanics on top of raw OB/FVG detection.
 * The goal is to reward the full sequence: liquidity -> displacement -> structure -> mitigation -> target liquidity.
 */
public final class SmartMoneyMechanicsEngine {
    private static final int SETUP_WINDOW = 14;

    public List<Zone> enrich(List<Candle> candles,
                             List<Zone> zones,
                             LiquidityMap liquidity,
                             MarketStructure ltfStructure,
                             MarketStructure htfStructure,
                             List<DisplacementEvent> displacementEvents,
                             MarketMetrics metrics,
                             Direction htfBias,
                             double equilibrium) {
        if (zones == null || zones.isEmpty()) return zones == null ? List.of() : zones;

        List<Zone> enriched = new ArrayList<>(zones.size() + 4);
        enriched.addAll(zones);
        for (Zone zone : zones) {
            Zone inverted = invertedFvg(candles, zone, ltfStructure, displacementEvents, metrics, htfBias);
            if (inverted != null) enriched.add(inverted);
        }

        for (Zone zone : enriched) {
            classify(candles, enriched, zone, liquidity, ltfStructure, htfStructure,
                    displacementEvents, metrics, htfBias, equilibrium);
        }
        return enriched;
    }

    private void classify(List<Candle> candles,
                          List<Zone> zones,
                          Zone zone,
                          LiquidityMap liquidity,
                          MarketStructure ltfStructure,
                          MarketStructure htfStructure,
                          List<DisplacementEvent> displacementEvents,
                          MarketMetrics metrics,
                          Direction htfBias,
                          double equilibrium) {
        Direction direction = zone.bullish ? Direction.BULLISH : Direction.BEARISH;
        double atr = metrics.atrAt(zone.creationIndex);
        LiquiditySweep sweep = latestSweepNear(liquidity, direction, zone.creationIndex, SETUP_WINDOW);
        StructureEvent structure = bestStructureNear(ltfStructure, direction, zone.creationIndex, SETUP_WINDOW + 4);
        boolean hasDirectionalFvg = hasNearbyZone(zones, zone, "FVG", direction, SETUP_WINDOW);
        boolean hasDirectionalOb = hasNearbyZone(zones, zone, "OB", direction, SETUP_WINDOW);
        boolean hasDisplacement = zone.displacementScore >= 60 || hasDisplacementNear(displacementEvents, direction, zone.creationIndex);
        boolean htfAligned = direction == htfBias
                || (htfStructure != null && htfStructure.bias == direction && htfStructure.strength >= 55);
        LiquidityLevel target = targetLiquidity(liquidity, zone.mid(), direction, atr);
        boolean targetOk = target != null;

        boolean premiumDiscount = premiumDiscountAligned(zone, equilibrium);
        zone.premiumDiscountAligned = premiumDiscount;
        if (premiumDiscount) addUnique(zone.reasons, "Premium/discount supports direction");

        if (targetOk) {
            zone.liquidityToLiquidity = true;
            zone.targetLiquidity = target.side + " " + target.label + " @ " + String.format("%.4f", target.price);
            addUnique(zone.reasons, "Liquidity-to-liquidity target: " + zone.targetLiquidity);
        }

        if (isBreaker(zone, sweep, structure)) {
            zone.breakerBlock = true;
            zone.mechanic = "Breaker Block";
            zone.setupModel = "breaker_block";
            zone.mechanicsScore += 16;
            addUnique(zone.reasons, "Breaker model: sweep followed by opposite structure break");
        } else if (isPropulsion(zones, zone)) {
            zone.propulsionBlock = true;
            zone.mechanic = "Propulsion Block";
            zone.setupModel = "propulsion_block";
            zone.mechanicsScore += 12;
            addUnique(zone.reasons, "Propulsion model: nested zone inside larger imbalance");
        } else if (isMitigationModel(zone, structure, sweep)) {
            zone.mitigationBlock = true;
            zone.mechanic = "Mitigation Block";
            zone.setupModel = "mitigation_block";
            zone.mechanicsScore += 8;
            addUnique(zone.reasons, "Mitigation model: price returned into active zone after structure shift");
        }

        int confluence = 0;
        if (sweep != null) {
            confluence += 12;
            zone.sweepConfirmed = true;
            zone.liquidityScore = Math.max(zone.liquidityScore, sweep.score);
            addUnique(zone.reasons, "Liquidity run before setup model");
        }
        if (hasDisplacement) {
            confluence += 10;
            addUnique(zone.reasons, "Displacement confirms imbalance");
        }
        if (structure != null) {
            confluence += structure.bodyCloseConfirmed ? 12 : 7;
            zone.structureScore = Math.max(zone.structureScore, structure.score);
            addUnique(zone.reasons, "Structure shift confirms model");
        }
        if (hasDirectionalFvg && !"FVG".equals(zone.type)) {
            confluence += 7;
            addUnique(zone.reasons, "OB has nearby FVG imbalance");
        }
        if (hasDirectionalOb && "FVG".equals(zone.type)) {
            confluence += 5;
            addUnique(zone.reasons, "FVG has nearby OB origin");
        }
        if (premiumDiscount) confluence += 5;
        if (targetOk) confluence += 6;
        if (htfAligned) confluence += 6;

        zone.mechanicsScore += Math.min(28, confluence / 2);
        zone.highQualityModel = confluence >= 34 && sweep != null && hasDisplacement && structure != null;
        if (zone.highQualityModel) {
            zone.mechanicsScore += 8;
            if (zone.mechanic == null || zone.mechanic.isBlank()) {
                zone.mechanic = "High-quality SMC model";
                zone.setupModel = "liquidity_displacement_model";
            }
            addUnique(zone.reasons, "Full model: liquidity -> displacement -> structure -> mitigation");
        } else if ((zone.mechanic == null || zone.mechanic.isBlank()) && zone.liquidityToLiquidity) {
            zone.mechanic = "Liquidity-to-liquidity";
            zone.setupModel = "liquidity_to_liquidity";
        }

        if (!premiumDiscount && Math.abs(zone.mid() - equilibrium) > atr * 0.35) {
            zone.mechanicsScore -= 4;
            addUnique(zone.reasons, "Premium/discount is not ideal for direction");
        }

        zone.mechanicsScore = ScoreUtils.clamp(zone.mechanicsScore, -12, 44);
        zone.score = ScoreUtils.clamp(zone.score + zone.mechanicsScore, 0, 100);
        zone.quality = zone.score >= 75 ? "strong" : zone.score >= 55 ? "medium" : "weak";
    }

    private Zone invertedFvg(List<Candle> candles,
                             Zone source,
                             MarketStructure structure,
                             List<DisplacementEvent> displacementEvents,
                             MarketMetrics metrics,
                             Direction htfBias) {
        if (!"FVG".equals(source.type) || candles == null || candles.isEmpty()) return null;
        int flipIndex = -1;
        Direction flipDirection = source.bullish ? Direction.BEARISH : Direction.BULLISH;
        for (int i = source.creationIndex + 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double buffer = metrics.atrAt(i) * 0.04;
            if (source.bullish && c.close < source.bottom - buffer) {
                flipIndex = i;
                break;
            }
            if (!source.bullish && c.close > source.top + buffer) {
                flipIndex = i;
                break;
            }
        }
        if (flipIndex < 0 || flipIndex >= candles.size() - 1) return null;

        Zone ifvg = new Zone("IFVG", flipDirection.bullish(), source.top, source.bottom, flipIndex);
        ifvg.source = "ifvg";
        ifvg.institutional = true;
        ifvg.inverted = true;
        ifvg.mechanic = "Inverted FVG";
        ifvg.setupModel = "ifvg_retest";
        ifvg.quality = source.quality;
        ifvg.displacementScore = source.displacementScore;
        ifvg.volumeScore = source.volumeScore;
        ifvg.score = ScoreUtils.clamp(source.score - 8, 0, 100);
        ifvg.reasons.add("IFVG: original FVG was crossed and flipped");

        DisplacementEvent flipDisplacement = bestDisplacementAfter(displacementEvents, flipIndex - 1, flipDirection, 4);
        if (flipDisplacement != null) {
            ifvg.displacementScore = Math.max(ifvg.displacementScore, flipDisplacement.score);
            ifvg.score += 6;
            ifvg.reasons.add("Flip supported by displacement");
        }
        StructureEvent flipStructure = bestStructureNear(structure, flipDirection, flipIndex, 8);
        if (flipStructure != null) {
            ifvg.structureScore = Math.max(ifvg.structureScore, flipStructure.score);
            ifvg.score += 6;
            ifvg.reasons.add("Flip supported by BOS/CHoCH");
        }
        if (flipDirection == htfBias) {
            ifvg.score += 5;
            ifvg.reasons.add("IFVG aligned with HTF bias");
        }
        if (retested(candles, ifvg, flipIndex + 1)) {
            ifvg.score += 7;
            ifvg.reasons.add("IFVG retested after flip");
        }
        ifvg.score = ScoreUtils.clamp(ifvg.score, 0, 100);
        return ifvg;
    }

    private boolean isBreaker(Zone zone, LiquiditySweep sweep, StructureEvent structure) {
        return "OB".equals(zone.type)
                && sweep != null
                && structure != null
                && structure.index >= zone.creationIndex
                && structure.index >= sweep.index
                && Math.abs(zone.creationIndex - sweep.index) <= SETUP_WINDOW;
    }

    private boolean isMitigationModel(Zone zone, StructureEvent structure, LiquiditySweep sweep) {
        return structure != null
                && sweep == null
                && zone.mitigationDepth >= 0.16
                && zone.mitigationDepth <= 0.86
                && zone.displacementScore >= 52;
    }

    private boolean isPropulsion(List<Zone> zones, Zone candidate) {
        if (!"OB".equals(candidate.type)) return false;
        for (Zone parent : zones) {
            if (parent == candidate || !"OB".equals(parent.type) || parent.bullish != candidate.bullish) continue;
            if (parent.creationIndex >= candidate.creationIndex) continue;
            if (parent.height() <= candidate.height() * 1.18) continue;
            boolean contains = parent.top >= candidate.top && parent.bottom <= candidate.bottom;
            boolean nearNested = candidate.bottom >= parent.bottom && candidate.top <= parent.top;
            if (contains || nearNested) return true;
        }
        return false;
    }

    private boolean premiumDiscountAligned(Zone zone, double equilibrium) {
        if (!Double.isFinite(equilibrium)) return false;
        if (zone.bullish) return zone.mid() <= equilibrium;
        return zone.mid() >= equilibrium;
    }

    private LiquiditySweep latestSweepNear(LiquidityMap liquidity, Direction direction, int index, int window) {
        if (liquidity == null) return null;
        LiquiditySweep best = null;
        for (LiquiditySweep sweep : liquidity.sweeps) {
            if (sweep.reversalDirection != direction) continue;
            if (Math.abs(sweep.index - index) > window) continue;
            if (best == null || sweep.index > best.index) best = sweep;
        }
        return best;
    }

    private StructureEvent bestStructureNear(MarketStructure structure, Direction direction, int index, int window) {
        if (structure == null) return null;
        return structure.events.stream()
                .filter(event -> event.direction == direction)
                .filter(event -> Math.abs(event.index - index) <= window || event.index >= index && event.index <= index + window)
                .max(Comparator.comparingInt(event -> event.score))
                .orElse(null);
    }

    private DisplacementEvent bestDisplacementAfter(List<DisplacementEvent> events,
                                                    int index,
                                                    Direction direction,
                                                    int window) {
        if (events == null) return null;
        DisplacementEvent best = null;
        for (DisplacementEvent event : events) {
            if (event.direction != direction) continue;
            if (event.index < index || event.index > index + window) continue;
            if (best == null || event.score > best.score) best = event;
        }
        return best;
    }

    private boolean hasDisplacementNear(List<DisplacementEvent> events, Direction direction, int index) {
        return bestDisplacementAfter(events, Math.max(0, index - 3), direction, 8) != null;
    }

    private boolean hasNearbyZone(List<Zone> zones, Zone source, String type, Direction direction, int window) {
        for (Zone zone : zones) {
            if (zone == source || !type.equals(zone.type) || zone.bullish != direction.bullish()) continue;
            if (Math.abs(zone.creationIndex - source.creationIndex) <= window) return true;
        }
        return false;
    }

    private LiquidityLevel targetLiquidity(LiquidityMap liquidity, double price, Direction direction, double atr) {
        if (liquidity == null) return null;
        List<LiquidityLevel> levels = direction.bullish() ? liquidity.buySide : liquidity.sellSide;
        LiquidityLevel best = null;
        double bestDistance = Double.MAX_VALUE;
        double minDistance = Math.max(atr * 0.65, Math.abs(price) * 0.0009);
        double maxDistance = Math.max(atr * 8.5, Math.abs(price) * 0.028);
        for (LiquidityLevel level : levels) {
            if (level.swept) continue;
            double signedDistance = direction.bullish() ? level.price - price : price - level.price;
            if (signedDistance < minDistance || signedDistance > maxDistance) continue;
            if (signedDistance < bestDistance) {
                bestDistance = signedDistance;
                best = level;
            }
        }
        return best;
    }

    private boolean retested(List<Candle> candles, Zone zone, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < candles.size(); i++) {
            Candle c = candles.get(i);
            if (zone.bullish && c.low <= zone.top && c.close >= zone.bottom) return true;
            if (!zone.bullish && c.high >= zone.bottom && c.close <= zone.top) return true;
        }
        return false;
    }

    private void addUnique(List<String> list, String value) {
        if (value == null || value.isBlank() || list.contains(value)) return;
        list.add(value);
    }
}
