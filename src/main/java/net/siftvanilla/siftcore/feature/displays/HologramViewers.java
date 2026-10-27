package net.siftvanilla.siftcore.feature.displays;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The online players who turned the spawn holograms off ({@code show-spawn-holograms}). Every display entity is
 * hidden from them with {@code Player#hideEntity}: new entities as they spawn, existing ones when the player joins or
 * turns the switch off, and shown again when they turn it back on. Thread-safe; pure.
 */
final class HologramViewers {

    private final Set<UUID> hiding = ConcurrentHashMap.newKeySet();

    /** Records a player's choice; true when it changes what they should see (something to apply). */
    boolean set(UUID player, boolean shows) {
        return shows ? this.hiding.remove(player) : this.hiding.add(player);
    }

    /** A player left: their hides end with their session. */
    void forget(UUID player) {
        this.hiding.remove(player);
    }

    /** Whether the player hides the holograms now. */
    boolean hides(UUID player) {
        return this.hiding.contains(player);
    }

    /** The players who hide them, as a snapshot. */
    Set<UUID> hiding() {
        return Set.copyOf(this.hiding);
    }
}
