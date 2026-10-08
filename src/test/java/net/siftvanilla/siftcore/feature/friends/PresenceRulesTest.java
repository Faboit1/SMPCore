package net.siftvanilla.siftcore.feature.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Join and leave alerts: who is told, relog grace, startup quiet, coalescing and request batching. */
class PresenceRulesTest {

    private static final long GRACE = 120_000L;
    private static final long QUIET = 60_000L;
    private static final long START = 1_000_000_000L;

    private final UUID alex = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID();

    @Test
    void joinIsAnnouncedOnlyWhenItShouldBe() {
        long now = START + 10 * QUIET;
        assertTrue(PresenceRules.announceJoin(true, false, 0, now, GRACE, START, QUIET));
        assertFalse(PresenceRules.announceJoin(false, false, 0, now, GRACE, START, QUIET), "announce turned off");
        assertFalse(PresenceRules.announceJoin(true, true, 0, now, GRACE, START, QUIET), "vanished");
        assertFalse(PresenceRules.announceJoin(true, false, now - 30_000, now, GRACE, START, QUIET), "a relog");
        assertTrue(PresenceRules.announceJoin(true, false, now - GRACE, now, GRACE, START, QUIET), "after the grace");
        assertFalse(PresenceRules.announceJoin(true, false, 0, START + 59_000, GRACE, START, QUIET), "right after the start");
        assertTrue(PresenceRules.announceJoin(true, false, 0, START + QUIET, GRACE, START, QUIET));
    }

    @Test
    void viewerChoosesWhatTheyHear() {
        assertTrue(PresenceRules.viewerWants(FriendPrefs.JoinAlerts.ALL, false, false));
        assertFalse(PresenceRules.viewerWants(FriendPrefs.JoinAlerts.ALL, true, true), "ignored friends are never announced");
        assertTrue(PresenceRules.viewerWants(FriendPrefs.JoinAlerts.FAVOURITES, true, false));
        assertFalse(PresenceRules.viewerWants(FriendPrefs.JoinAlerts.FAVOURITES, false, false));
        assertFalse(PresenceRules.viewerWants(FriendPrefs.JoinAlerts.OFF, true, false));
    }

    @Test
    void leaveAlertIsDroppedWhenTheyCameBack() {
        assertTrue(PresenceRules.announceLeave(true, false, false));
        assertFalse(PresenceRules.announceLeave(true, false, true), "rejoined within the leave delay");
        assertFalse(PresenceRules.announceLeave(true, true, false), "vanished when leaving");
        assertFalse(PresenceRules.announceLeave(false, false, false), "announce off");
    }

    @Test
    void joinsAreCoalescedPerViewer() {
        PresenceRules.JoinBatches batches = new PresenceRules.JoinBatches();
        assertTrue(batches.add(this.viewer, this.alex, false), "the first join opens a window");
        assertFalse(batches.add(this.viewer, this.bob, true), "the second rides along");
        assertFalse(batches.add(this.viewer, this.alex, false), "a double join counts once");
        PresenceRules.JoinBatches.Batch batch = batches.take(this.viewer);
        assertEquals(List.of(this.alex, this.bob), batch.joiners());
        assertEquals(Set.of(this.bob), batch.favourites());
        assertTrue(batch.hasFavourite());
        assertTrue(batches.take(this.viewer).joiners().isEmpty());
        assertTrue(batches.add(this.viewer, this.alex, false), "a new window after the flush");
        batches.forget(this.viewer);
        assertEquals(0, batches.size());
        // A window whose flush could not be scheduled (the viewer was leaving) is forgotten at once, so it can't
        // swallow the joins of the viewer's next session: the next join opens (and schedules) a window again.
        assertTrue(batches.add(this.viewer, this.alex, false));
        batches.forget(this.viewer);
        assertTrue(batches.add(this.viewer, this.bob, false), "a forgotten window doesn't hold later joins back");
    }

    @Test
    void relogTimesAreRemembered() {
        PresenceRules.LastLeave leaves = new PresenceRules.LastLeave();
        leaves.record(this.alex, 500);
        assertEquals(500, leaves.get(this.alex));
        assertEquals(0, leaves.get(this.bob));
        leaves.prune(600);
        assertEquals(0, leaves.get(this.alex));
    }

    @Test
    void requestAlertsBatchAfterTheFirst() {
        RequestBatcher batcher = new RequestBatcher();
        assertEquals(RequestBatcher.Decision.SHOW_NOW, batcher.offer(this.viewer, this.alex, true), "first in a quiet period");
        assertEquals(RequestBatcher.Decision.COLLECTED, batcher.offer(this.viewer, this.bob, true));
        UUID third = UUID.randomUUID();
        assertEquals(RequestBatcher.Decision.COLLECTED, batcher.offer(this.viewer, third, true));
        assertEquals(List.of(this.bob, third), batcher.close(this.viewer), "summed when the window closes");
        assertEquals(RequestBatcher.Decision.COLLECTED, batcher.offer(this.viewer, this.alex, true), "the window stays open a round");
        assertEquals(List.of(this.alex), batcher.close(this.viewer));
        assertEquals(List.of(), batcher.close(this.viewer), "an empty round ends the batch");
        assertEquals(0, batcher.size());
        assertEquals(RequestBatcher.Decision.SHOW_NOW, batcher.offer(this.viewer, this.bob, true), "quiet again");
        assertEquals(RequestBatcher.Decision.SHOW_NOW, batcher.offer(this.bob, this.alex, false), "batching off");
    }
}
