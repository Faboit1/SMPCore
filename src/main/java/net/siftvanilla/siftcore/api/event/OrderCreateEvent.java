package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player places a buy order, before any money is held. Cancelling stops the order and nothing is
 * charged.
 */
public final class OrderCreateEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID owner;
    private final Material item;
    private final String variant;
    private final int quantity;
    private final long priceEach;

    public OrderCreateEvent(UUID owner, Material item, String variant, int quantity, long priceEach) {
        this.owner = owner;
        this.item = item;
        this.variant = variant;
        this.quantity = quantity;
        this.priceEach = priceEach;
    }

    /** The player placing the order. */
    public UUID owner() {
        return this.owner;
    }

    /** The item type wanted. */
    public Material item() {
        return this.item;
    }

    /**
     * The exact variant wanted, e.g. {@code enchant:minecraft:mending:1} for a Mending book, or null for plain items
     * of {@link #item()}.
     */
    public String variant() {
        return this.variant;
    }

    /** How many items are wanted. */
    public int quantity() {
        return this.quantity;
    }

    /** What the buyer pays for each item delivered. */
    public long priceEach() {
        return this.priceEach;
    }

    /** The money held for the order: quantity times price each. */
    public long total() {
        return Math.multiplyExact(this.priceEach, (long) this.quantity);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
