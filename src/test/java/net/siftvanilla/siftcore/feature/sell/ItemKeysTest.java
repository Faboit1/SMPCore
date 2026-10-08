package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ItemKeysTest {

    @Test
    void normalizesKeys() {
        assertEquals("minecraft:diamond", ItemKeys.normalize("diamond"));
        assertEquals("minecraft:diamond", ItemKeys.normalize(" DIAMOND "));
        assertEquals("minecraft:diamond", ItemKeys.normalize("minecraft:diamond"));
        assertEquals("custom:thing", ItemKeys.normalize("custom:thing"));
    }

    @Test
    void rejectsGarbage() {
        assertNull(ItemKeys.normalize(null));
        assertNull(ItemKeys.normalize(""));
        assertNull(ItemKeys.normalize("dia mond"));
        assertNull(ItemKeys.normalize("<red>diamond"));
        assertNull(ItemKeys.normalize("a:b:c"));
    }

    @Test
    void plainNames() {
        assertEquals("diamond sword", ItemKeys.name("minecraft:diamond_sword"));
        assertEquals("diamond", ItemKeys.name("diamond"));
        assertEquals("diamond", ItemKeys.shortKey("minecraft:diamond"));
        assertEquals("custom:thing", ItemKeys.shortKey("custom:thing"));
    }
}
