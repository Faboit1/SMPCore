package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player adds spawner items to a placed SiftCore spawner stack. Cancelling stops the stacking: the
 * items stay in the player's hand. Fired on the player's thread.
 */
public final class SpawnerStackEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final String mob;
    private final UUID owner;
    private final int stack;
    private final int adding;

    public SpawnerStackEvent(Player player, Location location, String mob, UUID owner, int stack, int adding) {
        this.player = player;
        this.location = location.clone();
        this.mob = mob;
        this.owner = owner;
        this.stack = stack;
        this.adding = adding;
    }

    public Player player() {
        return this.player;
    }

    public Location location() {
        return this.location.clone();
    }

    /** The mob id, e.g. {@code zombie}. */
    public String mob() {
        return this.mob;
    }

    /** Who owns the spawner (may differ from the player when a team member stacks it). */
    public UUID owner() {
        return this.owner;
    }

    /** Spawners in the stack before this. */
    public int stack() {
        return this.stack;
    }

    /** Spawners being added. */
    public int adding() {
        return this.adding;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
