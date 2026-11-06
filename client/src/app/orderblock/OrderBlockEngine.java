package app.orderblock;

import app.common.Direction;
import app.common.EngineConfig;
import app.common.ScoreUtils;
import app.displacement.DisplacementContext;
import app.displacement.DisplacementEvent;
import app.model.Candle;
import app.model.Zone;
import app.structure.MarketStructure;
import app.structure.StructureEvent;
import app.volatility.MarketMetrics;
import app.volume.VolumeContext;

import java.util.ArrayList;
import java.util.List;

public class OrderBlockEngine {
    private final EngineConfig config;

    public OrderBlockEngine(EngineConfig config) {
        this.config = config;
    }

    public List<Zone> find(List<Candle> candles, MarketMetrics metrics,
                           DisplacementContext displacement, VolumeContext volume,
                           MarketStructure structure, Direction htfBias) {
        List<Zone> zones = new ArrayList<>();
        for (DisplacementEvent disp : displacement.events()) {
            if (disp.score < 45) continue;
            Direction direction = disp.direction;
            int obIndex = findLastOppositeCandle(candles, disp.index, direction);
            if (obIndex < 0) continue;

            Candle obCandle = candles.get(obIndex);
            double atr = metrics.atrAt(obIndex);
            double top = obCandle.high;
            double bottom = obCandle.low;
            if (top - bottom < atr * config.minZoneAtrWidth) {
                double mid = (top + bottom) * 0.5;
                top = mid + atr * config.minZoneAtrWidth * 0.5;
                bottom = mid - atr * config.minZoneAtrWidth * 0.5;
            }

            Zone zone = new Zone("OB", direction.bullish(), top, bottom, obIndex);
            zone.source = "orderblock";
            zone.institutional = true;
            zone.quality = disp.quality;
            zone.displacementScore = disp.score;
            zone.volumeScore = volume.scoreAt(obIndex);
            zone.structureScore = nearestStructureScore(structure, disp.index, direction);
            zone.score = 22 + disp.score / 3 + zone.volumeScore / 8 + zone.structureScore / 4;
            zone.reasons.add("Last opposing candle before displacement");
            zone.reasons.add("Displacement score: " + disp.score + "/100");
            if (zone.structureScore > 0) zone.reasons.add("Validated by BOS/CHoCH body close");
            if (direction == htfBias) {
                zone.score += 12;
                zone.reasons.add("Aligned with HTF bias");
            }
            if (disp.volumeRatio >= 1.35) {
                zone.score += 7;
                zone.reasons.add("Volume expansion on displacement");
            }
            zone.score = ScoreUtils.clamp(zone.score, 0, 100);
            zones.add(zone);
        }
        return zones;
    }

    /**
     * Ищет наиболее значимый OB-кандл перед дисплейсментом:
     * сначала находит все противоположные свечи в lookback-окне,
     * затем выбирает тот, у которого наибольшее тело (body) —
     * это более значимый институциональный след, чем просто последний кандл.
     * Если среди кандидатов нет свечи с body >= 30% range, берёт ближайший.
     */
    private int findLastOppositeCandle(List<Candle> candles, int displacementIndex, Direction direction) {
        int start = Math.max(0, displacementIndex - 14);
        int bestIndex = -1;
        double bestBody = -1.0;
        int nearestIndex = -1;

        for (int i = displacementIndex - 1; i >= start; i--) {
            Candle c = candles.get(i);
            boolean opposite = (direction.bullish() && c.bearish()) || (direction.bearish() && c.bullish());
            if (!opposite) continue;
            if (nearestIndex < 0) nearestIndex = i; // первый попавшийся (самый близкий к дисплейсменту)
            double body = c.body();
            if (body > bestBody) {
                bestBody = body;
                bestIndex = i;
            }
        }

        if (bestIndex < 0) return nearestIndex;
        // Предпочитаем кандл с наибольшим телом, если он значимее ближайшего
        // (иначе берём ближайший — OB должен быть непосредственно перед импульсом)
        Candle best = candles.get(bestIndex);
        double range = best.range();
        if (range > 0 && best.body() / range >= 0.30) return bestIndex;
        return nearestIndex;
    }

    private int nearestStructureScore(MarketStructure structure, int index, Direction direction) {
        int score = 0;
        for (StructureEvent event : structure.events) {
            if (event.direction != direction) continue;
            if (Math.abs(event.index - index) <= 5) score = Math.max(score, event.score);
        }
        return score;
    }
}
