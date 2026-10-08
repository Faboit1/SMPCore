package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code /trash} bin: four rows players can put anything into. Everything in it is deleted when it closes, however
 * it closes (the player closes it, opens something else, leaves or dies). The title says so; there is no confirmation.
 */
final class TrashMenu extends Menu {

    static final int ROWS = 4;

    private final Consumer<List<ItemStack>> deleted;

    /** @param deleted receives what was deleted (copies), on the viewer's thread; not called when it was empty */
    TrashMenu(MenuContext ctx, Player viewer, Component title, Consumer<List<ItemStack>> deleted) {
        super(ctx, viewer, title, ROWS);
        this.deleted = deleted;
    }

    @Override
    protected void draw() {
        // Starts empty; every slot takes items.
    }

    @Override
    protected boolean acceptsItems() {
        return true;
    }

    @Override
    protected boolean isItemSlot(int slot) {
        return slot >= 0 && slot < size();
    }

    @Override
    protected void closed() {
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : getInventory().getContents()) {
            if (item != null && !item.isEmpty()) {
                contents.add(item.clone());
            }
        }
        getInventory().clear();
        if (!contents.isEmpty()) {
            this.deleted.accept(contents);
        }
    }
}
