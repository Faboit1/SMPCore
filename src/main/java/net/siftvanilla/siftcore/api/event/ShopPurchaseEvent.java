package net.siftvanilla.siftcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired before a player buys from the server shop. Cancelling stops the purchase before any money moves. Fired on
 * the player's thread.
 */
public final class ShopPurchaseEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String entry;
    private final ItemStack item;
    private final int quantity;
    private final long unitPrice;
    private final long total;

    /**
     * @param player    the buyer
     * @param entry     the shop entry, {@code category/id}
     * @param item      one unit of what is bought
     * @param quantity  how many
     * @param unitPrice price of one
     * @param total     what the buyer pays
     */
    public ShopPurchaseEvent(Player player, String entry, ItemStack item, int quantity, long unitPrice, long total) {
        this.player = player;
        this.entry = entry;
        this.item = item.clone();
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.total = total;
    }

    public Player player() {
        return this.player;
    }

    /** The shop entry, {@code category/id}. */
    public String entry() {
        return this.entry;
    }

    /** A copy of one unit of what is bought. */
    public ItemStack item() {
        return this.item.clone();
    }

    public int quantity() {
        return this.quantity;
    }

    public long unitPrice() {
        return this.unitPrice;
    }

    /** What the buyer pays. */
    public long total() {
        return this.total;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
