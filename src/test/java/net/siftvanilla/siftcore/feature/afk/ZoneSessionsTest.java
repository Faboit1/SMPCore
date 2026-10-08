package net.siftvanilla.siftcore.feature.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.siftvanilla.siftcore.feature.afk.ZoneSessions.Block;
import net.siftvanilla.siftcore.feature.afk.ZoneSessions.State;
import net.siftvanilla.siftcore.feature.afk.ZoneSessions.Status;
import org.junit.jupiter.api.Test;

/** AFK zone timing (continuous presence, combat, the daily limit) and one earning account per connection. */
class ZoneSessionsTest {

    private static final long INTERVAL = 60_000;
    private static final long T0 = 5_000_000L;

    /** Updates once a second from {@code from} to {@code to} (inclusive) and counts the rewards that came due. */
    private static int run(ZoneSessions sessions, UUID player, long from, long to, Block block) {
        int due = 0;
        for (long now = from; now <= to; now += 1_000) {
            Status status = sessions.update(player, now, INTERVAL, block);
            if (status != null && status.due()) {
                due++;
            }
        }
        return due;
    }

    @Test
    void paysOncePerIntervalOfContinuousPresence() {
        ZoneSessions sessions = new ZoneSessions();
        UUID player = UUID.randomUUID();
        sessions.enter(player, "ip-a", T0);
        Status first = sessions.update(player, T0 + 1_000, INTERVAL, Block.NONE);
        assertEquals(State.EARNING, first.state());
        assertFalse(first.due());
        assertEquals(59_000, first.nextInMillis());
        assertEquals(0, run(sessions, player, T0 + 2_000, T0 + 59_000, Block.NONE));
        assertTrue(sessions.update(player, T0 + 60_000, INTERVAL, Block.NONE).due(), "due after exactly one interval");
        assertEquals(INTERVAL, sessions.update(player, T0 + 60_000, INTERVAL, Block.NONE).nextInMillis());
        assertEquals(9, run(sessions, player, T0 + 61_000, T0 + 600_000, Block.NONE), "ten minutes pay ten times");
    }

    @Test
    void leavingThrowsAwayTheProgress() {
        ZoneSessions sessions = new ZoneSessions();
        UUID player = UUID.randomUUID();
        sessions.enter(player, "ip-a", T0);
        run(sessions, player, T0, T0 + 50_000, Block.NONE);
        sessions.leave(player, T0 + 50_000);
        assertNull(sessions.update(player, T0 + 51_000, INTERVAL, Block.NONE), "not in the zone");
        sessions.enter(player, "ip-a", T0 + 55_000);
        assertEquals(0, run(sessions, player, T0 + 55_000, T0 + 114_000, Block.NONE), "50s before leaving don't count");
        assertTrue(sessions.update(player, T0 + 115_000, INTERVAL, Block.NONE).due());
    }

    @Test
    void combatTagRestartsTheInterval() {
        ZoneSessions sessions = new ZoneSessions();
        UUID player = UUID.randomUUID();
        sessions.enter(player, "ip-a", T0);
        run(sessions, player, T0, T0 + 40_000, Block.NONE);
        Status tagged = sessions.update(player, T0 + 41_000, INTERVAL, Block.COMBAT);
        assertEquals(State.COMBAT, tagged.state());
        assertFalse(tagged.due());
        assertEquals(0, run(sessions, player, T0 + 42_000, T0 + 70_000, Block.COMBAT), "nothing while tagged");
        assertEquals(0, run(sessions, player, T0 + 71_000, T0 + 129_000, Block.NONE));
        assertTrue(sessions.update(player, T0 + 130_000, INTERVAL, Block.NONE).due(), "a full interval after the tag ended");
    }

    @Test
    void cappedAndLoadingPlayersDoNotEarn() {
        ZoneSessions sessions = new ZoneSessions();
        UUID player = UUID.randomUUID();
        sessions.enter(player, "ip-a", T0);
        assertEquals(State.LOADING, sessions.update(player, T0 + 1_000, INTERVAL, Block.LOADING).state());
        assertEquals(0, run(sessions, player, T0, T0 + 300_000, Block.CAPPED));
        assertEquals(State.CAPPED, sessions.update(player, T0 + 301_000, INTERVAL, Block.CAPPED).state());
    }

    @Test
    void aLongStallDoesNotPayABurst() {
        ZoneSessions sessions = new ZoneSessions();
        UUID player = UUID.randomUUID();
        sessions.enter(player, "ip-a", T0);
        assertTrue(sessions.update(player, T0 + 10 * INTERVAL, INTERVAL, Block.NONE).due(), "one reward for the stall");
        assertFalse(sessions.update(player, T0 + 10 * INTERVAL + 1_000, INTERVAL, Block.NONE).due(), "not nine more at once");
    }

    // ------------------------------------------------------------------ one account per connection

    @Test
    void onlyOneAccountPerConnectionEarns() {
        ZoneSessions sessions = new ZoneSessions();
        UUID main = UUID.randomUUID();
        UUID alt = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        sessions.enter(main, "ip-a", T0);
        sessions.enter(alt, "ip-a", T0 + 1_000);
        sessions.enter(friend, "ip-b", T0 + 2_000);
        assertEquals(State.WAITING_ALT, sessions.update(alt, T0 + 3_000, INTERVAL, Block.NONE).state());
        assertEquals(main, sessions.holderFor(alt));
        assertNull(sessions.holderFor(main));
        assertEquals(1, run(sessions, main, T0 + 3_000, T0 + 61_000, Block.NONE));
        assertEquals(0, run(sessions, alt, T0 + 3_000, T0 + 300_000, Block.NONE), "the alt waits");
        assertEquals(1, run(sessions, friend, T0 + 3_000, T0 + 62_000, Block.NONE), "another connection earns too");
        assertNull(sessions.check());
    }

    @Test
    void theNextAccountTakesOverWithAFreshInterval() {
        ZoneSessions sessions = new ZoneSessions();
        UUID main = UUID.randomUUID();
        UUID alt1 = UUID.randomUUID();
        UUID alt2 = UUID.randomUUID();
        sessions.enter(main, "ip-a", T0);
        sessions.enter(alt2, "ip-a", T0 + 2_000);
        sessions.enter(alt1, "ip-a", T0 + 1_000);
        run(sessions, alt1, T0, T0 + 100_000, Block.NONE);
        sessions.leave(main, T0 + 100_000);
        assertNull(sessions.holderFor(alt1), "the account that entered next takes over");
        assertEquals(alt1, sessions.holderFor(alt2));
        assertEquals(0, run(sessions, alt1, T0 + 100_000, T0 + 159_000, Block.NONE), "waiting time doesn't count");
        assertTrue(sessions.update(alt1, T0 + 160_000, INTERVAL, Block.NONE).due());
        sessions.leave(alt1, T0 + 161_000);
        assertNull(sessions.holderFor(alt2));
        assertNull(sessions.check());
        sessions.leave(alt2, T0 + 162_000);
        assertEquals(0, sessions.size());
        assertNull(sessions.check());
    }

    @Test
    void enteringTwiceChangesNothing() {
        ZoneSessions sessions = new ZoneSessions();
        UUID player = UUID.randomUUID();
        sessions.enter(player, "ip-a", T0);
        run(sessions, player, T0, T0 + 30_000, Block.NONE);
        sessions.enter(player, "ip-a", T0 + 30_000);
        assertTrue(sessions.update(player, T0 + 60_000, INTERVAL, Block.NONE).due(), "progress kept");
        sessions.clear();
        assertEquals(0, sessions.size());
        assertNull(sessions.update(player, T0 + 61_000, INTERVAL, Block.NONE));
    }

    @Test
    void concurrentEntriesFromOneConnectionLeaveExactlyOneHolder() throws Exception {
        ZoneSessions sessions = new ZoneSessions();
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            players.add(UUID.randomUUID());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger earning = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (UUID player : players) {
            futures.add(pool.submit(() -> {
                start.await();
                sessions.enter(player, "shared", T0);
                if (sessions.update(player, T0 + INTERVAL, INTERVAL, Block.NONE).state() == State.EARNING) {
                    earning.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, earning.get());
        assertNull(sessions.check());
        for (UUID player : players) {
            sessions.leave(player, T0 + 2 * INTERVAL);
            assertNull(sessions.check());
        }
    }

    // ------------------------------------------------------------------ the daily limit

    @Test
    void dailyLimitAllowance() {
        assertEquals(3, DailyCounter.allowance(0, 1_000, 3), "no limit");
        assertEquals(3, DailyCounter.allowance(100, 50, 3));
        assertEquals(2, DailyCounter.allowance(100, 98, 3), "the last reward of the day is cut to the limit");
        assertEquals(0, DailyCounter.allowance(100, 100, 3));
        assertEquals(0, DailyCounter.allowance(100, 150, 3), "after the limit was lowered");
    }

    @Test
    void dailyCounterRollsOverAtMidnightAndKeepsRewardsPaidWhileLoading() {
        DailyCounter counter = new DailyCounter();
        LocalDate monday = LocalDate.of(2026, 10, 5);
        assertFalse(counter.isLoaded());
        counter.add(monday, 2);
        counter.loaded(monday, 40);
        assertTrue(counter.isLoaded());
        assertEquals(42, counter.earned(monday), "stored total plus what was paid while it loaded");
        counter.add(monday, 3);
        counter.loaded(monday, 0);
        assertEquals(45, counter.earned(monday), "a second load changes nothing");
        assertEquals(0, counter.earned(monday.plusDays(1)), "a new day starts at zero");
        counter.add(monday.plusDays(1), 1);
        assertEquals(1, counter.earned(monday.plusDays(1)));
    }
}
