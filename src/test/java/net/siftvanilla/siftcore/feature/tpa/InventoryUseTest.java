package net.siftvanilla.siftcore.feature.tpa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * "Requests open a pop-up" over the player's own inventory: the server can't see it opened, only clicked in, so a
 * recent click counts as busy there until the player closes it or a few seconds pass. (Which views are the own
 * inventory, {@link InventoryUse#own}, needs a server: Bukkit's inventory types load the game's registries.)
 */
class InventoryUseTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    private final long[] now = {10_000};
    private final InventoryUse use = new InventoryUse(() -> this.now[0]);

    @Test
    void aClickInTheOwnInventoryKeepsThePopUpAwayForAFewSeconds() {
        assertFalse(this.use.inUse(ALEX), "nothing clicked: the server can't tell, so the pop-up opens");
        this.use.clicked(ALEX, true);
        assertTrue(this.use.inUse(ALEX));
        assertFalse(this.use.inUse(SAM), "per player");
        this.now[0] += InventoryUse.RECENT.toMillis() - 1;
        assertTrue(this.use.inUse(ALEX), "still sorting their items");
        this.now[0] += 1;
        assertFalse(this.use.inUse(ALEX), "a few quiet seconds: probably closed");
    }

    @Test
    void closingEndsItAtOnce() {
        this.use.clicked(ALEX, true);
        this.use.closed(ALEX);
        assertFalse(this.use.inUse(ALEX));
        this.use.clicked(ALEX, true);
        this.use.forget(ALEX);
        assertFalse(this.use.inUse(ALEX), "forgotten when the player leaves");
    }

    @Test
    void clicksInAServerWindowAreNotRemembered() {
        this.use.clicked(ALEX, false);
        assertFalse(this.use.inUse(ALEX), "the chest itself keeps the pop-up away while it is open");
    }

    @Test
    void recentIsAWindowAfterTheClick() {
        assertTrue(InventoryUse.recent(1_000, 1_000));
        assertFalse(InventoryUse.recent(2_000, 1_000), "a click from the future (clock change) doesn't count");
        assertFalse(InventoryUse.recent(1_000, 1_000 + InventoryUse.RECENT.toMillis()));
    }
}
