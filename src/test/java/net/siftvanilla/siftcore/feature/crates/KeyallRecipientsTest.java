package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import org.junit.jupiter.api.Test;

class KeyallRecipientsTest {

    private static final UUID ACTIVE = new UUID(0, 1);
    private static final UUID HIDDEN = new UUID(0, 2);
    private static final UUID AWAY = new UUID(0, 3);
    private static final UUID HIDDEN_AND_AWAY = new UUID(0, 4);
    private static final List<UUID> ONLINE = List.of(ACTIVE, HIDDEN, AWAY, HIDDEN_AND_AWAY);

    private static final VanishStatus VANISH = Set.of(HIDDEN, HIDDEN_AND_AWAY)::contains;
    private static final AfkStatus AFK = Set.of(AWAY, HIDDEN_AND_AWAY)::contains;

    private static CratesSettings.Keyall config(boolean includeVanished, boolean includeAfk) {
        return new CratesSettings.Keyall(true, Duration.ofHours(4), "basic", 1, Duration.ofMinutes(10), includeVanished, includeAfk,
            List.of(Duration.ofMinutes(5)), Duration.ofSeconds(10));
    }

    @Test
    void shippedSettingsLeaveOutVanishedStaffOnly() {
        assertEquals(List.of(ACTIVE, AWAY), Keyall.recipients(ONLINE, config(false, true), VANISH, AFK));
    }

    @Test
    void afkPlayersCanBeLeftOut() {
        assertEquals(List.of(ACTIVE), Keyall.recipients(ONLINE, config(false, false), VANISH, AFK));
        assertEquals(List.of(ACTIVE, HIDDEN), Keyall.recipients(ONLINE, config(true, false), VANISH, AFK));
    }

    @Test
    void everyoneWhenBothAreIncluded() {
        assertEquals(ONLINE, Keyall.recipients(ONLINE, config(true, true), VANISH, AFK));
    }

    @Test
    void withoutTheAfkFeatureNobodyIsAfk() {
        assertEquals(List.of(ACTIVE, AWAY), Keyall.recipients(ONLINE, config(false, false), VANISH, AfkStatus.NONE));
        assertEquals(List.of(), Keyall.recipients(List.of(), config(false, false), VANISH, AFK));
    }
}
