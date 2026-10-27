package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;

/**
 * Fired before items are delivered to someone's buy order, before the items leave the seller. Cancelling stops that
 * delivery; the items stay where they are and no money moves.
 */
public final class OrderFillEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** How the items are delivered. */
    public enum Source {
        /** Through the delivery menu of the order. */
        MENU,
        /** With quick deliver, straight from the seller's inventory. */
        QUICK,
        /** Routed from a sale (/sell) to an order that pays more than the server. */
        SELL
    }

    private final long order;
    private final UUID owner;
    private final UUID seller;
    private final Material item;
    private final String variant;
    private final int amount;
    private final long priceEach;
    private final long tax;
    private final Source source;

    public OrderFillEvent(long order, UUID owner, UUID seller, Material item, String variant, int amount, long priceEach, long tax,
                          Source source) {
        this.order = order;
        this.owner = owner;
        this.seller = seller;
        this.item = item;
        this.variant = variant;
        this.amount = amount;
        this.priceEach = priceEach;
        this.tax = tax;
        this.source = source;
    }

    /** The order's id. */
    public long order() {
        return this.order;
    }

    /** The player who placed the order. */
    public UUID owner() {
        return this.owner;
    }

    /** The player delivering the items. */
    public UUID seller() {
        return this.seller;
    }

    public Material item() {
        return this.item;
    }

    /** The exact variant the order wants (e.g. {@code enchant:minecraft:mending:1}), or null for plain items. */
    public String variant() {
        return this.variant;
    }

    /** How many items are delivered. */
    public int amount() {
        return this.amount;
    }

    public long priceEach() {
        return this.priceEach;
    }

    /** What the order pays out of its held money: amount times price each. */
    public long paid() {
        return Math.multiplyExact(this.priceEach, (long) this.amount);
    }

    /** The tax the seller pays on this delivery. */
    public long tax() {
        return this.tax;
    }

    /** What the seller receives after tax. */
    public long payout() {
        return paid() - this.tax;
    }

    /** How the items are delivered. */
    public Source source() {
        return this.source;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
