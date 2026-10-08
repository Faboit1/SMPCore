package net.siftvanilla.siftcore.feature.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.siftvanilla.siftcore.feature.afk.AfkClock.Change;
import net.siftvanilla.siftcore.feature.afk.AfkClock.Timing;
import org.junit.jupiter.api.Test;

/** When players become AFK, come back, are warned and are kicked. */
class AfkClockTest {

    private static final long MINUTE = 60_000;
    /** AFK after 5m, kicked after 30m AFK outside the zone, warned 1m before, 3s grace, motion counts for 15m. */
    private static final Timing TIMING = new Timing(5 * MINUTE, 30 * MINUTE, MINUTE, 3_000, 15 * MINUTE);
    private static final long START = 1_000_000_000L;

    @Test
    void becomesAfkAfterTheInactivityTimeAndComesBackOnActivity() {
        AfkClock clock = new AfkClock(START);
        assertEquals(Change.NONE, clock.tick(START + 5 * MINUTE - 1, TIMING, false, true));
        assertEquals(Change.BECAME_AFK, clock.tick(START + 5 * MINUTE, TIMING, false, true));
        assertTrue(clock.afk());
        assertFalse(clock.manual());
        assertEquals(START + 5 * MINUTE, clock.afkSince());
        assertEquals(Change.NONE, clock.tick(START + 6 * MINUTE, TIMING, false, true), "only once");
        assertEquals(Change.RETURNED, clock.activity(START + 7 * MINUTE, true, TIMING));
        assertFalse(clock.afk());
        assertEquals(-1, clock.afkSince());
        assertEquals(Change.NONE, clock.tick(START + 11 * MINUTE, TIMING, false, true), "the timer started over");
    }

    @Test
    void activityKeepsAPlayerActive() {
        AfkClock clock = new AfkClock(START);
        for (int minute = 1; minute <= 12; minute++) {
            assertEquals(Change.NONE, clock.activity(START + minute * MINUTE, false, TIMING));
            assertEquals(Change.NONE, clock.tick(START + minute * MINUTE + 30_000, TIMING, false, true));
        }
        assertFalse(clock.afk());
    }

    @Test
    void motionAloneCountsOnlyForAWhileAfterTheLastAction() {
        AfkClock clock = new AfkClock(START);
        // A script turning the view every minute, convincingly, with no chat, command or click.
        Change change = Change.NONE;
        long minute = 0;
        while (change != Change.BECAME_AFK && minute < 60) {
            minute++;
            clock.activity(START + minute * MINUTE, true, TIMING);
            change = clock.tick(START + minute * MINUTE + 1, TIMING, false, true);
        }
        assertEquals(Change.BECAME_AFK, change, "motion alone ends up AFK");
        assertEquals(20, minute, "15 minutes of motion after the last action, then the 5 minute AFK time");
        assertEquals(Change.NONE, clock.activity(START + 21 * MINUTE, true, TIMING), "more motion doesn't bring them back");
        assertTrue(clock.afk());
        assertEquals(Change.RETURNED, clock.activity(START + 22 * MINUTE, false, TIMING), "an action does");
        assertEquals(Change.RETURNED, returnAfterMotionWithinLimit(), "within the limit, motion brings players back");
    }

    private static Change returnAfterMotionWithinLimit() {
        AfkClock clock = new AfkClock(START);
        clock.tick(START + 5 * MINUTE, TIMING, false, true);
        return clock.activity(START + 6 * MINUTE, true, TIMING);
    }

    @Test
    void noMotionLimitWhenTurnedOff() {
        Timing unlimited = new Timing(5 * MINUTE, 30 * MINUTE, MINUTE, 3_000, 0);
        AfkClock clock = new AfkClock(START);
        for (int minute = 1; minute <= 120; minute++) {
            clock.activity(START + minute * MINUTE, true, unlimited);
            assertEquals(Change.NONE, clock.tick(START + minute * MINUTE + 1, unlimited, false, true));
        }
    }

    @Test
    void afkCommandHasAGraceSoTypingItDoesNotUndoIt() {
        AfkClock clock = new AfkClock(START);
        assertEquals(Change.BECAME_AFK, clock.goAfk(START + 1_000, TIMING));
        assertTrue(clock.manual());
        assertEquals(Change.NONE, clock.activity(START + 2_000, false, TIMING), "the keys of the command itself");
        assertEquals(Change.NONE, clock.activity(START + 3_999, true, TIMING));
        assertTrue(clock.afk());
        assertEquals(Change.RETURNED, clock.activity(START + 4_000, true, TIMING), "after the grace, moving brings them back");
        assertEquals(Change.BECAME_AFK, clock.goAfk(START + 5_000, TIMING));
        assertEquals(Change.RETURNED, clock.comeBack(START + 5_100), "/afk again comes back at once");
        assertEquals(Change.NONE, clock.comeBack(START + 5_200));
    }

    @Test
    void kickCountsOnlyTimeAfkOutsideTheZone() {
        AfkClock clock = new AfkClock(START);
        long afkAt = START + 5 * MINUTE;
        assertEquals(Change.BECAME_AFK, clock.tick(afkAt, TIMING, true, true));
        // An hour in the zone: never warned or kicked.
        for (int minute = 1; minute <= 60; minute++) {
            assertEquals(Change.NONE, clock.tick(afkAt + minute * MINUTE, TIMING, true, true));
        }
        assertEquals(-1, clock.untilKick(afkAt + 60 * MINUTE, TIMING));
        // Pushed out of the zone while AFK: the kick clock starts from zero there.
        long out = afkAt + 61 * MINUTE;
        assertEquals(Change.NONE, clock.tick(out, TIMING, false, true));
        assertEquals(30 * MINUTE, clock.untilKick(out, TIMING));
        assertEquals(Change.NONE, clock.tick(out + 29 * MINUTE - 1, TIMING, false, true));
        assertEquals(Change.KICK_WARNING, clock.tick(out + 29 * MINUTE, TIMING, false, true));
        assertEquals(Change.NONE, clock.tick(out + 29 * MINUTE + 30_000, TIMING, false, true), "warned once");
        assertEquals(Change.KICK, clock.tick(out + 30 * MINUTE, TIMING, false, true));
    }

    @Test
    void reenteringTheZoneResetsTheKickClock() {
        AfkClock clock = new AfkClock(START);
        long afkAt = START + 5 * MINUTE;
        assertEquals(Change.BECAME_AFK, clock.tick(afkAt, TIMING, false, true));
        clock.tick(afkAt + 1_000, TIMING, false, true);
        clock.tick(afkAt + 20 * MINUTE + 1_000, TIMING, false, true);
        assertEquals(10 * MINUTE, clock.untilKick(afkAt + 20 * MINUTE + 1_000, TIMING));
        clock.tick(afkAt + 21 * MINUTE, TIMING, true, true);
        assertEquals(-1, clock.untilKick(afkAt + 21 * MINUTE, TIMING), "no kick counts down in the zone");
        clock.tick(afkAt + 22 * MINUTE, TIMING, false, true);
        assertEquals(30 * MINUTE, clock.untilKick(afkAt + 22 * MINUTE, TIMING));
    }

    @Test
    void bypassAndDisabledKickNeverKick() {
        AfkClock bypass = new AfkClock(START);
        Timing noKick = new Timing(5 * MINUTE, 0, MINUTE, 3_000, 15 * MINUTE);
        AfkClock disabled = new AfkClock(START);
        for (int minute = 5; minute <= 300; minute++) {
            assertTrue(bypass.tick(START + minute * MINUTE, TIMING, false, false) != Change.KICK);
            Change change = disabled.tick(START + minute * MINUTE, noKick, false, true);
            assertTrue(change != Change.KICK && change != Change.KICK_WARNING);
        }
    }
}
