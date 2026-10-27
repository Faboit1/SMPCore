package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ChancesTest {

    private static long sum(long[] values) {
        return LongStream.of(values).sum();
    }

    @Test
    void shownChancesAlwaysAddUpToExactlyOneHundredPercent() {
        SplittableRandom random = new SplittableRandom(7);
        for (int round = 0; round < 2_000; round++) {
            int size = 1 + random.nextInt(40);
            double[] weights = new double[size];
            for (int i = 0; i < size; i++) {
                weights[i] = random.nextInt(4) == 0 ? random.nextDouble() * 0.01 + 1e-6 : 1 + random.nextInt(1000) + random.nextDouble();
            }
            long[] shown = Chances.hundredths(weights);
            assertEquals(Chances.WHOLE, sum(shown), "round " + round);
            double total = 0;
            for (double weight : weights) {
                total += weight;
            }
            for (int i = 0; i < size; i++) {
                double exact = weights[i] / total * Chances.WHOLE;
                assertTrue(Math.abs(shown[i] - exact) < 1.0, "entry " + i + " shows " + shown[i] + " for " + exact);
            }
        }
    }

    @Test
    void thirdsAreRoundedSoTheTotalIsExact() {
        long[] shown = Chances.hundredths(new double[] {1, 1, 1});
        assertArrayEquals(new long[] {3334, 3333, 3333}, shown);
        assertEquals("33.34%", Chances.format(shown[0]));
        assertEquals("33.33%", Chances.format(shown[1]));
    }

    @Test
    void shippedCratesShowTheirWeightsAsPercentages() {
        // Every shipped crate's weights add up to 100, so each weight is its chance in percent.
        double[] basic = {18, 12, 12, 8, 8, 6, 10, 6, 8, 6, 4, 2};
        long[] shown = Chances.hundredths(basic);
        for (int i = 0; i < basic.length; i++) {
            assertEquals((long) basic[i] * 100, shown[i]);
        }
        assertEquals("18%", Chances.format(shown[0]));
        assertEquals("2%", Chances.format(shown[11]));
    }

    @Test
    void formatting() {
        assertEquals("100%", Chances.format(10_000));
        assertEquals("12.5%", Chances.format(1_250));
        assertEquals("0.05%", Chances.format(5));
        assertEquals("1%", Chances.format(100));
        assertEquals("<0.01%", Chances.format(0));
    }

    @Test
    void aVeryRareRewardIsShownAsBelowOneHundredthOfAPercent() {
        long[] shown = Chances.hundredths(new double[] {1_000_000, 1});
        assertEquals(10_000, shown[0]);
        assertEquals(0, shown[1]);
        assertEquals("<0.01%", Chances.format(shown[1]));
    }

    @Test
    void emptyAndBadWeights() {
        assertEquals(0, Chances.hundredths(new double[0]).length);
        assertThrows(IllegalArgumentException.class, () -> Chances.hundredths(new double[] {1, 0}));
        assertThrows(IllegalArgumentException.class, () -> Chances.hundredths(new double[] {Double.POSITIVE_INFINITY}));
    }
}
