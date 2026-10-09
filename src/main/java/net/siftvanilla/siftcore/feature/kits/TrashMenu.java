package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code /trash} bin: four rows players can put anything into. How it deletes follows the player's Trash bin mode:
 * <ul>
 *   <li>delete on close (the classic bin): everything in it is deleted when it closes, however it closes (the player
 *       closes it, opens something else, leaves or dies). The title says so; there is no confirmation.</li>
 *   <li>Delete button: a fifth row holds a Delete button that deletes what is in the bin; closing it, however it
 *       closes, gives everything back.</li>
 * </ul>
 * Either way the bin takes its items out (and clears itself) before anything is deleted or given back, and the
 * player's Trash protection decides what is given back instead of deleted ({@link PerkService}).
 */
final class TrashMenu extends Menu {

    static final int ROWS = 4;
    /** Rows of the bin with a Delete button: four rows of items and the button row. */
    static final int BUTTON_ROWS = 5;
    /** The slots that take items (four rows). */
    static final int ITEM_SLOTS = ROWS * 9;
    /** The Delete button, in the middle of the button row. */
    static final int DELETE_SLOT = ITEM_SLOTS + 4;

    /** Receives what leaves the bin (copies), on the viewer's thread. */
    @FunctionalInterface
    interface Emptied {
        /**
         * @param items  what was in the bin (never empty, except for a Delete click on an empty bin)
         * @param delete true to delete them (protected items still come back), false to give everything back
         * @param dying  the bin closed because its player died (and their inventory is about to be dropped)
         */
        void emptied(List<ItemStack> items, boolean delete, boolean dying);
    }

    private final KitPlayerSettings.TrashMode mode;
    private final Emptied emptied;
    private final Component deleteName;
    private final List<Component> deleteLore;
    private volatile boolean dying;

    /**
     * @param deleteName the Delete button's name (Delete button mode)
     * @param deleteLore the Delete button's lore
     */
    TrashMenu(MenuContext ctx, Player viewer, Component title, KitPlayerSettings.TrashMode mode, Component deleteName,
              List<Component> deleteLore, Emptied emptied) {
        super(ctx, viewer, title, mode == KitPlayerSettings.TrashMode.DELETE_BUTTON ? BUTTON_ROWS : ROWS);
        this.mode = mode;
        this.emptied = emptied;
        this.deleteName = deleteName;
        this.deleteLore = List.copyOf(deleteLore);
    }

    KitPlayerSettings.TrashMode mode() {
        return this.mode;
    }

    /** Marks that the bin is about to close because its player died without keeping their inventory. */
    void dying() {
        this.dying = true;
    }

    @Override
    protected void draw() {
        if (this.mode == KitPlayerSettings.TrashMode.DELETE_BUTTON) {
            set(DELETE_SLOT, Items.icon(Material.LAVA_BUCKET, this.deleteName, this.deleteLore), click -> {
                click(Feedback.CLICK);
                this.emptied.emptied(take(), true, false);
            });
        }
    }

    @Override
    protected boolean acceptsItems() {
        return true;
    }

    @Override
    protected boolean isItemSlot(int slot) {
        return slot >= 0 && slot < ITEM_SLOTS;
    }

    @Override
    protected void closed() {
        List<ItemStack> contents = take();
        if (!contents.isEmpty()) {
            this.emptied.emptied(contents, this.mode == KitPlayerSettings.TrashMode.DELETE_ON_CLOSE, this.dying);
        }
    }

    /** Takes every item out of the item slots (copies) and clears them, before anything else happens to them. */
    private List<ItemStack> take() {
        List<ItemStack> contents = new ArrayList<>();
        for (int slot = 0; slot < ITEM_SLOTS; slot++) {
            ItemStack item = getInventory().getItem(slot);
            if (item != null && !item.isEmpty()) {
                contents.add(item.clone());
                getInventory().setItem(slot, null);
            }
        }
        return contents;
    }
}
