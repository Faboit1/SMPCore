package net.siftvanilla.siftcore.feature.rtp;

import static net.siftvanilla.siftcore.feature.rtp.SafeSpot.Surface.CLEAR;
import static net.siftvanilla.siftcore.feature.rtp.SafeSpot.Surface.HAZARD;
import static net.siftvanilla.siftcore.feature.rtp.SafeSpot.Surface.LIQUID;
import static net.siftvanilla.siftcore.feature.rtp.SafeSpot.Surface.OTHER;
import static net.siftvanilla.siftcore.feature.rtp.SafeSpot.Surface.SOLID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import net.siftvanilla.siftcore.feature.rtp.SafeSpot.Surface;
import org.junit.jupiter.api.Test;

/** The safe-spot rules on a fake block grid: one column, everything not set is air. */
class SafeSpotTest {

    private static final int MIN_Y = -64;
    private static final int MAX_Y = 319;

    /** A fake column that also counts how many blocks were read. */
    private static final class Grid implements SafeSpot.Column {
        private final Map<Integer, Surface> blocks = new HashMap<>();
        private final AtomicInteger reads = new AtomicInteger();

        Grid set(int y, Surface surface) {
            this.blocks.put(y, surface);
            return this;
        }

        Grid fill(int from, int to, Surface surface) {
            for (int y = from; y <= to; y++) {
                this.blocks.put(y, surface);
            }
            return this;
        }

        @Override
        public Surface at(int y) {
            this.reads.incrementAndGet();
            if (y < MIN_Y || y > MAX_Y) {
                return CLEAR;
            }
            return this.blocks.getOrDefault(y, CLEAR);
        }
    }

    @Test
    void solidGroundWithTwoClearBlocksIsSafe() {
        Grid grass = new Grid().fill(MIN_Y, 70, SOLID);
        assertEquals(OptionalInt.of(71), SafeSpot.surface(grass, 70, MIN_Y, MAX_Y));
        Grid flowers = new Grid().fill(MIN_Y, 70, SOLID).set(71, CLEAR);
        assertEquals(OptionalInt.of(71), SafeSpot.surface(flowers, 70, MIN_Y, MAX_Y), "short plants count as clear");
    }

    @Test
    void liquidsAndHazardsAreNeverSafe() {
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 40, SOLID).fill(41, 62, LIQUID), 62, MIN_Y, MAX_Y).isPresent(), "ocean");
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 30, SOLID).set(31, LIQUID), 31, MIN_Y, MAX_Y).isPresent(), "lava lake");
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 69, SOLID).set(70, HAZARD), 70, MIN_Y, MAX_Y).isPresent(), "magma or cactus");
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 70, SOLID).set(71, HAZARD), 70, MIN_Y, MAX_Y).isPresent(), "fire at the feet");
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 70, SOLID).set(72, HAZARD), 70, MIN_Y, MAX_Y).isPresent(), "powder snow at the head");
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 70, SOLID).set(71, LIQUID), 70, MIN_Y, MAX_Y).isPresent(), "waterlogged feet");
    }

    @Test
    void needsTwoClearBlocks() {
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 70, SOLID).set(72, SOLID), 70, MIN_Y, MAX_Y).isPresent(), "a one-block gap");
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 70, SOLID).set(71, OTHER), 70, MIN_Y, MAX_Y).isPresent(), "leaves at the feet");
        assertFalse(SafeSpot.surface(new Grid().fill(MIN_Y, 69, SOLID).set(70, OTHER), 70, MIN_Y, MAX_Y).isPresent(), "standing on a fence");
    }

    @Test
    void theVoidAndTheTopOfTheWorldAreNotSafe() {
        Grid voidColumn = new Grid();
        assertFalse(SafeSpot.surface(voidColumn, MIN_Y - 1, MIN_Y, MAX_Y).isPresent(), "an empty end column");
        assertFalse(SafeSpot.surface(voidColumn, MIN_Y, MIN_Y, MAX_Y).isPresent(), "air at the bottom");
        assertFalse(SafeSpot.surface(new Grid().set(MAX_Y - 1, SOLID), MAX_Y - 1, MIN_Y, MAX_Y).isPresent(), "no room for the head");
        Grid island = new Grid().fill(50, 60, SOLID);
        assertEquals(OptionalInt.of(61), SafeSpot.surface(island, 60, MIN_Y, MAX_Y), "an end island");
    }

    @Test
    void netherFindsTheHighestCaveFloorBelowTheRoof() {
        Grid nether = new Grid()
            .fill(0, 31, SOLID).fill(32, 34, LIQUID)        // lava sea surface reaches 34 here
            .fill(35, 60, SOLID)                            // floor of a big cavern up to 60
            .fill(61, 64, CLEAR)                            // cavern
            .fill(65, 90, SOLID)                            // rock
            .set(91, SOLID).fill(92, 92, CLEAR).set(93, SOLID) // a one-block gap: not enough room
            .fill(94, 126, SOLID).fill(127, 127, SOLID);    // up to the bedrock roof
        assertEquals(OptionalInt.of(61), SafeSpot.cavern(nether, 120, 32));
    }

    @Test
    void netherSkipsFireAndLavaFloors() {
        Grid fire = new Grid().fill(0, 59, SOLID).set(60, SOLID).set(61, HAZARD).fill(62, 63, CLEAR).fill(64, 127, SOLID);
        assertFalse(SafeSpot.cavern(fire, 120, 32).isPresent(), "fire on the only floor");
        Grid lava = new Grid().fill(0, 31, SOLID).fill(32, 50, LIQUID).fill(51, 127, CLEAR);
        assertFalse(SafeSpot.cavern(lava, 120, 32).isPresent(), "a lava ocean");
        Grid solidRock = new Grid().fill(0, 127, SOLID);
        assertFalse(SafeSpot.cavern(solidRock, 120, 32).isPresent(), "no cave in this column");
    }

    @Test
    void cavernScanReadsEachBlockOnce() {
        Grid rock = new Grid().fill(0, 127, SOLID);
        SafeSpot.cavern(rock, 120, 32);
        assertEquals(120 - 32 + 1 + 2, rock.reads.get());
        assertFalse(SafeSpot.cavern(rock, 10, 32).isPresent(), "an empty scan range");
    }

    @Test
    void standableIsExactlySolidClearClear() {
        for (Surface ground : Surface.values()) {
            for (Surface feet : Surface.values()) {
                for (Surface head : Surface.values()) {
                    boolean expected = ground == SOLID && feet == CLEAR && head == CLEAR;
                    assertEquals(expected, SafeSpot.standable(ground, feet, head), ground + " " + feet + " " + head);
                }
            }
        }
        assertTrue(SafeSpot.standable(SOLID, CLEAR, CLEAR));
    }
}
