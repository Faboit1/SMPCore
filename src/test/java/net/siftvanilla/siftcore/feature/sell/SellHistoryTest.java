package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SellHistoryTest {

    @Test
    void theNoteOfASaleSplitsIntoItsItems() {
        List<String[]> items = SellHistory.noteItems("64 minecraft:diamond, 32 minecraft:iron_ingot, +3 more (312 from shulker boxes)");
        assertEquals(2, items.size());
        assertArrayEquals(new String[] {"64", "minecraft:diamond"}, items.get(0));
        assertArrayEquals(new String[] {"32", "minecraft:iron_ingot"}, items.get(1));
        assertTrue(SellHistory.noteItems("").isEmpty());
        assertTrue(SellHistory.noteItems(null).isEmpty());
        assertEquals(1, SellHistory.noteItems("5 minecraft:stick").size());
    }
}
