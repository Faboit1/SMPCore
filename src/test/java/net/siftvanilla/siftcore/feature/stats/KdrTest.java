package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class KdrTest {

    @Test
    void noDeathsCountsAsOne() {
        assertEquals("0.00", Kdr.format(0, 0));
        assertEquals("7.00", Kdr.format(7, 0));
        assertEquals("7.00", Kdr.format(7, 1));
        assertEquals(7.0, Kdr.value(7, 0));
    }

    @Test
    void alwaysTwoDecimalsRoundedHalfUp() {
        assertEquals("2.50", Kdr.format(5, 2));
        assertEquals("0.33", Kdr.format(1, 3));
        assertEquals("0.67", Kdr.format(2, 3));
        assertEquals("0.13", Kdr.format(1, 8));
        assertEquals("1.00", Kdr.format(3, 3));
        assertEquals("1234.57", Kdr.format(123_457, 100));
        assertEquals("0.01", Kdr.format(1, 199));
        assertEquals("0.00", Kdr.format(1, 201));
    }

    @Test
    void negativeInputsAreTreatedAsZeroKillsAndOneDeath() {
        assertEquals("0.00", Kdr.format(-5, 3));
        assertEquals("4.00", Kdr.format(4, -2));
    }

    @Test
    void compareIsExactWhereDoublesAndRoundingAreNot() {
        assertEquals(0, Kdr.compare(1, 3, 2, 6));
        assertEquals(0, Kdr.compare(5, 0, 5, 1));
        assertTrue(Kdr.compare(2, 1, 1, 1) > 0);
        assertTrue(Kdr.compare(1, 2, 1, 1) < 0);
        // Both show as 2.00 but are not equal.
        assertEquals(Kdr.format(2001, 1000), Kdr.format(2000, 1000));
        assertTrue(Kdr.compare(2001, 1000, 2000, 1000) > 0);
        // Ratios that only differ far past the fourth decimal.
        assertTrue(Kdr.compare(1_000_000_001L, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L) > 0);
        // Products beyond a long still compare exactly.
        assertTrue(Kdr.compare(Long.MAX_VALUE, 2, Long.MAX_VALUE - 1, 2) > 0);
        assertEquals(0, Kdr.compare(Long.MAX_VALUE, 3, Long.MAX_VALUE, 3));
    }

    @Test
    void compareAgreesWithExactFractions() {
        Random random = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            long ka = random.nextInt(5_000);
            long da = random.nextInt(5_000);
            long kb = random.nextInt(5_000);
            long db = random.nextInt(5_000);
            int expected = Long.signum(ka * Math.max(1, db) - kb * Math.max(1, da));
            assertEquals(expected, Integer.signum(Kdr.compare(ka, da, kb, db)), ka + "/" + da + " vs " + kb + "/" + db);
            assertEquals(-expected, Integer.signum(Kdr.compare(kb, db, ka, da)));
        }
    }
}
