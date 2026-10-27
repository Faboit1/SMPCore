package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired after the owner of a buy order collected delivered items and the collection was stored. Information only;
 * the items are on their way to the owner's inventory (or claim box).
 */
public final class OrderCollectEvent extends SiftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long order;
    private final UUID owner;
    private final String itemType;
    private final String variant;
    private final int amount;

    public OrderCollectEvent(long order, UUID owner, String itemType, String variant, int amount) {
        this.order = order;
        this.owner = owner;
        this.itemType = itemType;
        this.variant = variant;
        this.amount = amount;
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

    /** How many items were collected. */
    public int amount() {
        return this.amount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
