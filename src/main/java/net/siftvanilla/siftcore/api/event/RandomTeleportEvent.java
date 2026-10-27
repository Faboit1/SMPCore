package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.event.HandlerList;

/**
 * Fired on the player's thread after random teleport found a safe spot and right before the player pays and is
 * teleported. Cancelling stops the teleport; nothing has been charged at that point.
 */
public final class RandomTeleportEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String region;
    private final Location destination;
    private final long cost;

    public RandomTeleportEvent(UUID player, String region, Location destination, long cost) {
        this.player = player;
        this.region = region;
        this.destination = destination.clone();
        this.cost = cost;
    }

    public UUID player() {
        return this.player;
    }

    /** The region id from {@code features/rtp.yml}, e.g. {@code nether}. */
    public String region() {
        return this.region;
    }

    /** A copy of where the player would land. */
    public Location destination() {
        return this.destination.clone();
    }

    /** What the player pays (0 for free regions and staff teleports). */
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
