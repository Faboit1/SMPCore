package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WeightedTableTest {

    private record Entry(String id, double weight) {
    }

    private static final List<Entry> SHIPPED_BASIC = List.of(new Entry("iron", 18), new Entry("gold", 12), new Entry("steak", 12),
        new Entry("experience", 8), new Entry("pickaxe", 8), new Entry("chestplate", 6), new Entry("money-small", 10),
        new Entry("shards", 6), new Entry("diamonds", 8), new Entry("golden-apples", 6), new Entry("money-large", 4),
        new Entry("rare-key", 2));

    /**
     * Pearson's chi-square statistic of observed counts against the table's expected counts. With 11 degrees of
     * freedom the 99.9th percentile is 31.26; a correct sampler exceeds it once in a thousand runs, and the seeds
     * below are fixed, so the test is deterministic.
     */
    private static double chiSquare(WeightedTable<Entry> table, int[] counts, int draws) {
        double chi = 0;
        for (int i = 0; i < counts.length; i++) {
            double expected = draws * table.chance(i);
            chi += (counts[i] - expected) * (counts[i] - expected) / expected;
        }
        return chi;
    }

    @Test
    void drawsFollowTheWeights() {
        WeightedTable<Entry> table = WeightedTable.of(SHIPPED_BASIC, Entry::weight);
        int draws = 200_000;
        for (long seed : new long[] {1, 42, 20261008}) {
            SplittableRandom random = new SplittableRandom(seed);
            int[] counts = new int[table.size()];
            for (int i = 0; i < draws; i++) {
                counts[table.index(random.nextDouble())]++;
            }
            double chi = chiSquare(table, counts, draws);
            assertTrue(chi < 31.26, "chi-square " + chi + " with seed " + seed + " is beyond the 99.9th percentile");
            for (int i = 0; i < counts.length; i++) {
                double share = counts[i] / (double) draws;
                assertEquals(table.chance(i), share, 0.005, SHIPPED_BASIC.get(i).id() + " came up " + share);
            }
        }
    }

    @Test
    void evenlySpreadUnitsSplitExactlyByWeight() {
        WeightedTable<Entry> table = WeightedTable.of(List.of(new Entry("a", 1), new Entry("b", 2), new Entry("c", 7)), Entry::weight);
        int[] counts = new int[3];
        for (int i = 0; i < 1000; i++) {
            counts[table.index((i + 0.5) / 1000.0)]++;
        }
        assertEquals(100, counts[0]);
        assertEquals(200, counts[1]);
        assertEquals(700, counts[2]);
    }

    @Test
    void boundariesBelongToTheNextEntry() {
        WeightedTable<Entry> table = WeightedTable.of(List.of(new Entry("a", 1), new Entry("b", 1)), Entry::weight);
        assertEquals("a", table.pick(0.0).id());
        assertEquals("a", table.pick(0.4999).id());
        assertEquals("b", table.pick(0.5).id());
        assertEquals("b", table.pick(Math.nextDown(1.0)).id());
    }

    @Test
    void fractionalAndTinyWeightsWork() {
        WeightedTable<Entry> table = WeightedTable.of(List.of(new Entry("common", 99.99), new Entry("jackpot", 0.01)), Entry::weight);
        assertEquals(0.0001, table.chance(1), 1e-12);
        assertEquals("jackpot", table.pick(Math.nextDown(1.0)).id());
        assertEquals("common", table.pick(0.9998).id());
    }

    @Test
    void oneEntryAlwaysWins() {
        WeightedTable<Entry> table = WeightedTable.of(List.of(new Entry("only", 3)), Entry::weight);
        assertTrue(IntStream.range(0, 100).allMatch(i -> table.pick(i / 100.0).id().equals("only")));
        assertEquals(1.0, table.chance(0));
    }

    @Test
    void badInputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> WeightedTable.of(List.<Entry>of(), Entry::weight));
        assertThrows(IllegalArgumentException.class, () -> WeightedTable.of(List.of(new Entry("zero", 0)), Entry::weight));
        assertThrows(IllegalArgumentException.class, () -> WeightedTable.of(List.of(new Entry("negative", -1)), Entry::weight));
        assertThrows(IllegalArgumentException.class, () -> WeightedTable.of(List.of(new Entry("nan", Double.NaN)), Entry::weight));
        WeightedTable<Entry> table = WeightedTable.of(List.of(new Entry("a", 1)), Entry::weight);
        assertThrows(IllegalArgumentException.class, () -> table.index(1.0));
        assertThrows(IllegalArgumentException.class, () -> table.index(-0.1));
        assertThrows(IllegalArgumentException.class, () -> table.index(Double.NaN));
    }
}
