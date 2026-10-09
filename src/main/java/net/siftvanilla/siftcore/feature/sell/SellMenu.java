package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code /sell} menu: a five-row grid the player fills with items, a live total and a Sell button, plus Add
 * (moves everything sellable from the inventory into the grid), Give back (empties the grid into the inventory) and
 * Mastery. Selling keeps the menu open (anything that can't be sold stays in the grid, and a shulker box stays with
 * whatever could not be sold out of it); closing it sells nothing and gives every item back. Dying with the menu
 * open drops the items like the rest of the inventory, so the menu can't be used to keep items safe from a death.
 */
final class SellMenu extends Menu {

    /** Slots 0-44 hold the player's items. */
    static final int GRID = 45;
    static final int SLOT_ADD = 45;
    static final int SLOT_GIVE_BACK = 46;
    static final int SLOT_TOTAL = 48;
    static final int SLOT_SELL = 50;
    static final int SLOT_MASTERY = 52;

    private final SellMenus menus;
    private boolean dropOnClose;
    private boolean reopening;
    private long shownTotal;

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
        this.shownTotal = summary.total();
        set(SLOT_ADD, this.menus.addIcon(), click -> {
            if (!lock()) {
                return;
            }
            try {
                click(Feedback.CLICK);
                this.menus.addSellable(this);
            } finally {
                unlock();
            }
            redraw();
        });
        set(SLOT_GIVE_BACK, this.menus.giveBackIcon(), click -> {
            if (!lock()) {
                return;
            }
            try {
                click(Feedback.CLICK);
                this.menus.giveBack(this);
            } finally {
                unlock();
            }
            redraw();
        });
        set(SLOT_TOTAL, this.menus.totalIcon(this.viewer, summary), null);
        set(SLOT_SELL, this.menus.sellIcon(summary), click -> sell());
        if (this.menus.masteryShown()) {
            set(SLOT_MASTERY, this.menus.masteryIcon(), click -> {
                click(Feedback.CLICK);
                this.menus.openMastery(this);
            });
        }
        backup();
    }

    private void sell() {
        if (!lock()) {
            return;
        }
        try {
            this.menus.sell(this, this.shownTotal);
        } finally {
            unlock();
        }
        redraw();
    }

    @Override
    protected void itemsChanged() {
        redraw();
    }

    /** An item is about to leave (or be swapped out of) a grid slot: the copy drops it right away. */
    @Override
    protected void itemSlotClicked(int slot) {
        this.menus.backup(this, slot);
    }

    /** Copies the grid into the viewer's player data (see {@link net.siftvanilla.siftcore.ui.gui.GridBackup}). */
    void backup() {
        this.menus.backup(this, -1);
    }

    @Override
    protected void closed() {
        if (this.reopening) {
            // Opening this menu again (from a dialog's Back) makes the server close it first: not a real close.
            return;
        }
        this.menus.closed(this);
    }

    /**
     * Brings this menu back on screen from a dialog shown over it, grid and all. Returns false when it could not be
     * opened again (another plugin refused); it was closed then. Viewer's thread.
     */
    boolean reopen() {
        this.ctx.dialogs().markShown(this.viewer);
        redraw();
        this.reopening = true;
        try {
            this.viewer.openInventory(getInventory());
        } finally {
            this.reopening = false;
        }
        if (this.viewer.getOpenInventory().getTopInventory().getHolder(false) != this) {
            this.menus.closed(this);
            return false;
        }
        return true;
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

    /** The first empty grid slot, or -1 when the grid is full. */
    int emptySlot() {
        Inventory inventory = getInventory();
        for (int slot = 0; slot < GRID; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /** Marks that the viewer died without keeping their inventory: the grid drops on close. */
    void dropOnClose(boolean drop) {
        this.dropOnClose = drop;
    }

    boolean dropOnClose() {
        return this.dropOnClose;
    }
}
