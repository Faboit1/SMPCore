package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import org.junit.jupiter.api.Test;

/** Which online seconds count as active playtime. */
class PlaytimeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void vanishedStaffEarnNoPlaytime() {
        VanishStatus vanished = Set.of(STAFF)::contains;
        assertTrue(StatsFeature.playing(PLAYER, AfkStatus.NONE, vanished));
        assertFalse(StatsFeature.playing(STAFF, AfkStatus.NONE, vanished),
            "a counter that grows while hidden tells anyone polling /playtime that they are online");
    }

    @Test
    void afkPlayersEarnNoPlaytime() {
        AfkStatus afk = Set.of(PLAYER)::contains;
        assertFalse(StatsFeature.playing(PLAYER, afk, VanishStatus.NONE));
        assertTrue(StatsFeature.playing(STAFF, afk, VanishStatus.NONE));
    }
}
