package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Kill effects are limited per killer and on the whole server. */
class RateGateTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    @Test
    void onePlayerWaitsOutTheCooldown() {
        RateGate gate = new RateGate();
        assertEquals(RateGate.Outcome.OK, gate.tryAcquire(A, 10_000, 3_000, 10));
        assertEquals(RateGate.Outcome.COOLDOWN, gate.tryAcquire(A, 12_999, 3_000, 10));
        assertEquals(RateGate.Outcome.OK, gate.tryAcquire(A, 13_000, 3_000, 10));
    }

    @Test
    void theServerWideLimitHoldsPerSecond() {
        RateGate gate = new RateGate();
        assertEquals(RateGate.Outcome.OK, gate.tryAcquire(A, 1_000, 0, 2));
        assertEquals(RateGate.Outcome.OK, gate.tryAcquire(B, 1_100, 0, 2));
        assertEquals(RateGate.Outcome.BUSY, gate.tryAcquire(C, 1_500, 0, 2));
        assertEquals(RateGate.Outcome.OK, gate.tryAcquire(C, 2_000, 0, 2), "the first one left the window");
        assertEquals(RateGate.Outcome.BUSY, gate.tryAcquire(A, 2_050, 0, 2));
    }

    @Test
    void refusedAttemptsCountForNothing() {
        RateGate gate = new RateGate();
        assertEquals(RateGate.Outcome.OK, gate.tryAcquire(A, 0, 1_000, 1));
        assertEquals(RateGate.Outcome.BUSY, gate.tryAcquire(B, 100, 1_000, 1));
        assertEquals(RateGate.Outcome.OK, gate.tryAcquire(B, 1_000, 1_000, 1), "B's refused try did not start its cooldown");
    }

    @Test
    void zeroTurnsALimitOff() {
        RateGate gate = new RateGate();
        for (int i = 0; i < 50; i++) {
            assertEquals(RateGate.Outcome.OK, gate.tryAcquire(A, 5_000 + i, 0, 0));
        }
    }

    @Test
    void sweepingForgetsOldPlayers() {
        RateGate gate = new RateGate();
        gate.tryAcquire(A, 0, 1_000, 0);
        gate.tryAcquire(B, 50_000, 1_000, 0);
        gate.sweep(60_000, 30_000);
        assertEquals(1, gate.tracked());
        gate.forget(B);
        assertEquals(0, gate.tracked());
    }
}
