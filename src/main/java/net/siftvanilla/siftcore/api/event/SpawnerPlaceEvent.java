package net.siftvanilla.siftcore.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player places a SiftCore spawner, after the placement passed SiftCore's own checks. Cancelling
 * stops the placement: the block is not placed and the item stays in the player's hand. Fired on the player's thread.
 */
public final class SpawnerPlaceEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final String mob;

    public SpawnerPlaceEvent(Player player, Location location, String mob) {
        this.player = player;
        this.location = location.clone();
        this.mob = mob;
    }

    public Player player() {
        return this.player;
    }

    /** Where the spawner block goes. */
    public Location location() {
        return this.location.clone();
    }

    /** The mob id, e.g. {@code zombie}. */
    public String mob() {
        return this.mob;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
