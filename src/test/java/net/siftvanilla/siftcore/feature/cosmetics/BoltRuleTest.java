package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The visual bolt holds off for everyone who could see it, not only for players within the effect range. */
class BoltRuleTest {

    /** A player: where they stand and whether they want kill effects. */
    private record Watcher(double x, double y, double z, boolean wants) {
    }

    private static boolean clear(double reach, Watcher... watchers) {
        return BoltRule.clear(100.0, -50.0, reach, List.of(watchers), Watcher::x, Watcher::z, Watcher::wants);
    }

    @Test
    void theReachIsTheViewDistanceWhenThatIsFurtherThanTheRange() {
        assertEquals(6 * 16 + BoltRule.MARGIN, BoltRule.reach(32, 6), "view distance 6, the live server");
        assertEquals(10 * 16 + BoltRule.MARGIN, BoltRule.reach(32, 10));
        assertEquals(96 + BoltRule.MARGIN, BoltRule.reach(96, 2), "a range further than the view distance");
        assertEquals(32 + BoltRule.MARGIN, BoltRule.reach(32, -1));
    }

    @Test
    void anOptedOutPlayerBeyondTheEffectRangeButInSightStopsTheBolt() {
        double reach = BoltRule.reach(32, 6);
        // 50 blocks away: outside the 32 block effect range, inside the default entity tracking range of 64.
        assertFalse(clear(reach, new Watcher(100.0, 64.0, 0.0, false)));
        assertFalse(clear(reach, new Watcher(100.0 + 70.0, 64.0, -50.0 + 70.0, false)), "99 blocks across the ground");
        assertTrue(clear(reach, new Watcher(100.0 + 80.0, 64.0, -50.0 + 80.0, false)), "113 blocks: out of sight");
    }

    @Test
    void heightDoesNotCountLikeTheEntityTracker() {
        double reach = BoltRule.reach(32, 6);
        assertFalse(clear(reach, new Watcher(100.0, 300.0, -50.0, false)), "straight above, far up");
    }

    @Test
    void theBoltStrikesWhenEveryoneInSightWantsEffects() {
        double reach = BoltRule.reach(32, 6);
        assertTrue(clear(reach));
        assertTrue(clear(reach, new Watcher(100.0, 64.0, -50.0, true), new Watcher(140.0, 64.0, -10.0, true)));
        assertFalse(clear(reach, new Watcher(100.0, 64.0, -50.0, true), new Watcher(140.0, 64.0, -10.0, false)), "one opted out");
    }

    @Test
    void theOptOutIsOnlyAskedWithinReach() {
        double reach = BoltRule.reach(32, 6);
        boolean clear = BoltRule.clear(0.0, 0.0, reach, List.of(new Watcher(5_000.0, 64.0, 0.0, false)), Watcher::x, Watcher::z, watcher -> {
            throw new AssertionError("asked about a player out of sight");
        });
        assertTrue(clear);
    }
}
