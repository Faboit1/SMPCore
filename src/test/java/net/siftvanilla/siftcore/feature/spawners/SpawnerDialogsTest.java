package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The fullness a spawner's button shows in /spawners. */
class SpawnerDialogsTest {

    @Test
    void fullnessIsRoundedDownSoOnlyAFullStorageReadsAHundred() {
        assertEquals(0, SpawnerDialogs.percent(0, 1_200));
        assertEquals(28, SpawnerDialogs.percent(340, 1_200));
        assertEquals(99, SpawnerDialogs.percent(1_199, 1_200), "one item short is not full");
        assertEquals(100, SpawnerDialogs.percent(1_200, 1_200));
        assertEquals(100, SpawnerDialogs.percent(5_000, 1_200), "more than fits (a stack shrank) still reads full");
        assertEquals(0, SpawnerDialogs.percent(10, 0), "no capacity: nothing to fill");
        assertEquals(50, SpawnerDialogs.percent(Long.MAX_VALUE / 200, Long.MAX_VALUE / 100), "large numbers don't overflow");
    }
}
