package studio.q.anticrash.core.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsBurstThenRejects() {
        RateLimiter rl = new RateLimiter(10, 3, 60_000);
        long now = 1_000_000L;
        assertTrue(rl.tryConsume(1L, now));
        assertTrue(rl.tryConsume(1L, now));
        assertTrue(rl.tryConsume(1L, now));
        // Bucket empty: rejected even though time has not advanced.
        assertFalse(rl.tryConsume(1L, now));
    }

    @Test
    void refillsOverTime() {
        RateLimiter rl = new RateLimiter(10, 1, 60_000);
        long now = 1_000_000L;
        assertTrue(rl.tryConsume(1L, now));
        assertFalse(rl.tryConsume(1L, now));
        // 10/s -> one token after ~100ms (fixed point 3 decimals tolerates 99ms -> 0.99).
        assertTrue(rl.tryConsume(1L, now + 100));
        assertFalse(rl.tryConsume(1L, now + 150));
    }

    @Test
    void keysAreIndependent() {
        RateLimiter rl = new RateLimiter(10, 1, 60_000);
        long now = 1_000_000L;
        assertTrue(rl.tryConsume(1L, now));
        // Different key is unaffected.
        assertTrue(rl.tryConsume(2L, now));
        // Sub-key variations also isolate.
        assertTrue(rl.tryConsume(RateLimiter.key(5L, 7, 1), now));
        assertTrue(rl.tryConsume(RateLimiter.key(5L, 7, 2), now));
    }

    @Test
    void keyPackingIsDeterministic() {
        long a = RateLimiter.key(0xDEADBEEFL, 0x12345678, 0);
        long b = RateLimiter.key(0xDEADBEEFL, 0x12345678, 0);
        assertEquals(a, b);
        long c = RateLimiter.key(0xDEADBEEFL, 0x12345679, 0);
        assertTrue(a != c);
    }

    @Test
    void cleanupRemovesExpiredBuckets() throws InterruptedException {
        RateLimiter rl = new RateLimiter(10, 5, 50);
        long now = System.currentTimeMillis();
        rl.tryConsume(1L, now);
        rl.tryConsume(2L, now);
        assertEquals(2, rl.size());
        Thread.sleep(80);
        rl.cleanup(System.currentTimeMillis());
        assertEquals(0, rl.size());
    }

    @Test
    void sustainedLoadNeverExceedsRate() {
        // 100/s, burst 20. Simulate 1s of maximal pressure in 10ms steps.
        RateLimiter rl = new RateLimiter(100, 20, 60_000);
        long now = 0L;
        long accepted = 0;
        for (int i = 0; i < 100; i++) { // 100 steps * 10ms = 1s
            now += 10;
            if (rl.tryConsume(1L, now)) {
                accepted++;
            }
        }
        // A continuous 10ms caller matches the 100/s refill exactly: with the
        // initial burst consumed first, it must never exceed 100 accepted/s.
        assertTrue(accepted <= 100, "accepted=" + accepted);
        assertTrue(accepted >= 95, "accepted=" + accepted);
    }

    @Test
    void burstIsGrantedAfterIdle() {
        RateLimiter rl = new RateLimiter(10, 5, 60_000);
        long now = 1_000_000L;
        // Drain the bucket.
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.tryConsume(1L, now));
        }
        assertFalse(rl.tryConsume(1L, now));
        // After 10s of idle, at most min(burst, 10/s * 10s) = 5 tokens accrued.
        now += 10_000;
        int granted = 0;
        for (int i = 0; i < 10; i++) {
            if (rl.tryConsume(1L, now)) {
                granted++;
            }
        }
        assertTrue(granted <= 5, "granted=" + granted);
        assertTrue(granted >= 4, "granted=" + granted);
    }
}
