package net.siftvanilla.siftcore.feature.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.siftvanilla.siftcore.feature.homes.HomeSafety.Danger;
import net.siftvanilla.siftcore.feature.homes.HomeSafety.Kind;
import org.junit.jupiter.api.Test;

class HomeSafetyTest {

    @Test
    void anOpenSpotIsSafe() {
        assertEquals(Danger.NONE, HomeSafety.danger(Kind.CLEAR, Kind.CLEAR));
    }

    @Test
    void aBlockAtTheFeetIsNormal() {
        assertEquals(Danger.NONE, HomeSafety.danger(Kind.FULL, Kind.CLEAR), "homes set on slabs, stairs or carpets");
    }

    @Test
    void aBlockAtTheHeadSuffocates() {
        assertEquals(Danger.BLOCKED, HomeSafety.danger(Kind.CLEAR, Kind.FULL));
        assertEquals(Danger.BLOCKED, HomeSafety.danger(Kind.FULL, Kind.FULL), "walled in");
    }

    @Test
    void lavaAndFireAnywhereAreDangerousAndLavaIsNamedFirst() {
        assertEquals(Danger.LAVA, HomeSafety.danger(Kind.LAVA, Kind.CLEAR));
        assertEquals(Danger.LAVA, HomeSafety.danger(Kind.CLEAR, Kind.LAVA));
        assertEquals(Danger.LAVA, HomeSafety.danger(Kind.FIRE, Kind.LAVA));
        assertEquals(Danger.FIRE, HomeSafety.danger(Kind.FIRE, Kind.CLEAR));
        assertEquals(Danger.FIRE, HomeSafety.danger(Kind.CLEAR, Kind.FIRE));
        assertEquals(Danger.FIRE, HomeSafety.danger(Kind.FIRE, Kind.FULL), "fire is worse than a block to dig out of");
    }
}
