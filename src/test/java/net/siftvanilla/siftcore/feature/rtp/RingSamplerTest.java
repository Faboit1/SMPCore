package net.siftvanilla.siftcore.feature.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class RingSamplerTest {

    private static final int SAMPLES = 200_000;

    private static double distance(RingSampler.Point point, double cx, double cz) {
        double dx = point.x() + 0.5 - cx;
        double dz = point.z() + 0.5 - cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Test
    void everyPointLiesInTheRing() {
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < SAMPLES; i++) {
            RingSampler.Point point = RingSampler.sample(random, 0, 0, 300, 4_800);
            double d = distance(point, 0, 0);
            assertTrue(d >= 299 && d <= 4_801, "distance " + d + " for " + point);
        }
    }

    @Test
    void pointsAreUniformByArea() {
        SplittableRandom random = new SplittableRandom(2);
        double min = 300;
        double max = 4_800;
        // The circle that splits the ring into two halves of equal area.
        double split = Math.sqrt((min * min + max * max) / 2);
        int inner = 0;
        int[] quadrants = new int[4];
        for (int i = 0; i < SAMPLES; i++) {
            RingSampler.Point point = RingSampler.sample(random, 0, 0, min, max);
            if (distance(point, 0, 0) < split) {
                inner++;
            }
            quadrants[(point.x() >= 0 ? 0 : 1) + (point.z() >= 0 ? 0 : 2)]++;
        }
        double innerShare = inner / (double) SAMPLES;
        assertEquals(0.5, innerShare, 0.01, "half the points fall inside the equal-area circle");
        for (int count : quadrants) {
            assertEquals(0.25, count / (double) SAMPLES, 0.01, "each quarter of the ring gets a quarter of the points");
        }
    }

    @Test
    void outerBandIsNotUnderRepresented() {
        SplittableRandom random = new SplittableRandom(3);
        // With uniform radius the band 4300-4800 would get 500/4500 = 11% of points; by area it gets 20.3%.
        int outer = 0;
        for (int i = 0; i < SAMPLES; i++) {
            if (distance(RingSampler.sample(random, 0, 0, 300, 4_800), 0, 0) >= 4_300) {
                outer++;
            }
        }
        double expected = (4_800.0 * 4_800 - 4_300.0 * 4_300) / (4_800.0 * 4_800 - 300.0 * 300);
        assertEquals(expected, outer / (double) SAMPLES, 0.01);
    }

    @Test
    void ringsAroundOtherCentres() {
        SplittableRandom random = new SplittableRandom(4);
        for (int i = 0; i < 10_000; i++) {
            RingSampler.Point point = RingSampler.sample(random, 1_000, -2_000, 100, 200);
            double d = distance(point, 1_000, -2_000);
            assertTrue(d >= 99 && d <= 201, "distance " + d);
        }
    }

    @Test
    void fullDiscAndInvalidRings() {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 1_000; i++) {
            assertTrue(distance(RingSampler.sample(random, 0, 0, 0, 50), 0, 0) <= 51);
        }
        assertThrows(IllegalArgumentException.class, () -> RingSampler.sample(random, 0, 0, 100, 50));
        assertThrows(IllegalArgumentException.class, () -> RingSampler.sample(random, 0, 0, -1, 50));
    }

    @Test
    void ringMembershipUsesBlockCentres() {
        assertTrue(RingSampler.inRing(299, 0, 0, 0, 299, 4_800));
        assertFalse(RingSampler.inRing(10, 10, 0, 0, 300, 4_800), "inside the hole");
        assertFalse(RingSampler.inRing(4_800, 0, 0, 0, 300, 4_800), "the centre of block 4800 is 4800.5 away");
        assertTrue(RingSampler.inRing(4_799, 0, 0, 0, 300, 4_800));
    }
}
