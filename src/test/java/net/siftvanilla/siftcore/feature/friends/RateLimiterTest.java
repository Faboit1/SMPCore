package net.siftvanilla.siftcore.feature.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The per-player and per-address request buckets, and the in-flight guard. */
class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();
    private final UUID alt1 = UUID.randomUUID();
    private final UUID alt2 = UUID.randomUUID();

    @Test
    void fivePerMinutePerPlayer() {
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            assertEquals(Duration.ZERO, this.limiter.tryAcquire(this.alt1, null, 5, now + i));
        }
        Duration wait = this.limiter.tryAcquire(this.alt1, null, 5, now + 10);
        assertTrue(wait.toMillis() > 59_000 && wait.toMillis() <= 60_000, "about a minute: " + wait);
        assertEquals(Duration.ZERO, this.limiter.tryAcquire(this.alt1, null, 5, now + RateLimiter.WINDOW_MILLIS + 1),
            "the oldest left the window");
    }

    @Test
    void altsShareTheirAddressBucket() {
        long now = 5_000_000L;
        for (int i = 0; i < 3; i++) {
            assertEquals(Duration.ZERO, this.limiter.tryAcquire(this.alt1, "ip-hash", 5, now));
        }
        assertEquals(Duration.ZERO, this.limiter.tryAcquire(this.alt2, "ip-hash", 5, now));
        assertEquals(Duration.ZERO, this.limiter.tryAcquire(this.alt2, "ip-hash", 5, now));
        assertFalse(this.limiter.tryAcquire(this.alt2, "ip-hash", 5, now).isZero(), "five from one address");
        assertEquals(Duration.ZERO, this.limiter.tryAcquire(this.alt2, "other-hash", 5, now), "a refused try takes nothing");
    }

    @Test
    void pruneForgetsQuietBuckets() {
        this.limiter.tryAcquire(this.alt1, "hash", 5, 0);
        assertEquals(2, this.limiter.size());
        this.limiter.prune(RateLimiter.WINDOW_MILLIS + 1);
        assertEquals(0, this.limiter.size());
    }

    @Test
    void inFlightGuardsOnePairAtATime() {
        InFlight guard = new InFlight();
        assertTrue(guard.tryAcquire(this.alt1, this.alt2));
        assertFalse(guard.tryAcquire(this.alt1, this.alt2), "a second action on the pair waits");
        assertTrue(guard.tryAcquire(this.alt2, this.alt1), "per acting player");
        assertTrue(guard.busy(this.alt1, this.alt2));
        guard.release(this.alt1, this.alt2);
        assertTrue(guard.tryAcquire(this.alt1, this.alt2));
        guard.release(this.alt1, this.alt2);
        guard.release(this.alt2, this.alt1);
        assertEquals(0, guard.size());
        assertEquals(FriendService.pairKey(this.alt1, this.alt2), FriendService.pairKey(this.alt2, this.alt1), "staff pair key is unordered");
    }
}
