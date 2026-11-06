package app.ai;

import app.fundamental.EventRiskContext;
import app.model.AnalysisResult;
import app.model.TradePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiForecastEngineTest {
    @Test
    void eventRiskBlockPreventsEnterVerdict() {
        TradePlan plan = TradePlan.restored(true, 100.0, 95.0, 105.0, 110.0, 115.0,
                82, true, "junit");
        AnalysisResult result = resultWith(plan, new EventRiskContext(true, 88, 8,
                true, false, false, "high impact", "High-impact event window"));

        AiForecastEngine.Forecast forecast = new AiForecastEngine().generate(result);

        assertTrue("ВОЗДЕРЖАТЬСЯ".equals(forecast.verdict()) || "STAND ASIDE".equals(forecast.verdict()));
    }

    @Test
    void nullCollectionsRenderAsNoDataSectionsInsteadOfCrashing() {
        TradePlan plan = TradePlan.restored(false, 100.0, 105.0, 95.0, 90.0, 85.0,
                70, false, "junit");
        AnalysisResult result = new AnalysisResult("ETHUSDT", "15m", null,
                100.0, false, null, plan, 100.0, 110.0, 90.0);

        AiForecastEngine.Forecast forecast = new AiForecastEngine().generate(result);

        assertFalse(forecast.html().isBlank());
    }

    private static AnalysisResult resultWith(TradePlan plan, EventRiskContext eventRisk) {
        return new AnalysisResult("BTCUSDT", "5m", List.of(),
                100.0, true, List.of(), plan, 100.0, 110.0, 90.0,
                null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, eventRisk, null,
                null, null, null,
                82, List.of("junit factor"), List.of(), "junit narrative");
    }
}
