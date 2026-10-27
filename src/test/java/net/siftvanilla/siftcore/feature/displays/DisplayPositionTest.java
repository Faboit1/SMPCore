package net.siftvanilla.siftcore.feature.displays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DisplayPositionTest {

    @Test
    void chunksFloorNegativeCoordinates() {
        DisplayPosition position = new DisplayPosition("world", -0.5, 64, 15.99, 0f);
        assertEquals(-1, position.chunkX());
        assertEquals(0, position.chunkZ());
        assertEquals(-2, new DisplayPosition("world", -17, 64, 16, 0f).chunkX());
        assertEquals(1, new DisplayPosition("world", -17, 64, 16, 0f).chunkZ());
    }

    @Test
    void aFixedDisplayFacesWhoPlacedIt() {
        assertEquals(-180f, DisplayPosition.facing(0f), "looking south, the text faces north");
        assertEquals(0f, DisplayPosition.facing(180f), "looking north, the text faces south");
        assertEquals(-90f, DisplayPosition.facing(90f));
        assertEquals(90f, DisplayPosition.facing(-90f));
        assertEquals(-135f, DisplayPosition.facing(50f), "snapped to 45 degrees");
        assertEquals(0f, DisplayPosition.facing(-170f));
        assertEquals(0f, DisplayPosition.facing(530f));
    }

    @Test
    void samePlaceIgnoresTheFacing() {
        DisplayPosition a = new DisplayPosition("world", 1, 2, 3, 0f);
        assertTrue(a.samePlace(new DisplayPosition("world", 1, 2, 3, 90f)));
        assertFalse(a.samePlace(new DisplayPosition("world", 1, 2, 3.01, 0f)));
        assertFalse(a.samePlace(new DisplayPosition("world_nether", 1, 2, 3, 0f)));
        assertFalse(a.samePlace(null));
    }

    @Test
    void coordinatesAreShortAndRounded() {
        assertEquals("12.5", DisplayPosition.format(12.5));
        assertEquals("64", DisplayPosition.format(64.0));
        assertEquals("-3.13", DisplayPosition.format(-3.125));
        assertEquals("0", DisplayPosition.format(-0.001));
        assertEquals(10.57, DisplayPosition.round(10.5678));
        assertEquals(-3.12, DisplayPosition.round(-3.125), "half rounds up, like Math.round");
    }
}
