package net.siftvanilla.siftcore.feature.tpa;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.event.inventory.InventoryType;

/**
 * Whether a player is busy in their own inventory, for "Requests open a pop-up". The client opens its own inventory
 * without telling the server (the open view still reads as the crafting screen), so the closest the server gets is the
 * clicks in it: a player who clicked in their own inventory within the last {@link #RECENT} counts as busy there, until
 * they close it. Thread-safe: clicks and closes arrive on the player's thread, the pop-up reads on the same thread.
 */
final class InventoryUse {

    /** How long after a click in their own inventory a player still counts as busy in it. */
    static final Duration RECENT = Duration.ofSeconds(5);

    private final Map<UUID, Long> clicks = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /** @param clock milliseconds, like {@link System#currentTimeMillis} */
    InventoryUse(LongSupplier clock) {
        this.clock = clock;
    }

    /** Whether an open view of this type is the player's own inventory (what the server sees with nothing open). */
    static boolean own(InventoryType type) {
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    /** Whether a click at {@code clickedAt} is recent enough at {@code now} to count as busy in the inventory. */
    static boolean recent(long clickedAt, long now) {
        long since = now - clickedAt;
        return since >= 0 && since < RECENT.toMillis();
    }

    /**
     * The player clicked in a window; only a click in their own inventory ({@code own}, see {@link #own}) is
     * remembered: a server window keeps the pop-up away on its own while it is open.
     */
    void clicked(UUID player, boolean own) {
        if (own) {
            this.clicks.put(player, this.clock.getAsLong());
        }
    }

    /** The player closed a window (their own inventory sends a close as well): not busy any more. */
    void closed(UUID player) {
        this.clicks.remove(player);
    }

    /** Whether the player is busy in their own inventory now: a click in it within {@link #RECENT}, not closed since. */
    boolean inUse(UUID player) {
        Long at = this.clicks.get(player);
        return at != null && recent(at, this.clock.getAsLong());
    }

    void forget(UUID player) {
        this.clicks.remove(player);
    }
}
