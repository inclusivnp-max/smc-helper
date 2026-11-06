package app.logic;

import app.common.Direction;
import app.common.EngineConfig;
import app.displacement.DisplacementEvent;
import app.liquidity.LiquidityLevel;
import app.liquidity.LiquidityMap;
import app.liquidity.LiquiditySweep;
import app.model.Candle;
import app.model.Zone;
import app.structure.MarketStructure;
import app.structure.StructureEvent;
import app.volatility.MarketMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartMoneyMechanicsEngineTest {
    @Test
    void createsInvertedFvgAfterFullFlipAndRetest() {
        List<Candle> candles = List.of(
                c(0, 99.0, 100.0, 98.5, 99.6),
                c(1, 99.6, 100.4, 99.2, 100.1),
                c(2, 100.8, 102.2, 100.7, 101.8),
                c(3, 101.8, 102.0, 99.2, 99.5),
                c(4, 99.5, 100.9, 99.4, 100.8),
                c(5, 100.8, 100.9, 99.8, 100.0)
        );
        MarketMetrics metrics = MarketMetrics.compute(candles, EngineConfig.defaults().atrPeriod);
        Zone fvg = new Zone("FVG", true, 101.0, 100.0, 2);
        fvg.score = 72;
        fvg.displacementScore = 64;

        List<Zone> zones = new SmartMoneyMechanicsEngine().enrich(
                candles,
                List.of(fvg),
                new LiquidityMap(List.of(), List.of(), List.of(), List.of()),
                structure(Direction.BEARISH, 3),
                structure(Direction.BEARISH, 3),
                List.of(displacement(Direction.BEARISH, 3)),
                metrics,
                Direction.BEARISH,
                101.0);

        assertTrue(zones.stream().anyMatch(z -> "IFVG".equals(z.type)
                && z.inverted
                && !z.bullish
                && "ifvg_retest".equals(z.setupModel)));
    }

    @Test
    void rewardsFullLiquidityDisplacementStructureModel() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            candles.add(c(i, 100.0 + i * 0.2, 101.5 + i * 0.2, 98.8 + i * 0.2, 100.8 + i * 0.2));
        }
        MarketMetrics metrics = MarketMetrics.compute(candles, EngineConfig.defaults().atrPeriod);
        Zone ob = new Zone("OB", true, 101.0, 99.0, 5);
        ob.score = 62;
        ob.displacementScore = 70;
        Zone fvg = new Zone("FVG", true, 102.0, 101.4, 7);
        fvg.score = 68;
        fvg.displacementScore = 72;

        LiquidityLevel sweptSellSide = new LiquidityLevel("SSL", "internal", "test low", 98.0,
                75, 2, 1, 4);
        LiquidityLevel targetBuySide = new LiquidityLevel("BSL", "external", "target high", 105.0,
                82, 3, 2, 10);
        LiquidityMap liquidity = new LiquidityMap(
                List.of(targetBuySide),
                List.of(sweptSellSide),
                List.of(new LiquiditySweep(4, Direction.BULLISH, sweptSellSide,
                        84, 0.58, true, true, "test")),
                List.of());

        List<Zone> zones = new SmartMoneyMechanicsEngine().enrich(
                candles,
                List.of(ob, fvg),
                liquidity,
                structure(Direction.BULLISH, 8),
                structure(Direction.BULLISH, 8),
                List.of(displacement(Direction.BULLISH, 6)),
                metrics,
                Direction.BULLISH,
                101.0);

        Zone enrichedOb = zones.stream().filter(z -> z == ob).findFirst().orElseThrow();
        assertTrue(enrichedOb.highQualityModel);
        assertTrue(enrichedOb.breakerBlock);
        assertTrue(enrichedOb.liquidityToLiquidity);
        assertFalse(enrichedOb.mechanicLabel().isBlank());
    }

    private static Candle c(int index, double open, double high, double low, double close) {
        return new Candle(index * 60_000L, open, high, low, close, 1_000.0);
    }

    private static DisplacementEvent displacement(Direction direction, int index) {
        return new DisplacementEvent(index, direction, 76, "strong",
                0.72, 1.8, 1.6, 1.4, 0.5, 2);
    }

    private static MarketStructure structure(Direction direction, int index) {
        return new MarketStructure(direction, 80, null, null, List.of(), List.of(),
                List.of(new StructureEvent(index, "BOS", direction, 100.0,
                        72, true, true, 82)));
    }
}
