package net.siftvanilla.siftcore.feature.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Flight at spawn: the height limit, and a fall that began with flight turning off in the air stays protected until
 * the player lands, however long it takes (the old fixed 10 second window let a long fall kill), but never lingers for
 * a later fall.
 */
class FlightRulesTest {

    private static final long START = 1_700_000_000_000L;

    /** In the air, falling, nothing special. */
    private static FlightRules.Sample air(float fallDistance) {
        return new FlightRules.Sample(false, false, false, false, false, false, false, fallDistance);
    }

    @Test
    void theCeilingIsAboveTheSpawnPoint() {
        assertEquals(118.0, FlightRules.ceiling(70, 48));
        assertFalse(FlightRules.aboveCeiling(118.0, 118.0), "right at the limit is still fine");
        assertTrue(FlightRules.aboveCeiling(118.01, 118.0));
        assertFalse(FlightRules.aboveCeiling(5, FlightRules.ceiling(70, 1)));
    }

    @Test
    void aLongFallStaysProtectedUntilItLands() {
        FlightRules.Fall fall = new FlightRules.Fall(START, 0f);
        // A fall from far above: 30 seconds in the air (the old 10 second window ran out a third of the way down).
        float distance = 0;
        for (long t = 0; t <= 30_000; t += 250) {
            distance += 3.9f;
            assertFalse(fall.over(air(distance), START + t), "still falling at " + t + " ms");
        }
        assertTrue(fall.over(new FlightRules.Sample(true, true, false, false, false, false, false, distance), START + 30_250),
            "standing on solid ground again ends it");
    }

    @Test
    void aStaleGroundFlagInTheAirDoesNotEndIt() {
        FlightRules.Fall fall = new FlightRules.Fall(START, 0f);
        // The client said it was on the ground (a teleport into the air), but nothing solid is under the feet.
        assertFalse(fall.over(new FlightRules.Sample(true, false, false, false, false, false, false, 0f), START + 250));
        assertFalse(fall.over(air(0f), START + 12_000), "hovering still counts as the same fall");
        assertFalse(fall.over(air(12f), START + 12_700));
    }

    @Test
    void everyOtherWayAFallEndsEndsTheProtection() {
        FlightRules.Sample[] ends = {
            new FlightRules.Sample(false, false, true, false, false, false, false, 9f),   // water or lava
            new FlightRules.Sample(false, false, false, true, false, false, false, 9f),   // a ladder
            new FlightRules.Sample(false, false, false, false, true, false, false, 9f),   // an elytra: they steer now
            new FlightRules.Sample(false, false, false, false, false, true, false, 9f),   // a vehicle
            new FlightRules.Sample(false, false, false, false, false, false, true, 9f),   // flying again
        };
        for (FlightRules.Sample end : ends) {
            FlightRules.Fall fall = new FlightRules.Fall(START, 0f);
            assertFalse(fall.over(air(4f), START + 250));
            assertTrue(fall.over(end, START + 500), end.toString());
        }
        FlightRules.Fall reset = new FlightRules.Fall(START, 0f);
        assertFalse(reset.over(air(8f), START + 250));
        assertTrue(reset.over(air(0.5f), START + 500), "a fall distance that went back down means the fall ended (slime, cobweb)");
    }

    @Test
    void itNeverOutlivesTheBackstop() {
        FlightRules.Fall fall = new FlightRules.Fall(START, 0f);
        assertFalse(fall.over(air(1f), START + FlightRules.FALL_LIMIT_MILLIS));
        assertTrue(fall.over(air(2f), START + FlightRules.FALL_LIMIT_MILLIS + 1));
    }
}
