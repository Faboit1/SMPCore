package net.siftvanilla.siftcore.feature.kits;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A read-only look into another player's ender chest ({@code /ec <player>} for staff). The items are copies taken on
 * the owner's thread, so nothing here can change or duplicate the real ender chest; every click is cancelled.
 */
final class EnderChestPeekMenu extends Menu {

    private final List<ItemStack> items;

    /** @param items copies of the ender chest's slots in order (null or empty for free slots) */
    EnderChestPeekMenu(MenuContext ctx, Player viewer, Component title, List<ItemStack> items) {
        super(ctx, viewer, title, Math.clamp((items.size() + 8) / 9, 1, 6));
        this.items = items;
    }

    @Override
    protected void draw() {
        for (int slot = 0; slot < Math.min(size(), this.items.size()); slot++) {
            ItemStack item = this.items.get(slot);
            if (item != null && !item.isEmpty()) {
                set(slot, item.clone(), null);
            }
        }
    }
}
