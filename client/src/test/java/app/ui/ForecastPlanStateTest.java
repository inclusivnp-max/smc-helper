package app.ui;

import app.model.TradePlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecastPlanStateTest {
    @Test
    void radarForecastIsPendingOnceAndStaysSticky() {
        ForecastPlanState state = new ForecastPlanState();
        TradePlan plan = TradePlan.restored(true, 100.0, 95.0, 105.0, 110.0, 115.0,
                80, true, "junit");

        state.armRadar("TONUSDT", plan);

        assertTrue(state.pendingMatches("TONUSDT"));
        assertTrue(state.consumePendingFor("TONUSDT"));
        assertFalse(state.pendingMatches("TONUSDT"));
        assertTrue(state.stickyMatches("TONUSDT"));
        assertSame(plan, state.plan());
    }

    @Test
    void stickyForecastClearsWhenSwitchingSymbolWithoutPendingRadar() {
        ForecastPlanState state = new ForecastPlanState();
        TradePlan plan = TradePlan.restored(false, 100.0, 105.0, 95.0, 90.0, 85.0,
                80, true, "junit");

        state.stick("BTCUSDT", plan);
        state.clearIfDifferentSymbol("ETHUSDT");

        assertFalse(state.sticky());
        assertFalse(state.hasPlan());
    }
}
