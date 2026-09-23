package studio.q.anticrash.core.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowCounterTest {

    @Test
    void countsInsideWindow() {
        WindowCounter wc = new WindowCounter(10_000, 60_000);
        long now = 1_000_000L;
        assertEquals(1, wc.increment(1L, now));
        assertEquals(2, wc.increment(1L, now + 100));
        assertEquals(3, wc.increment(1L, now + 200));
        assertEquals(3, wc.current(1L, now + 300));
    }

    @Test
    void resetsAcrossWindowBoundary() {
        WindowCounter wc = new WindowCounter(10_000, 60_000);
        long now = 1_000_000L;
        wc.increment(1L, now);
        wc.increment(1L, now + 100);
        assertEquals(2, wc.current(1L, now + 200));
        // Past the window: count restarts.
        assertEquals(1, wc.increment(1L, now + 10_500));
        assertEquals(1, wc.current(1L, now + 10_600));
    }

    @Test
    void keysAreIndependent() {
        WindowCounter wc = new WindowCounter(10_000, 60_000);
        long now = 1_000_000L;
        wc.increment(1L, now);
        wc.increment(1L, now);
        wc.increment(1L, now);
        assertEquals(1, wc.increment(2L, now));
        assertEquals(3, wc.current(1L, now));
    }

    @Test
    void cleanupRemovesExpiredEntries() throws InterruptedException {
        WindowCounter wc = new WindowCounter(100, 100);
        wc.increment(1L, System.currentTimeMillis());
        assertEquals(1, wc.size());
        Thread.sleep(450);
        wc.cleanup(System.currentTimeMillis());
        assertEquals(0, wc.size());
    }

    @Test
    void rateMeterFixedWindow() {
        RateMeter m = new RateMeter(1000);
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            m.increment(now);
        }
        assertEquals(5, m.current(now));
        assertEquals(5, m.total());
        // Window advances: counter resets, total persists.
        m.increment(now + 1500);
        assertEquals(1, m.current(now + 1500));
        assertEquals(6, m.total());
        m.reset();
        assertEquals(0, m.current(now + 1500));
    }

    @Test
    void rateMeterStormDetection() {
        RateMeter m = new RateMeter(1000);
        long now = 1_000_000L;
        int limit = 100;
        long last = 0;
        for (int i = 0; i < limit + 5; i++) {
            last = m.increment(now);
        }
        assertTrue(last > limit);
        assertEquals(limit + 5, m.total());
    }
}
