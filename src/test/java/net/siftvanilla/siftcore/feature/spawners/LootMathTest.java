package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class LootMathTest {

    /** Counts how many random numbers a calculation draws. */
    private static final class Counting implements RandomGenerator {
        private final SplittableRandom random;
        long draws;

        Counting(long seed) {
            this.random = new SplittableRandom(seed);
        }

        @Override
        public long nextLong() {
            this.draws++;
            return this.random.nextLong();
        }

        @Override
        public double nextDouble() {
            this.draws++;
            return this.random.nextDouble();
        }

        @Override
        public int nextInt(int origin, int bound) {
            this.draws++;
            return this.random.nextInt(origin, bound);
        }

        @Override
        public double nextGaussian() {
            this.draws++;
            return this.random.nextGaussian();
        }
    }

    private static double meanBinomial(long n, double p, int samples, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        long total = 0;
        for (int i = 0; i < samples; i++) {
            long hits = LootMath.binomial(n, p, random);
            assertTrue(hits >= 0 && hits <= n, "binomial result " + hits + " outside 0.." + n);
            total += hits;
        }
        return (double) total / samples;
    }

    @Test
    void binomialEdgeCases() {
        SplittableRandom random = new SplittableRandom(1);
        assertEquals(0, LootMath.binomial(0, 0.5, random));
        assertEquals(0, LootMath.binomial(-5, 0.5, random));
        assertEquals(0, LootMath.binomial(1_000, 0, random));
        assertEquals(1_000, LootMath.binomial(1_000, 1, random));
        assertEquals(Long.MAX_VALUE / 2, LootMath.binomial(Long.MAX_VALUE / 2, 1.0, random));
    }

    @Test
    void binomialMeanIsRightOnEveryPath() {
        // exact (n <= 32)
        assertEquals(20 * 0.3, meanBinomial(20, 0.3, 20_000, 2), 0.05);
        // sparse: few successes expected
        assertEquals(1_000 * 0.005, meanBinomial(1_000, 0.005, 20_000, 3), 0.05);
        // sparse complement: almost every trial succeeds
        assertEquals(1_000 * 0.995, meanBinomial(1_000, 0.995, 20_000, 4), 0.05);
        // normal approximation
        assertEquals(100_000 * 0.4, meanBinomial(100_000, 0.4, 5_000, 5), 5);
    }

    @Test
    void binomialSpreadMatchesTheBinomial() {
        SplittableRandom random = new SplittableRandom(6);
        int samples = 20_000;
        long n = 10_000;
        double p = 0.3;
        double sum = 0;
        double squares = 0;
        for (int i = 0; i < samples; i++) {
            long value = LootMath.binomial(n, p, random);
            sum += value;
            squares += (double) value * value;
        }
        double mean = sum / samples;
        double variance = squares / samples - mean * mean;
        assertEquals(n * p * (1 - p), variance, n * p * (1 - p) * 0.05);
    }

    @Test
    void uniformSumStaysInRangeWithTheRightMean() {
        SplittableRandom random = new SplittableRandom(8);
        assertEquals(0, LootMath.uniformSum(0, 1, 3, random));
        assertEquals(15, LootMath.uniformSum(5, 3, 3, random));
        long exactTotal = 0;
        for (int i = 0; i < 20_000; i++) {
            long value = LootMath.uniformSum(10, 0, 2, random);
            assertTrue(value >= 0 && value <= 20);
            exactTotal += value;
        }
        assertEquals(10.0, exactTotal / 20_000.0, 0.05);
        long approxTotal = 0;
        for (int i = 0; i < 5_000; i++) {
            long value = LootMath.uniformSum(5_000, 3, 5, random);
            assertTrue(value >= 15_000 && value <= 25_000);
            approxTotal += value;
        }
        assertEquals(20_000.0, approxTotal / 5_000.0, 2);
    }

    @Test
    void rollAveragesTheTable() {
        List<DropEntry> zombie = List.of(new DropEntry("minecraft:rotten_flesh", 0, 2, 1.0),
            new DropEntry("minecraft:iron_ingot", 1, 1, 0.025));
        SplittableRandom random = new SplittableRandom(9);
        long flesh = 0;
        long iron = 0;
        int samples = 2_000;
        for (int i = 0; i < samples; i++) {
            Map<String, Long> loot = LootMath.roll(zombie, 10_000, random);
            flesh += loot.getOrDefault("minecraft:rotten_flesh", 0L);
            iron += loot.getOrDefault("minecraft:iron_ingot", 0L);
        }
        assertEquals(10_000, (double) flesh / samples, 10_000 * 0.005);
        assertEquals(250, (double) iron / samples, 250 * 0.02);
    }

    @Test
    void rollOfSmallKillCountsIsExact() {
        List<DropEntry> table = List.of(new DropEntry("minecraft:ender_pearl", 0, 1, 1.0));
        SplittableRandom random = new SplittableRandom(10);
        long total = 0;
        for (int i = 0; i < 50_000; i++) {
            long pearls = LootMath.roll(table, 3, random).getOrDefault("minecraft:ender_pearl", 0L);
            assertTrue(pearls >= 0 && pearls <= 3);
            total += pearls;
        }
        assertEquals(1.5, total / 50_000.0, 0.02);
    }

    @Test
    void rollLeavesOutItemsThatDroppedNothing() {
        List<DropEntry> table = List.of(new DropEntry("minecraft:bone", 1, 1, 0.0), new DropEntry("minecraft:arrow", 2, 2, 1.0));
        Map<String, Long> loot = LootMath.roll(table, 4, new SplittableRandom(11));
        assertEquals(Map.of("minecraft:arrow", 8L), loot);
        assertTrue(LootMath.roll(table, 0, new SplittableRandom(11)).isEmpty());
    }

    @Test
    void costDoesNotGrowWithTheKillCount() {
        List<DropEntry> table = List.of(new DropEntry("minecraft:rotten_flesh", 0, 2, 1.0),
            new DropEntry("minecraft:iron_ingot", 1, 1, 0.025),
            new DropEntry("minecraft:carrot", 1, 1, 0.5),
            new DropEntry("minecraft:potato", 1, 3, 1e-9),
            new DropEntry("minecraft:stick", 0, 2, 0.999_999_999));
        for (long kills : new long[] {1_000, 1_000_000, 1_000_000_000L, 1_000_000_000_000L}) {
            Counting random = new Counting(kills);
            Map<String, Long> loot = LootMath.roll(table, kills, random);
            assertTrue(random.draws <= table.size() * (2 * LootMath.EXACT_LIMIT + 3 * LootMath.SPARSE_LIMIT),
                kills + " kills took " + random.draws + " random draws");
            long flesh = loot.getOrDefault("minecraft:rotten_flesh", 0L);
            assertTrue(flesh >= 0 && flesh <= 2 * kills);
        }
    }

    @Test
    void killsRoundFractionsFairly() {
        SplittableRandom random = new SplittableRandom(12);
        assertEquals(0, LootMath.kills(0, 2.5, random));
        assertEquals(0, LootMath.kills(5, 0, random));
        assertEquals(10, LootMath.kills(5, 2.0, random));
        long total = 0;
        for (int i = 0; i < 100_000; i++) {
            long kills = LootMath.kills(3, 2.5, random);
            assertTrue(kills == 7 || kills == 8);
            total += kills;
        }
        assertEquals(7.5, total / 100_000.0, 0.01);
    }

    @Test
    void expectedItemsPerKill() {
        List<DropEntry> table = List.of(new DropEntry("minecraft:beef", 1, 3, 1.0), new DropEntry("minecraft:leather", 0, 2, 0.5));
        assertEquals(2.5, LootMath.expectedItemsPerKill(table), 1e-9);
    }
}
