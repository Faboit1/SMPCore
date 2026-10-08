package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code /sell} menu: a five-row grid the player fills with items, a live total and a Sell button. Selling
 * keeps the menu open (anything that can't be sold stays in the grid); closing it sells nothing and gives every
 * item back. Dying with the menu open drops the items like the rest of the inventory, so the menu can't be used to
 * keep items safe from a death.
 */
final class SellMenu extends Menu {

    /** Slots 0-44 hold the player's items. */
    static final int GRID = 45;
    static final int SLOT_TOTAL = 48;
    static final int SLOT_SELL = 50;

    private final SellMenus menus;
    private boolean dropOnClose;

    SellMenu(MenuContext ctx, Player viewer, SellMenus menus) {
        super(ctx, viewer, Component.text(ctx.lang().plain(SellMessages.MENU_TITLE)), 6);
        this.menus = menus;
    }

    @Override
    protected boolean isItemSlot(int slot) {
        return slot >= 0 && slot < GRID;
    }

    @Override
    protected boolean acceptsItems() {
        return true;
    }

    @Override
    protected void draw() {
        SellMenus.Summary summary = this.menus.summarize(this.viewer, getInventory());
        set(SLOT_TOTAL, this.menus.totalIcon(summary), null);
        set(SLOT_SELL, this.menus.sellIcon(summary), click -> sell());
    }

    private void sell() {
        if (!lock()) {
            return;
        }
        try {
            this.menus.sell(this);
        } finally {
            unlock();
        }
        redraw();
    }

    @Override
    protected void itemsChanged() {
        redraw();
    }

    @Override
    protected void closed() {
        this.menus.closed(this);
    }

    /** Takes every item out of the grid and returns them. */
    List<ItemStack> drain() {
        Inventory inventory = getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < GRID; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty()) {
                items.add(item);
                inventory.setItem(slot, null);
            }
        }
        return items;
    }

    /** Marks that the viewer died without keeping their inventory: the grid drops on close. */
    void dropOnClose(boolean drop) {
        this.dropOnClose = drop;
    }

    boolean dropOnClose() {
        return this.dropOnClose;
    }
}
