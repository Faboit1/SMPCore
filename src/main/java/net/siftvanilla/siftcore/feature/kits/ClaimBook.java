package net.siftvanilla.siftcore.feature.kits;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each player last claimed each kit, in memory. Reads are safe from any thread; changes happen only inside
 * economy transactions (under the economy lock), so a claim's cooldown check and the new claim time are one atomic
 * step. Bounded by players who ever claimed a kit times the number of kits. Pure.
 */
final class ClaimBook {

    private final Map<UUID, Map<String, Long>> claims = new ConcurrentHashMap<>();

    /** Replaces everything (startup). */
    void load(Map<UUID, Map<String, Long>> loaded) {
        this.claims.clear();
        loaded.forEach((player, kits) -> {
            if (!kits.isEmpty()) {
                this.claims.put(player, new ConcurrentHashMap<>(kits));
            }
        });
    }

    /** When the player last claimed the kit, or null when never. */
    Long last(UUID player, String kit) {
        Map<String, Long> kits = this.claims.get(player);
        return kits == null ? null : kits.get(kit);
    }

    /** The player's claims by kit id (a copy). */
    Map<String, Long> of(UUID player) {
        Map<String, Long> kits = this.claims.get(player);
        return kits == null ? Map.of() : Map.copyOf(kits);
    }

    /** Records a claim; returns the previous claim time (null when none) for the undo. */
    Long set(UUID player, String kit, long at) {
        return this.claims.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(kit, at);
    }

    /** Puts back what {@link #set} or {@link #remove} returned: a claim time, or nothing when null. */
    void restore(UUID player, String kit, Long previous) {
        if (previous == null) {
            remove(player, kit);
        } else {
            set(player, kit, previous);
        }
    }

    /** Forgets one claim; returns its time (null when there was none). */
    Long remove(UUID player, String kit) {
        Map<String, Long> kits = this.claims.get(player);
        if (kits == null) {
            return null;
        }
        Long previous = kits.remove(kit);
        if (kits.isEmpty()) {
            this.claims.remove(player, kits);
        }
        return previous;
    }

    /** Forgets every claim of a player; returns them (empty when none). */
    Map<String, Long> removeAll(UUID player) {
        Map<String, Long> kits = this.claims.remove(player);
        return kits == null ? Map.of() : Map.copyOf(kits);
    }

    /** Puts back what {@link #removeAll} returned. */
    void restoreAll(UUID player, Map<String, Long> kits) {
        kits.forEach((kit, at) -> set(player, kit, at));
    }

    /** A deep copy of everything, for comparing with storage. */
    Map<UUID, Map<String, Long>> snapshot() {
        Map<UUID, Map<String, Long>> copy = new HashMap<>();
        this.claims.forEach((player, kits) -> {
            if (!kits.isEmpty()) {
                copy.put(player, Map.copyOf(kits));
            }
        });
        return copy;
    }

    /** Number of stored claims. */
    int size() {
        int total = 0;
        for (Map<String, Long> kits : this.claims.values()) {
            total += kits.size();
        }
        return total;
    }
}
