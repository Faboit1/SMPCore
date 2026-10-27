package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class StorageMathTest {

    @Test
    void capacityGrowsWithTheStack() {
        assertEquals(576, StorageMath.capacity(1, 9));
        assertEquals(576_000, StorageMath.capacity(1_000, 9));
        assertEquals(10_000L * 54 * 64, StorageMath.capacity(StorageMath.HARD_STACK_CAP, 54));
        assertEquals(0, StorageMath.capacity(0, 9));
        assertEquals(0, StorageMath.capacity(5, 0));
    }

    @Test
    void xpCapacityAndAdding() {
        assertEquals(6_000, StorageMath.xpCapacity(1, 6_000));
        assertEquals(Long.MAX_VALUE, StorageMath.xpCapacity(10_000, Long.MAX_VALUE / 2));
        assertEquals(150, StorageMath.addXp(100, 50, 1_000));
        assertEquals(1_000, StorageMath.addXp(900, 500, 1_000));
        assertEquals(1_200, StorageMath.addXp(1_200, 10, 1_000), "a stack that shrank keeps its XP but gets no more");
        assertEquals(100, StorageMath.addXp(100, 0, 1_000));
    }

    @Test
    void stackCaps() {
        assertEquals(1_000, StorageMath.stackCap(1_000, 0));
        assertEquals(1_500, StorageMath.stackCap(1_000, 500));
        assertEquals(StorageMath.HARD_STACK_CAP, StorageMath.stackCap(1_000, Integer.MAX_VALUE));
        assertEquals(1, StorageMath.stackCap(0, -5));
        assertEquals(1, StorageMath.acceptable(1, 1_000, 1));
        assertEquals(64, StorageMath.acceptable(10, 1_000, 64));
        assertEquals(36, StorageMath.acceptable(964, 1_000, 64));
        assertEquals(0, StorageMath.acceptable(1_000, 1_000, 64));
        assertEquals(0, StorageMath.acceptable(1_200, 1_000, 1));
        assertEquals(0, StorageMath.acceptable(10, 1_000, 0));
    }

    @Test
    void fitKeepsEverythingWhenThereIsRoom() {
        Map<String, Long> loot = Map.of("a", 10L, "b", 5L);
        assertEquals(loot, StorageMath.fit(loot, 15));
        assertEquals(loot, StorageMath.fit(loot, 1_000));
        assertTrue(StorageMath.fit(loot, 0).isEmpty());
        assertTrue(StorageMath.fit(Map.of(), 10).isEmpty());
    }

    @Test
    void fitSharesTheSpaceProportionally() {
        Map<String, Long> loot = new LinkedHashMap<>();
        loot.put("flesh", 300L);
        loot.put("iron", 100L);
        Map<String, Long> fitted = StorageMath.fit(loot, 200);
        assertEquals(150, fitted.get("flesh"));
        assertEquals(50, fitted.get("iron"));
        Map<String, Long> odd = StorageMath.fit(Map.of("a", 1L, "b", 1L, "c", 1L), 2);
        assertEquals(2, odd.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of("a", 1L, "b", 1L), odd, "ties go to the first keys");
    }

    @Test
    void fitNeverOverfillsOrInventsLoot() {
        SplittableRandom random = new SplittableRandom(3);
        for (int round = 0; round < 5_000; round++) {
            Map<String, Long> loot = new LinkedHashMap<>();
            long total = 0;
            int kinds = random.nextInt(1, 6);
            for (int k = 0; k < kinds; k++) {
                long amount = random.nextLong(0, 5_000);
                loot.put("item" + k, amount);
                total += amount;
            }
            long free = random.nextLong(0, 10_000);
            Map<String, Long> fitted = StorageMath.fit(loot, free);
            long sum = 0;
            for (Map.Entry<String, Long> entry : fitted.entrySet()) {
                assertTrue(entry.getValue() > 0 && entry.getValue() <= loot.get(entry.getKey()));
                sum += entry.getValue();
                if (total > free) {
                    double share = (double) loot.get(entry.getKey()) * free / total;
                    assertTrue(Math.abs(entry.getValue() - share) <= 1.0, "share of " + entry.getKey() + " is proportional");
                }
            }
            assertEquals(Math.min(free, total), sum);
        }
    }

    @Test
    void fitHandlesHugeNumbers() {
        Map<String, Long> fitted = StorageMath.fit(Map.of("a", Long.MAX_VALUE / 3, "b", Long.MAX_VALUE / 3), Long.MAX_VALUE / 4);
        assertEquals(Long.MAX_VALUE / 4, fitted.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void stacksAndSaturation() {
        assertEquals(0, StorageMath.stacks(0, 64));
        assertEquals(1, StorageMath.stacks(64, 64));
        assertEquals(2, StorageMath.stacks(65, 64));
        assertEquals(7, StorageMath.stacks(100, 16));
        assertEquals(Long.MAX_VALUE, StorageMath.saturatingAdd(Long.MAX_VALUE, 5));
        assertEquals(12, StorageMath.saturatingAdd(7, 5));
        assertEquals(Long.MAX_VALUE, StorageMath.saturatingMultiply(Long.MAX_VALUE / 2, 3));
        assertEquals(600, StorageMath.saturatingMultiply(200, 3));
    }

    @Test
    void storageCounts() {
        Storage storage = new Storage();
        assertTrue(storage.isEmpty());
        storage.add("minecraft:bone", 10);
        storage.add("minecraft:arrow", 5);
        storage.add("minecraft:bone", 2);
        storage.add("minecraft:arrow", 0);
        assertEquals(17, storage.used());
        assertEquals(12, storage.amount("minecraft:bone"));
        assertEquals(4, storage.take("minecraft:bone", 4));
        assertEquals(8, storage.take("minecraft:bone", 100));
        assertEquals(0, storage.take("minecraft:bone", 1));
        assertEquals(0, storage.take("minecraft:arrow", -3));
        assertEquals(5, storage.used());
        assertEquals(Map.of("minecraft:arrow", 5L), storage.snapshot());
        storage.replace(Map.of("minecraft:string", 7L, "minecraft:stick", 0L));
        assertEquals(Map.of("minecraft:string", 7L), storage.snapshot());
        assertEquals(7, storage.used());
    }
}
