package net.siftvanilla.siftcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player in the AFK zone is paid shards for an interval spent there. Cancelling skips this payment
 * (the player keeps earning towards the next one). Fired on the player's thread.
 */
public final class AfkZoneRewardEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long shards;

    public AfkZoneRewardEvent(Player player, long shards) {
        this.player = player;
        this.shards = shards;
    }

    public Player player() {
        return this.player;
    }

    /** The shards about to be paid. */
    public long shards() {
        return this.shards;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
