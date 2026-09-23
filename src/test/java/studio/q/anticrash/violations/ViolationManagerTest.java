package studio.q.anticrash.violations;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationManagerTest {

    private static final class MutableClock implements java.util.function.LongSupplier {
        long now = 1_000_000L;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    @Test
    void addAccumulatesWeight() {
        MutableClock clock = new MutableClock();
        ViolationManager vm = new ViolationManager(60_000, 2, clock);
        UUID pid = UUID.randomUUID();
        assertEquals(4, vm.add(pid, "PacketRate", 4));
        assertEquals(8, vm.add(pid, "PacketRate", 4));
        assertEquals(8, vm.level(pid, "PacketRate"));
    }

    @Test
    void modulesAreSeparate() {
        MutableClock clock = new MutableClock();
        ViolationManager vm = new ViolationManager(60_000, 2, clock);
        UUID pid = UUID.randomUUID();
        vm.add(pid, "Movement", 2);
        vm.add(pid, "ChatGuard", 8);
        assertEquals(2, vm.level(pid, "movement"));
        assertEquals(8, vm.level(pid, "chatguard"));
        assertEquals(10, vm.totalLevel(pid));
    }

    @Test
    void decayReducesLevelOverTime() {
        MutableClock clock = new MutableClock();
        ViolationManager vm = new ViolationManager(60_000, 2, clock);
        UUID pid = UUID.randomUUID();
        vm.add(pid, "Movement", 10);
        clock.now += 60_000;
        assertEquals(8, vm.level(pid, "Movement"));
        // Lazy decay applies at most one step per access once the interval has
        // elapsed (allocation-free, loop-free design), anchored at the last
        // decay point. Repeated accesses over time keep eroding the level.
        clock.now += 60_000;
        assertEquals(6, vm.level(pid, "Movement"));
        clock.now += 60_000;
        assertEquals(4, vm.level(pid, "Movement"));
        clock.now += 600_000; // far future: still bounded, never negative
        long lvl = vm.level(pid, "Movement");
        assertTrue(lvl >= 0 && lvl < 4, "lvl=" + lvl);
    }

    @Test
    void decayNeverGoesNegative() {
        MutableClock clock = new MutableClock();
        ViolationManager vm = new ViolationManager(60_000, 100, clock);
        UUID pid = UUID.randomUUID();
        vm.add(pid, "Movement", 4);
        clock.now += 60_000;
        assertEquals(0, vm.level(pid, "Movement"));
    }

    @Test
    void snapshotContainsOnlyPositiveLevels() {
        MutableClock clock = new MutableClock();
        ViolationManager vm = new ViolationManager(60_000, 2, clock);
        UUID pid = UUID.randomUUID();
        vm.add(pid, "Movement", 4);
        vm.add(pid, "ChatGuard", 1);
        Map<String, Long> snap = vm.snapshot(pid);
        assertEquals(2, snap.size());
        assertTrue(snap.containsKey("movement"));
        assertTrue(snap.containsKey("chatguard"));
    }

    @Test
    void resetClearsPlayer() {
        MutableClock clock = new MutableClock();
        ViolationManager vm = new ViolationManager(60_000, 2, clock);
        UUID pid = UUID.randomUUID();
        vm.add(pid, "Movement", 8);
        vm.reset(pid);
        assertEquals(0, vm.totalLevel(pid));
        assertEquals(0, vm.trackedPlayers());
    }

    @Test
    void cleanupRemovesStalePlayers() {
        MutableClock clock = new MutableClock();
        ViolationManager vm = new ViolationManager(60_000, 2, clock);
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        vm.add(stale, "Movement", 4);
        vm.add(fresh, "Movement", 4);
        clock.now += 500_000;
        vm.add(fresh, "Movement", 1); // refresh freshness
        int removed = vm.cleanup(120_000);
        assertEquals(1, removed);
        assertEquals(0, vm.totalLevel(stale));
        assertTrue(vm.totalLevel(fresh) > 0);
    }

    @Test
    void unknownPlayerLevelIsZero() {
        MutableClock clock = new MutableClock();
        ViolationManager vm = new ViolationManager(60_000, 2, clock);
        assertEquals(0, vm.level(UUID.randomUUID(), "Movement"));
        assertEquals(0, vm.totalLevel(UUID.randomUUID()));
    }
}
