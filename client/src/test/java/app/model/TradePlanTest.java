package app.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradePlanTest {
    @Test
    void restoredPlanKeepsClassicOneTwoThreeRiskReward() {
        TradePlan plan = TradePlan.restored(true, 100.0, 95.0, 105.0, 110.0, 115.0,
                80, true, "junit");

        assertEquals(5.0, plan.risk(), 0.000001);
        assertEquals(1.0, plan.rr1(), 0.000001);
        assertEquals(2.0, plan.rr2(), 0.000001);
        assertEquals(3.0, plan.rr3(), 0.000001);
    }

    @Test
    void smartTargetsDoNotChaseBeyondUsefulLiquidityTarget() {
        Zone zone = new Zone("FVG", true, 99.0, 98.0, 10);
        zone.score = 90;

        TradePlan plan = TradePlan.smart(true, 100.0, zone, 2.0,
                90, "junit", true, 8, 12,
                "test", 130.0, "far liquidity", List.of("factor"), List.of());

        assertTrue(plan.rr3() <= 3.25, "far liquidity should be capped to useful R range");
        assertTrue(plan.tp1 < plan.tp2 && plan.tp2 < plan.tp3, "long targets should stay ordered");
    }
}
