package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired after a buy order ended early and the money it still held went back to its owner: cancelled by the owner or
 * staff, or run out of time. Information only; it is fired once the refund was stored.
 */
public final class OrderEndEvent extends SiftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** How the order ended. */
    public enum Reason {
        CANCELLED,
        EXPIRED
    }

    private final long order;
    private final UUID owner;
    private final String itemType;
    private final String variant;
    private final long refund;
    private final Reason reason;

    public OrderEndEvent(long order, UUID owner, String itemType, String variant, long refund, Reason reason) {
        this.order = order;
        this.owner = owner;
        this.itemType = itemType;
        this.variant = variant;
        this.refund = refund;
        this.reason = reason;
    }

    public long order() {
        return this.order;
    }

    public UUID owner() {
        return this.owner;
    }

    /** The item type as a namespaced key, e.g. {@code minecraft:diamond}. */
    public String itemType() {
        return this.itemType;
    }

    /** The exact variant, or null for plain items. */
    public String variant() {
        return this.variant;
    }

    /** The money that went back to the owner. */
    public long refund() {
        return this.refund;
    }

    public Reason reason() {
        return this.reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
