package app.ui.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveValueSmootherTest {
    @Test
    void smootherMovesTowardTargetWithoutOvershooting() {
        LiveValueSmoother smoother = new LiveValueSmoother(80.0);
        smoother.reset(100.0);
        smoother.target(110.0);

        smoother.tick(1_000_000_000L, 0.0001);
        smoother.tick(1_050_000_000L, 0.0001);

        double value = smoother.valueOr(0.0);
        assertTrue(value > 100.0, "value should move toward the live target");
        assertTrue(value < 110.0, "value should not jump through the target");
    }

    @Test
    void resetSnapsVisualValueToTarget() {
        LiveValueSmoother smoother = new LiveValueSmoother(80.0);
        smoother.reset(42.5);
        assertEquals(42.5, smoother.valueOr(0.0), 0.000001);
        assertTrue(!smoother.active(0.000001));
    }
}
