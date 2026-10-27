package net.siftvanilla.siftcore.feature.crates;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player's virtual keys and the grant references applied recently, in memory. Changed only inside economy
 * transactions (under the economy lock), read from any thread. Pure logic.
 * <p>
 * Memory: one entry per player and crate with keys, plus one per grant reference remembered (bounded by the
 * configured retention, for example the keyall's one reference per player and run).
 */
final class KeyBook {

    /** The most keys of one crate a player can hold. */
    static final int MAX_KEYS = 1_000_000;

    private final Map<UUID, Map<String, Integer>> keys = new ConcurrentHashMap<>();
    private final Map<String, Long> refs = new ConcurrentHashMap<>();

    int get(UUID player, String crate) {
        Map<String, Integer> map = this.keys.get(player);
        if (map == null) {
            return 0;
        }
        Integer amount = map.get(crate);
        return amount == null ? 0 : amount;
    }

    /** A snapshot of a player's keys by crate (only crates with keys). */
    Map<String, Integer> of(UUID player) {
        Map<String, Integer> map = this.keys.get(player);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    /**
     * Changes a player's keys. Never leaves a negative count (callers check first, under the economy lock).
     *
     * @throws IllegalStateException when the result would be negative or above {@link #MAX_KEYS}
     */
    void add(UUID player, String crate, int delta) {
        if (delta == 0) {
            return;
        }
        this.keys.compute(player, (uuid, map) -> {
            Map<String, Integer> next = map == null ? new ConcurrentHashMap<>() : map;
            int current = next.getOrDefault(crate, 0);
            long result = (long) current + delta;
            if (result < 0 || result > MAX_KEYS) {
                throw new IllegalStateException("Keys of " + uuid + " for " + crate + " would become " + result);
            }
            if (result == 0) {
                next.remove(crate);
            } else {
                next.put(crate, (int) result);
            }
            return next.isEmpty() ? null : next;
        });
    }

    boolean applied(String ref) {
        return this.refs.containsKey(ref);
    }

    void remember(String ref, long at) {
        this.refs.put(ref, at);
    }

    void forget(String ref) {
        this.refs.remove(ref);
    }

    /** Forgets references applied before {@code cutoff}; returns how many. */
    int forgetOlderThan(long cutoff) {
        int[] removed = {0};
        this.refs.entrySet().removeIf(entry -> {
            boolean old = entry.getValue() < cutoff;
            if (old) {
                removed[0]++;
            }
            return old;
        });
        return removed[0];
    }

    int refCount() {
        return this.refs.size();
    }

    /** Number of players holding at least one key. */
    int holders() {
        return this.keys.size();
    }

    /** Total keys of a crate across all players. */
    long total(String crate) {
        long total = 0;
        for (Map<String, Integer> map : this.keys.values()) {
            total += map.getOrDefault(crate, 0);
        }
        return total;
    }

    /** A deep copy of every count (take it under the economy lock for a consistent view). */
    Map<UUID, Map<String, Integer>> snapshot() {
        Map<UUID, Map<String, Integer>> copy = new HashMap<>();
        this.keys.forEach((uuid, map) -> copy.put(uuid, Map.copyOf(map)));
        return copy;
    }

    /** Replaces everything (startup). */
    void load(Map<UUID, Map<String, Integer>> loadedKeys, Map<String, Long> loadedRefs) {
        this.keys.clear();
        this.refs.clear();
        loadedKeys.forEach((uuid, map) -> {
            Map<String, Integer> copy = new ConcurrentHashMap<>();
            map.forEach((crate, amount) -> {
                if (amount != null && amount > 0) {
                    copy.put(crate, Math.min(amount, MAX_KEYS));
                }
            });
            if (!copy.isEmpty()) {
                this.keys.put(uuid, copy);
            }
        });
        this.refs.putAll(loadedRefs);
    }
}
