package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before the owner raises the price of an active buy order or asks for more items, before the extra money is
 * held. Cancelling keeps the order as it is and charges nothing.
 */
public final class OrderEditEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long order;
    private final UUID owner;
    private final long oldPrice;
    private final long newPrice;
    private final int oldQuantity;
    private final int newQuantity;
    private final long extra;

    public OrderEditEvent(long order, UUID owner, long oldPrice, long newPrice, int oldQuantity, int newQuantity, long extra) {
        this.order = order;
        this.owner = owner;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.oldQuantity = oldQuantity;
        this.newQuantity = newQuantity;
        this.extra = extra;
    }

    /** The order's id. */
    public long order() {
        return this.order;
    }

    /** The player who placed the order (and pays the extra). */
    public UUID owner() {
        return this.owner;
    }

    public long oldPrice() {
        return this.oldPrice;
    }

    public long newPrice() {
        return this.newPrice;
    }

    public int oldQuantity() {
        return this.oldQuantity;
    }

    public int newQuantity() {
        return this.newQuantity;
    }

    /** The extra money held for the order after the change. */
    public long extra() {
        return this.extra;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
