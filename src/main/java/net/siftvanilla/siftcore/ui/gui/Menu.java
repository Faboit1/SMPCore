package net.siftvanilla.siftcore.ui.gui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Feedback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A chest GUI for one viewer. Every click in the menu is cancelled and routed to the slot's handler on the viewer's
 * thread; items can only be moved into it when a subclass opts in with {@link #acceptsItems()}. While an async
 * action runs the menu is busy and ignores clicks, so spamming cannot start an action twice.
 * <p>
 * Titles are plain text in the default title colour.
 */
public abstract class Menu implements InventoryHolder {

    protected final MenuContext ctx;
    protected final Player viewer;
    private final Inventory inventory;
    private final MenuItem[] items;
    private final AtomicBoolean busy = new AtomicBoolean();
    private boolean drawing;

    protected Menu(MenuContext ctx, Player viewer, Component title, int rows) {
        this.ctx = ctx;
        this.viewer = viewer;
        int size = Math.clamp(rows, 1, 6) * 9;
        this.inventory = Bukkit.createInventory(this, size, title);
        this.items = new MenuItem[size];
    }

    @Override
    public final Inventory getInventory() {
        return this.inventory;
    }

    public Player viewer() {
        return this.viewer;
    }

    public int size() {
        return this.items.length;
    }

    /** Fills the slots. Called on open and on {@link #redraw()}. */
    protected abstract void draw();

    /** Opens (or re-opens) the menu for its viewer, on the viewer's thread. */
    public final void open() {
        this.ctx.scheduler().entity(this.viewer, () -> {
            redrawNow();
            this.viewer.openInventory(this.inventory);
        }, null);
    }

    /** Redraws all slots on the viewer's thread. */
    public final void redraw() {
        if (this.ctx.scheduler().owns(this.viewer)) {
            redrawNow();
        } else {
            this.ctx.scheduler().entity(this.viewer, this::redrawNow, null);
        }
    }

    private void redrawNow() {
        this.drawing = true;
        try {
            for (int i = 0; i < this.items.length; i++) {
                if (!isItemSlot(i)) {
                    this.items[i] = null;
                    this.inventory.setItem(i, null);
                }
            }
            draw();
        } finally {
            this.drawing = false;
        }
    }

    protected final void set(int slot, ItemStack icon, MenuItem.Handler handler) {
        if (slot < 0 || slot >= this.items.length) {
            return;
        }
        this.items[slot] = new MenuItem(icon, handler);
        this.inventory.setItem(slot, icon);
    }

    protected final void clear(int slot) {
        if (slot >= 0 && slot < this.items.length) {
            this.items[slot] = null;
            this.inventory.setItem(slot, null);
        }
    }

    /** True while a redraw is running. */
    protected final boolean drawing() {
        return this.drawing;
    }

    public final boolean busy() {
        return this.busy.get();
    }

    /**
     * Runs an async action with the menu locked. Clicks are ignored until it completes; {@code then} runs on the
     * viewer's thread with the result (or null on failure, after the error was reported).
     */
    protected final <T> void runBusy(CompletableFuture<T> action, Consumer<T> then) {
        if (!this.busy.compareAndSet(false, true)) {
            action.cancel(false);
            return;
        }
        action.whenComplete((result, error) -> this.ctx.scheduler().entity(this.viewer, () -> {
            this.busy.set(false);
            if (error != null) {
                this.ctx.messenger().send(this.viewer, CoreMessages.ACTION_FAILED);
                then.accept(null);
            } else {
                then.accept(result);
            }
        }, () -> this.busy.set(false)));
    }

    /** Locks the menu; returns false if it was already busy. Pair with {@link #unlock()}. */
    protected final boolean lock() {
        return this.busy.compareAndSet(false, true);
    }

    protected final void unlock() {
        this.busy.set(false);
    }

    protected final void click(Feedback feedback) {
        this.ctx.messenger().feedback(this.viewer, feedback);
    }

    /** Slots players may place items into (only consulted when {@link #acceptsItems()}). */
    protected boolean isItemSlot(int slot) {
        return false;
    }

    /** Whether players may move their own items into {@link #isItemSlot(int)} slots. */
    protected boolean acceptsItems() {
        return false;
    }

    /** Called after items were moved into or out of item slots (on the viewer's thread, next tick). */
    protected void itemsChanged() {
    }

    /** Called when the viewer closes the menu (also when another screen replaces it). */
    protected void closed() {
    }

    // ------------------------------------------------------------------ events (called by MenuListener)

    final void handleClick(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        boolean top = clicked == this.inventory;
        if (event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
            // Double-click gathering would pull icons (or deposited items) out of the menu.
            event.setCancelled(true);
            return;
        }
        if (!top) {
            if (clicked == null) {
                return;
            }
            if (event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
                if (acceptsItems() && !this.busy.get()) {
                    ItemStack moving = event.getCurrentItem();
                    if (moving != null && !moving.isEmpty()) {
                        ItemStack rest = depositIntoItemSlots(moving);
                        event.setCurrentItem(rest);
                        scheduleItemsChanged();
                    }
                }
            }
            return;
        }
        if (acceptsItems() && isItemSlot(event.getSlot())) {
            if (this.busy.get()) {
                event.setCancelled(true);
                return;
            }
            scheduleItemsChanged();
            return;
        }
        event.setCancelled(true);
        if (this.busy.get()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= this.items.length) {
            return;
        }
        MenuItem item = this.items[slot];
        if (item == null || item.handler() == null) {
            return;
        }
        item.handler().click(new ClickContext(this.viewer, event.getClick(), slot));
    }

    /** Moves as much of {@code stack} as fits into the item slots; returns what is left (or null). */
    private ItemStack depositIntoItemSlots(ItemStack stack) {
        ItemStack remaining = stack.clone();
        for (int slot = 0; slot < this.items.length && remaining.getAmount() > 0; slot++) {
            if (!isItemSlot(slot)) {
                continue;
            }
            ItemStack existing = this.inventory.getItem(slot);
            if (existing != null && !existing.isEmpty() && existing.isSimilar(remaining)) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                int moved = Math.min(space, remaining.getAmount());
                if (moved > 0) {
                    existing.setAmount(existing.getAmount() + moved);
                    remaining.setAmount(remaining.getAmount() - moved);
                }
            }
        }
        for (int slot = 0; slot < this.items.length && remaining.getAmount() > 0; slot++) {
            if (!isItemSlot(slot)) {
                continue;
            }
            ItemStack existing = this.inventory.getItem(slot);
            if (existing == null || existing.isEmpty()) {
                int moved = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
                ItemStack placed = remaining.clone();
                placed.setAmount(moved);
                this.inventory.setItem(slot, placed);
                remaining.setAmount(remaining.getAmount() - moved);
            }
        }
        return remaining.getAmount() > 0 ? remaining : null;
    }

    final void handleDrag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw < this.items.length) {
                if (!acceptsItems() || !isItemSlot(raw) || this.busy.get()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (acceptsItems()) {
            scheduleItemsChanged();
        }
    }

    final void handleClose() {
        closed();
    }

    private void scheduleItemsChanged() {
        this.ctx.scheduler().entityLater(this.viewer, this::itemsChanged, null, 1L);
    }
}
