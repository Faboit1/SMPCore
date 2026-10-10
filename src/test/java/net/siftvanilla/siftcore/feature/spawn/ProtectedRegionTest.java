package net.siftvanilla.siftcore.feature.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProtectedRegionTest {

    @Test
    void radiusIsAFlatCircleThroughTheWholeHeight() {
        ProtectedRegion region = new ProtectedRegion.Radius("world", 100.5, -20.5, 64);
        assertTrue(region.contains("world", 100.5, 70, -20.5), "the centre");
        assertTrue(region.contains("world", 164.5, -60, -20.5), "exactly on the edge, deep underground");
        assertTrue(region.contains("world", 100.5, 319, 43.5), "on the edge at build height");
        assertFalse(region.contains("world", 164.6, 70, -20.5), "just past the edge");
        assertTrue(region.contains("world", 100.5 + 45, 70, -20.5 + 45), "45,45 is 63.6 away");
        assertFalse(region.contains("world", 100.5 + 46, 70, -20.5 + 46), "46,46 is 65.1 away");
    }

    @Test
    void otherWorldsAreNeverInside() {
        assertFalse(new ProtectedRegion.Radius("world", 0, 0, 64).contains("world_nether", 0, 64, 0));
        assertFalse(ProtectedRegion.Cuboid.of("world", -10, 0, -10, 10, 100, 10).contains("world_the_end", 0, 50, 0));
    }

    @Test
    void cuboidIncludesBothCornersInAnyOrder() {
        ProtectedRegion.Cuboid cuboid = ProtectedRegion.Cuboid.of("world", 10, 100, -5, -10, 0, 5);
        assertEquals(-10, cuboid.minX());
        assertEquals(10, cuboid.maxX());
        assertEquals(0, cuboid.minY());
        assertTrue(cuboid.contains("world", -10, 0, -5), "lowest corner block");
        assertTrue(cuboid.contains("world", 10.99, 100.99, 5.99), "anywhere inside the highest corner block");
        assertFalse(cuboid.contains("world", 11, 50, 0), "one block east");
        assertFalse(cuboid.contains("world", 0, -0.01, 0), "below the floor");
        assertFalse(cuboid.contains("world", -10.01, 50, 0), "negative coordinates floor correctly");
    }

    @Test
    void disabledContainsNothing() {
        assertFalse(ProtectedRegion.NONE.contains("world", 0, 64, 0));
        assertEquals("disabled", ProtectedRegion.NONE.describe());
    }

    @Test
    void negativeRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProtectedRegion.Radius("world", 0, 0, -1));
    }

    @Test
    void bordersKnowTheirInside() {
        BorderSpec border = new BorderSpec(0, 0, 10_000);
        assertEquals(5_000, border.halfSize());
        assertTrue(border.inside(4_968, -4_968, 32));
        assertFalse(border.inside(4_969, 0, 32));
        assertFalse(border.inside(0, -5_001, 0));
        BorderSpec moved = new BorderSpec(1_000, -500, 2_000);
        assertTrue(moved.inside(1_999, -1_499, 0));
        assertFalse(moved.inside(-1, -500, 0));
        assertThrows(IllegalArgumentException.class, () -> new BorderSpec(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BorderSpec(Double.NaN, 0, 10));
    }
}
