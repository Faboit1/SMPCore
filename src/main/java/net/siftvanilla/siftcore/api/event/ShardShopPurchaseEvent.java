package net.siftvanilla.siftcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player buys from the shard shop. Cancelling stops the purchase before any shards move. Fired on the
 * player's thread.
 */
public final class ShardShopPurchaseEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String offer;
    private final String kind;
    private final int quantity;
    private final long cost;

    /**
     * @param offer    the offer id from features/shards.yml
     * @param kind     {@code key} (crate keys) or {@code item}
     * @param quantity how many units of the offer
     * @param cost     the shards the player pays in total
     */
    public ShardShopPurchaseEvent(Player player, String offer, String kind, int quantity, long cost) {
        this.player = player;
        this.offer = offer;
        this.kind = kind;
        this.quantity = quantity;
        this.cost = cost;
    }

    public Player player() {
        return this.player;
    }

    /** The offer id from features/shards.yml. */
    public String offer() {
        return this.offer;
    }

    /** {@code key} or {@code item}. */
    public String kind() {
        return this.kind;
    }

    public int quantity() {
        return this.quantity;
    }

    /** The shards the player pays in total. */
    public long cost() {
        return this.cost;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
