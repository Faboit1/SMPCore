package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import org.junit.jupiter.api.Test;

/** Tag timing: the countdown, the single end message, refreshes, the kill-credit window and the shared tag map. */
class TagTimingTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void secondsAreRoundedUp() {
        assertEquals(20, TagTicker.secondsLeft(NOW + 20_000, NOW));
        assertEquals(20, TagTicker.secondsLeft(NOW + 19_001, NOW));
        assertEquals(1, TagTicker.secondsLeft(NOW + 1, NOW), "the last millisecond still shows 1s");
        assertEquals(0, TagTicker.secondsLeft(NOW, NOW));
        assertEquals(0, TagTicker.secondsLeft(NOW - 5_000, NOW));
    }

    @Test
    void countdownThenOneEndMessage() {
        TagTicker ticker = new TagTicker();
        UUID player = UUID.randomUUID();
        long until = NOW + 3_000;
        List<Long> shown = new ArrayList<>();
        int ended = 0;
        for (long t = NOW; t <= NOW + 6_000; t += 1_000) {
            TagTicker.Update update = ticker.tick(t < until ? Map.of(player, until) : Map.of(player, until), t);
            update.shows().forEach(show -> shown.add(show.secondsLeft()));
            ended += update.ended().size();
        }
        assertEquals(List.of(3L, 2L, 1L), shown);
        assertEquals(1, ended, "the end is announced exactly once, even while the expired tag is still in the map");
    }

    @Test
    void aRefreshKeepsThePlayerInCombat() {
        TagTicker ticker = new TagTicker();
        UUID player = UUID.randomUUID();
        assertEquals(1, ticker.tick(Map.of(player, NOW + 2_000), NOW).shows().size());
        // Hit again just before the tag ran out: 20 seconds from the new hit.
        TagTicker.Update update = ticker.tick(Map.of(player, NOW + 21_500), NOW + 1_500);
        assertEquals(20, update.shows().getFirst().secondsLeft());
        assertTrue(update.ended().isEmpty());
    }

    @Test
    void untaggedPlayersEndAndForgottenPlayersDoNot() {
        TagTicker ticker = new TagTicker();
        UUID untagged = UUID.randomUUID();
        UUID died = UUID.randomUUID();
        ticker.tick(Map.of(untagged, NOW + 10_000, died, NOW + 10_000), NOW);
        ticker.forget(died);
        TagTicker.Update update = ticker.tick(Map.of(), NOW + 1_000);
        assertEquals(List.of(untagged), update.ended(), "removed by staff: told; died or left: not told");
        assertEquals(0, ticker.size());
    }

    @Test
    void aPlayerTaggedBetweenTicksIsToldWhenStaffUntagThem() {
        TagTicker ticker = new TagTicker();
        UUID player = UUID.randomUUID();
        ticker.shown(player);
        assertEquals(List.of(player), ticker.tick(Map.of(), NOW).ended());
    }

    @Test
    void killCreditWindowMatchesTheTag() {
        HitLog<String> hits = new HitLog<>();
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        long window = Duration.ofSeconds(20).toMillis();
        hits.record(victim, attacker, NOW, "sword");
        HitLog.Hit<String> hit = hits.within(victim, NOW + 19_999, window);
        assertNotNull(hit, "a fall 19.999s after the hit is credited");
        assertEquals(attacker, hit.attacker());
        assertEquals("sword", hit.weapon());
        assertNull(hits.within(victim, NOW + 20_000, window), "the tag is over at 20s, so is the credit");
        assertNull(hits.within(UUID.randomUUID(), NOW, window));
    }

    @Test
    void theLastHitWinsAndOlderHitsNeverReplaceNewerOnes() {
        HitLog<String> hits = new HitLog<>();
        UUID victim = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        hits.record(victim, first, NOW, "bow");
        hits.record(victim, second, NOW + 2_000, "axe");
        hits.record(victim, first, NOW + 1_000, "bow");
        assertEquals(second, hits.attackerWithin(victim, NOW + 3_000, 20_000));
        hits.forget(victim);
        assertNull(hits.within(victim, NOW + 3_000, 20_000));
    }

    @Test
    void pruningDropsOnlyOldHits() {
        HitLog<String> hits = new HitLog<>();
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        hits.record(old, UUID.randomUUID(), NOW - 30_000, null);
        hits.record(fresh, UUID.randomUUID(), NOW - 1_000, null);
        hits.prune(NOW - 20_000);
        assertEquals(1, hits.size());
        assertNotNull(hits.within(fresh, NOW, 20_000));
    }

    @Test
    void recentPairsPruneAndStayDirectional() {
        RecentPairs pairs = new RecentPairs();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        pairs.record(a, b, NOW - Duration.ofHours(25).toMillis());
        pairs.record(b, a, NOW);
        pairs.record(b, a, NOW - 5_000);
        assertEquals(NOW, pairs.last(b, a).getAsLong(), "an older record never replaces a newer one");
        pairs.prune(NOW - Duration.ofHours(24).toMillis());
        assertTrue(pairs.last(a, b).isEmpty());
        assertEquals(1, pairs.size());
    }

    @Test
    void sharedTagsRefreshAndExpire() throws Exception {
        CombatTags tags = new CombatTags();
        UUID player = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        assertTrue(tags.tag(player, attacker, Duration.ofMillis(150)), "a new tag");
        assertFalse(tags.tag(player, attacker, Duration.ofMillis(150)), "a refresh");
        assertTrue(tags.tagged(player));
        assertEquals(attacker, tags.get(player).lastAttacker());
        Thread.sleep(200);
        assertFalse(tags.tagged(player));
        assertTrue(tags.remaining(player).isZero());
        assertTrue(tags.tag(player, null, Duration.ofSeconds(5)), "tagging after expiry counts as new");
    }

    @Test
    void concurrentHitsKeepTheNewest() throws Exception {
        HitLog<Integer> hits = new HitLog<>();
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 2_000; i++) {
            int at = i;
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                hits.record(victim, attacker, NOW + at, at);
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1_999, hits.within(victim, NOW + 2_000, 20_000).weapon());
    }
}
