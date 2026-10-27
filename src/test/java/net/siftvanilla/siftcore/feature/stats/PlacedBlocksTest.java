package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PlacedBlocksTest {

    private static final UUID WORLD = new UUID(5, 5);
    private static final UUID NETHER = new UUID(6, 6);
    private static final long WINDOW = 60_000;

    @Test
    void recentlyPlacedBlocksDoNotCountOnce() {
        PlacedBlocks placed = new PlacedBlocks(1_000);
        placed.placed(WORLD, 10, 64, -20, 1_000);
        assertTrue(placed.takeIfRecent(WORLD, 10, 64, -20, 2_000, WINDOW));
        // The entry is gone with the block: a natural block at the same spot later counts.
        assertFalse(placed.takeIfRecent(WORLD, 10, 64, -20, 3_000, WINDOW));
    }

    @Test
    void oldPlacementsAndOtherWorldsDoNotMatch() {
        PlacedBlocks placed = new PlacedBlocks(1_000);
        placed.placed(WORLD, 1, 2, 3, 0);
        assertFalse(placed.takeIfRecent(WORLD, 1, 2, 3, WINDOW, WINDOW));
        placed.placed(WORLD, 1, 2, 3, 0);
        assertFalse(placed.takeIfRecent(NETHER, 1, 2, 3, 10, WINDOW));
        assertFalse(placed.takeIfRecent(WORLD, 1, 3, 3, 10, WINDOW));
        assertTrue(placed.takeIfRecent(WORLD, 1, 2, 3, 10, WINDOW));
    }

    @Test
    void packingKeepsNegativeAndExtremeCoordinatesApart() {
        assertNotEquals(PlacedBlocks.pack(1, -64, 1), PlacedBlocks.pack(1, 64, 1));
        assertNotEquals(PlacedBlocks.pack(-29_999_984, 319, 29_999_984), PlacedBlocks.pack(29_999_984, 319, -29_999_984));
        assertNotEquals(PlacedBlocks.pack(0, -1, 0), PlacedBlocks.pack(0, 0, -1));
        PlacedBlocks placed = new PlacedBlocks(1_000);
        placed.placed(WORLD, -29_999_984, -64, 29_999_984, 0);
        assertTrue(placed.takeIfRecent(WORLD, -29_999_984, -64, 29_999_984, 1, WINDOW));
    }

    @Test
    void boundedByCapacityOldestFirst() {
        PlacedBlocks placed = new PlacedBlocks(1_600);
        for (int i = 0; i < 10_000; i++) {
            placed.placed(WORLD, i, 70, 0, i);
        }
        assertTrue(placed.size() <= 1_600, "size " + placed.size());
        // The newest placements are still known, the oldest were forgotten.
        assertTrue(placed.takeIfRecent(WORLD, 9_999, 70, 0, 10_000, WINDOW));
        assertFalse(placed.takeIfRecent(WORLD, 0, 70, 0, 10_000, WINDOW));
        placed.capacity(160);
        placed.placed(WORLD, -1, 70, 0, 10_001);
        assertTrue(placed.size() <= 1_600);
    }

    @Test
    void pistonsCarryPlacementsToTheNewPosition() {
        PlacedBlocks placed = new PlacedBlocks(1_000);
        placed.placed(WORLD, 0, 64, 0, 100);
        placed.placed(WORLD, 1, 64, 0, 200);
        // A row of two placed blocks pushed one block east.
        List<int[]> moved = new ArrayList<>(List.of(new int[] {0, 64, 0}, new int[] {1, 64, 0}));
        placed.moved(WORLD, moved, 1, 0, 0);
        assertFalse(placed.takeIfRecent(WORLD, 0, 64, 0, 300, WINDOW));
        assertTrue(placed.takeIfRecent(WORLD, 1, 64, 0, 300, WINDOW));
        assertTrue(placed.takeIfRecent(WORLD, 2, 64, 0, 300, WINDOW));
        // Natural blocks pushed by a piston stay natural.
        placed.moved(WORLD, List.<int[]>of(new int[] {5, 5, 5}), 0, 1, 0);
        assertFalse(placed.takeIfRecent(WORLD, 5, 6, 5, 300, WINDOW));
    }

    @Test
    void sweepDropsOnlyExpiredEntries() {
        PlacedBlocks placed = new PlacedBlocks(1_000);
        placed.placed(WORLD, 1, 1, 1, 0);
        placed.placed(WORLD, 2, 2, 2, 50_000);
        placed.forget(WORLD, 3, 3, 3);
        assertEquals(1, placed.sweep(70_000, WINDOW));
        assertEquals(1, placed.size());
        placed.forget(WORLD, 2, 2, 2);
        assertEquals(0, placed.size());
    }

    @Test
    void manyThreadsAtOnce() throws Exception {
        PlacedBlocks placed = new PlacedBlocks(1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int thread = t;
            results.add(pool.submit(() -> {
                start.await();
                int found = 0;
                for (int i = 0; i < 20_000; i++) {
                    placed.placed(WORLD, thread * 100_000 + i, 64, 0, 1);
                }
                for (int i = 0; i < 20_000; i++) {
                    if (placed.takeIfRecent(WORLD, thread * 100_000 + i, 64, 0, 2, WINDOW)) {
                        found++;
                    }
                }
                return found;
            }));
        }
        start.countDown();
        for (var result : results) {
            assertEquals(20_000, result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(0, placed.size());
    }
}
