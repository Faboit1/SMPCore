package net.siftvanilla.siftcore.feature.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Dupe audit R15: today's /pay total must survive a quit. Payments apply at once and are stored later, so reading the
 * total back from the ledger on rejoin could miss the newest ones and hand out the limit again.
 */
class PayLimitsTest {

    /** A clock that can be moved forward. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-10T12:00:00Z"));
    private final PayLimits limits = new PayLimits(null, this.clock);

    @Test
    void todaysTotalStaysWhenThePlayerLeaves() {
        UUID payer = UUID.randomUUID();
        this.limits.add(payer, 240_000);
        this.limits.forget(payer);
        assertEquals(240_000, this.limits.sent(payer), "a quit and rejoin does not reset the day's total");
        this.limits.add(payer, 10_000);
        assertEquals(250_000, this.limits.sent(payer));
    }

    @Test
    void pastDaysAreDroppedForEveryone() {
        UUID early = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        this.limits.add(early, 100_000);
        this.clock.now = Instant.parse("2026-10-11T00:00:01Z");
        assertEquals(0, this.limits.sent(early), "a new day starts at 0");
        this.limits.add(other, 5_000);
        this.limits.forget(other);
        assertEquals(5_000, this.limits.sent(other));
        this.clock.now = Instant.parse("2026-10-10T23:00:00Z");
        assertEquals(0, this.limits.sent(early), "yesterday's total was dropped at the quit");
    }
}
