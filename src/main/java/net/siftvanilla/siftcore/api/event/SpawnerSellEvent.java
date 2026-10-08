package net.siftvanilla.siftcore.api.event;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player sells the loot stored in a SiftCore spawner ("Sell all" in its storage), after the sale was
 * priced and before anything is taken or paid. Cancelling keeps the loot in the spawner. Fired on the player's thread.
 */
public final class SpawnerSellEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final String mob;
    private final UUID owner;
    private final Map<Material, Long> items;
    private final long total;
    private final double multiplier;

    public SpawnerSellEvent(Player player, Location location, String mob, UUID owner, Map<Material, Long> items, long total,
                            double multiplier) {
        this.player = player;
        this.location = location.clone();
        this.mob = mob;
        this.owner = owner;
        this.items = items.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(items));
        this.total = total;
        this.multiplier = multiplier;
    }

    /** The seller, who receives the money. */
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

    /** How many of each item are sold. */
    public Map<Material, Long> items() {
        return this.items;
    }

    /** The money the player receives, with their multiplier applied. */
    public long total() {
        return this.total;
    }

    /** The player's sell multiplier (1.0 when they have none). */
    public double multiplier() {
        return this.multiplier;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
