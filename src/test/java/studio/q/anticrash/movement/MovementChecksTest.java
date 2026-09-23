package studio.q.anticrash.movement;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementChecksTest {

    @Test
    void detectsNonFiniteCoordinates() {
        assertTrue(MovementChecks.isFinite(0, 0, 0));
        assertFalse(MovementChecks.isFinite(Double.NaN, 0, 0));
        assertFalse(MovementChecks.isFinite(0, Double.POSITIVE_INFINITY, 0));
        assertFalse(MovementChecks.isFinite(0, 0, Double.NEGATIVE_INFINITY));
    }

    @Test
    void rotationRangeChecks() {
        assertTrue(MovementChecks.isFiniteRotation(0f, 0f));
        assertTrue(MovementChecks.isFiniteRotation(180f, 90f));
        assertTrue(MovementChecks.isFiniteRotation(-180f, -90f));
        assertFalse(MovementChecks.isFiniteRotation(0f, 91f));   // beyond straight down
        assertFalse(MovementChecks.isFiniteRotation(0f, -90.5f));
        assertFalse(MovementChecks.isFiniteRotation(Float.NaN, 0f));
        assertFalse(MovementChecks.isFiniteRotation(0f, Float.POSITIVE_INFINITY));
    }

    @Test
    void describeInvalidNamesTheComponent() {
        String s = MovementChecks.describeInvalid(0, Double.NaN, 0);
        assertTrue(s.startsWith("y="));
    }

    @Test
    void trackerFlagsConsecutiveImpossibleDeltas() {
        MovementTracker t = new MovementTracker();
        UUID pid = UUID.randomUUID();
        long base = 1_000_000L;
        // First sample initializes.
        var a = t.observe(pid, new MovementTracker.Sample(0, 64, 0, 0, 0, base));
        assertFalse(a.impossibleDelta());
        // First huge jump: allowed once (teleport/pearl/velocity allowance).
        var b = t.observe(pid, new MovementTracker.Sample(500, 64, 500, 0, 0, base + 50));
        assertFalse(b.impossibleDelta());
        // Second consecutive huge jump: flagged.
        var c = t.observe(pid, new MovementTracker.Sample(1000, 64, 1000, 0, 0, base + 100));
        assertTrue(c.impossibleDelta());
    }

    @Test
    void trackerAllowsNormalMovement() {
        MovementTracker t = new MovementTracker();
        UUID pid = UUID.randomUUID();
        long base = 1_000_000L;
        t.observe(pid, new MovementTracker.Sample(0, 64, 0, 0, 0, base));
        var a = t.observe(pid, new MovementTracker.Sample(0.3, 64, 0.4, 5, 2, base + 50));
        var b = t.observe(pid, new MovementTracker.Sample(0.6, 64, 0.8, 10, 4, base + 100));
        assertFalse(a.impossibleDelta());
        assertFalse(a.rotationSpam());
        assertFalse(b.impossibleDelta());
        assertFalse(b.rotationSpam());
    }

    @Test
    void trackerRemoveClearsState() {
        MovementTracker t = new MovementTracker();
        UUID pid = UUID.randomUUID();
        long base = 1_000_000L;
        t.observe(pid, new MovementTracker.Sample(0, 64, 0, 0, 0, base));
        t.observe(pid, new MovementTracker.Sample(500, 64, 500, 0, 0, base + 50));
        t.remove(pid);
        assertEquals(0, t.tracked());
        // After removal, the next sample re-initializes (no flag).
        var r = t.observe(pid, new MovementTracker.Sample(0, 64, 0, 0, 0, base + 100));
        assertFalse(r.impossibleDelta());
    }
}
