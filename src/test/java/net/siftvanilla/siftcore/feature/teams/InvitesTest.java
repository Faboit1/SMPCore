package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Invites: one per team and player, expiry after the configured time, single use. */
class InvitesTest {

    private static final long TTL = 120_000;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void anInviteIsValidUntilItExpires() {
        Invites invites = new Invites();
        assertTrue(invites.add(1, this.bob, this.alice, 1_000, TTL));
        assertEquals(Invites.State.VALID, invites.state(this.bob, 1, 1_000));
        assertEquals(Invites.State.VALID, invites.state(this.bob, 1, 1_000 + TTL - 1));
        assertEquals(Invites.State.EXPIRED, invites.state(this.bob, 1, 1_000 + TTL), "expires exactly after two minutes");
        assertNull(invites.get(this.bob, 1, 1_000 + TTL));
        assertEquals(Invites.State.NONE, invites.state(this.bob, 2, 1_000));
        assertEquals(TTL - 500, invites.get(this.bob, 1, 1_500).remainingMillis(1_500));
    }

    @Test
    void oneOpenInvitePerTeamButSeveralTeams() {
        Invites invites = new Invites();
        assertTrue(invites.add(1, this.bob, this.alice, 0, TTL));
        assertFalse(invites.add(1, this.bob, this.alice, 10, TTL), "a second invite from the same team is refused");
        assertTrue(invites.add(2, this.bob, this.alice, 20, TTL));
        assertEquals(List.of(1L, 2L), invites.pendingFor(this.bob, 30).stream().map(Invites.Invite::team).toList());
        assertTrue(invites.add(1, this.bob, this.alice, TTL + 1, TTL), "after expiry the team can invite again");
        assertEquals(1, invites.pendingFromTeam(1, TTL + 2));
    }

    @Test
    void anInviteCanBeUsedOnce() {
        Invites invites = new Invites();
        invites.add(1, this.bob, this.alice, 0, TTL);
        Invites.Invite taken = invites.take(this.bob, 1, 5);
        assertNotNull(taken);
        assertEquals(this.alice, taken.inviter());
        assertNull(invites.take(this.bob, 1, 6));
        assertEquals(Invites.State.NONE, invites.state(this.bob, 1, 6));
    }

    @Test
    void takingAnExpiredInviteRemovesItAndReturnsNothing() {
        Invites invites = new Invites();
        invites.add(1, this.bob, this.alice, 0, TTL);
        assertNull(invites.take(this.bob, 1, TTL));
        assertEquals(Invites.State.NONE, invites.state(this.bob, 1, TTL));
        assertEquals(0, invites.size());
    }

    @Test
    void concurrentAcceptsUseTheInviteOnce() throws Exception {
        Invites invites = new Invites();
        invites.add(1, this.bob, this.alice, 0, TTL);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < 64; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (invites.take(this.bob, 1, 1) != null) {
                    winners.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, winners.get());
    }

    @Test
    void sweepAndClear() {
        Invites invites = new Invites();
        UUID carl = UUID.randomUUID();
        invites.add(1, this.bob, this.alice, 0, TTL);
        invites.add(2, this.bob, this.alice, 0, 10);
        invites.add(1, carl, this.alice, 0, TTL);
        invites.sweep(50);
        assertEquals(2, invites.size(), "only the expired invite is swept");
        invites.clearTeam(1);
        assertEquals(0, invites.size());
        invites.add(3, carl, this.alice, 100, TTL);
        invites.clearInvitee(carl);
        assertTrue(invites.pendingFor(carl, 100).isEmpty());
    }
}
