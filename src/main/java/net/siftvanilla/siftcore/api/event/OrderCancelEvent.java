package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before a buy order is cancelled by its owner or by staff, before the held money is refunded. Cancelling the
 * event keeps the order open. Orders that run out of time are refunded without this event (expiry cannot be
 * blocked); {@link OrderEndEvent} reports every end after it happened.
 */
public final class OrderCancelEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Who cancels the order. */
    public enum Cause {
        /** The player who placed it. */
        OWNER,
        /** A staff member or the console. */
        STAFF
    }

    private final long order;
    private final UUID owner;
    private final String itemType;
    private final long refund;
    private final Cause cause;
    private final String reason;

    public OrderCancelEvent(long order, UUID owner, String itemType, long refund, Cause cause, String reason) {
        this.order = order;
        this.owner = owner;
        this.itemType = itemType;
        this.refund = refund;
        this.cause = cause;
        this.reason = reason == null ? "" : reason;
    }

    /** The order's id. */
    public long order() {
        return this.order;
    }

    /** The player who placed the order (and gets the refund). */
    public UUID owner() {
        return this.owner;
    }

    /** The wanted item type as a namespaced key, e.g. {@code minecraft:diamond}. */
    public String itemType() {
        return this.itemType;
    }

    /** The held money that goes back to the owner. */
    public long refund() {
        return this.refund;
    }

    public Cause cause() {
        return this.cause;
    }

    /** The reason staff gave (empty when the owner cancels or staff gave none). */
    public String reason() {
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
