package net.siftvanilla.siftcore.ui.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order in which items that left a menu grid go back to their player. The player file holds the inventory and the
 * grid's copy together, the claim box is stored apart from it: the file must be saved with the items in the inventory
 * (and out of the copy) before anything can reach the claim box, or a crash between the claim box commit and the save
 * restores the copy while the claim box keeps the overflow too.
 */
class GridBackupTest {

    private final List<String> steps = new ArrayList<>();

    private long handBack(List<String> items, int fits) {
        return GridBackup.handBack(items,
            all -> {
                this.steps.add("inventory " + all);
                return new ArrayList<>(all.subList(Math.min(fits, all.size()), all.size()));
            },
            () -> this.steps.add("save"),
            left -> {
                this.steps.add("claim box " + left);
                return left.size();
            });
    }

    @Test
    void theInventoryIsSavedBeforeTheOverflowReachesTheClaimBox() {
        long claimed = handBack(List.of("stone", "dirt", "sand"), 1);
        assertEquals(List.of("inventory [stone, dirt, sand]", "save", "claim box [dirt, sand]"), this.steps);
        assertEquals(2, claimed);
    }

    @Test
    void nothingGoesToTheClaimBoxWhenEverythingFits() {
        long claimed = handBack(List.of("stone", "dirt"), 2);
        assertEquals(List.of("inventory [stone, dirt]", "save"), this.steps);
        assertEquals(0, claimed);
    }

    @Test
    void aSaveThatThrowsStillKeepsTheOverflowAndPassesTheFailureOn() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> GridBackup.handBack(List.of("stone", "dirt"),
            all -> List.of("dirt"),
            () -> {
                throw new IllegalStateException("not the player's thread");
            },
            left -> {
                this.steps.add("claim box " + left);
                return left.size();
            }));
        assertEquals("not the player's thread", failure.getMessage());
        assertEquals(List.of("claim box [dirt]"), this.steps, "the overflow exists nowhere else, so it is stored once");
    }
}
