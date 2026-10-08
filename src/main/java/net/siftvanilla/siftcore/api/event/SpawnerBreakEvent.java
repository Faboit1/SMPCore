package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player picks up a placed SiftCore spawner, after the pickup passed SiftCore's own checks (access,
 * silk touch, storage size). Cancelling keeps the spawner in place with its stack and storage. Fired on the player's
 * thread.
 */
public final class SpawnerBreakEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final String mob;
    private final UUID owner;
    private final int stack;
    private final long storedItems;
    private final long storedXp;

    public SpawnerBreakEvent(Player player, Location location, String mob, UUID owner, int stack, long storedItems, long storedXp) {
        this.player = player;
        this.location = location.clone();
        this.mob = mob;
        this.owner = owner;
        this.stack = stack;
        this.storedItems = storedItems;
        this.storedXp = storedXp;
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

    public UUID owner() {
        return this.owner;
    }

    /** Spawners in the stack (the player gets this many spawner items). */
    public int stack() {
        return this.stack;
    }

    /** Items in the storage. */
    public long storedItems() {
        return this.storedItems;
    }

    /** XP in the storage. */
    public long storedXp() {
        return this.storedXp;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
