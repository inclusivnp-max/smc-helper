package app.fvg;

import app.common.Direction;
import app.common.EngineConfig;
import app.common.ScoreUtils;
import app.displacement.DisplacementContext;
import app.displacement.DisplacementEvent;
import app.model.Candle;
import app.model.Zone;
import app.volatility.MarketMetrics;
import app.volume.VolumeContext;

import java.util.ArrayList;
import java.util.List;

public class FvgEngine {
    private final EngineConfig config;

    public FvgEngine(EngineConfig config) {
        this.config = config;
    }

    public List<Zone> find(List<Candle> candles, MarketMetrics metrics,
                           DisplacementContext displacement, VolumeContext volume, Direction htfBias) {
        List<Zone> zones = new ArrayList<>();
        for (int i = 2; i < candles.size(); i++) {
            Candle left = candles.get(i - 2);
            Candle right = candles.get(i);

            if (left.high < right.low) {
                addFvg(candles, metrics, displacement, volume, htfBias, zones, i,
                        true, right.low, left.high, right.low - left.high);
            }
            if (left.low > right.high) {
                addFvg(candles, metrics, displacement, volume, htfBias, zones, i,
                        false, left.low, right.high, left.low - right.high);
            }
        }
        return zones;
    }

    private void addFvg(List<Candle> candles, MarketMetrics metrics, DisplacementContext displacement,
                        VolumeContext volume, Direction htfBias, List<Zone> zones, int index, boolean bullish,
                        double top, double bottom, double gap) {
        Direction direction = bullish ? Direction.BULLISH : Direction.BEARISH;
        double atr = metrics.atrAt(index);
        if (gap < atr * config.minFvgAtrSize) return;
        DisplacementEvent disp = displacement.bestAfter(index - 3, direction, 4);
        if (disp == null || disp.score < 42) return;

        Zone zone = new Zone("FVG", bullish, top, bottom, index);
        zone.source = "fvg";
        zone.institutional = true;
        zone.quality = disp.quality;
        zone.displacementScore = disp.score;
        // volumeScore теперь берётся с бара displacement-события — аналогично OB
        zone.volumeScore = volume.scoreAt(disp.index);
        zone.score = 24 + disp.score / 3 + ScoreUtils.scale(gap / atr, config.minFvgAtrSize, 0.75, 18)
                + zone.volumeScore / 10;
        zone.reasons.add((bullish ? "Bullish" : "Bearish") + " displacement created imbalance");
        zone.reasons.add("FVG size normalized by ATR");
        if (zone.volumeScore >= 55) {
            zone.reasons.add("Volume confirmation: " + zone.volumeScore + "/100");
        }
        if (direction == htfBias) {
            zone.score += 12;
            zone.reasons.add("Aligned with HTF narrative");
        }
        if (metrics.compressedVolatility(index - 1)) {
            zone.score += 6;
            zone.reasons.add("Expansion after volatility compression");
        }

        // Age decay: старые FVG постепенно теряют ценность.
        // Нарастающий штраф после 15 свечей — к 60+ свечам максимум -18 pts.
        int age = candles.size() - 1 - index;
        if (age > 15) {
            int agePenalty = Math.min(18, (age - 15) / 3);
            zone.score -= agePenalty;
            if (agePenalty >= 6) {
                zone.reasons.add("FVG age decay: -" + agePenalty + " pts (" + age + " bars old)");
            }
        }

        // Свежие FVG (≤5 свечей) получают небольшой бонус за актуальность
        if (age <= 5) {
            zone.score += 4;
            zone.reasons.add("Fresh FVG (within 5 bars)");
        }

        zone.score = ScoreUtils.clamp(zone.score, 0, 100);
        zones.add(zone);
    }
}
