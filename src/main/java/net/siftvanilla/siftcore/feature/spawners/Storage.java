package net.siftvanilla.siftcore.feature.spawners;

import java.util.Map;
import java.util.TreeMap;

/**
 * The items one spawner holds: item key to amount, plus the running total. Not thread-safe: every change happens
 * under the economy lock (inside a transaction's apply, or a loot cycle holding the lock). {@link #used()} is
 * volatile so displays may read it without the lock. Bukkit-free.
 */
final class Storage {

    private final Map<String, Long> items = new TreeMap<>();
    private volatile long used;

    long used() {
        return this.used;
    }

    long amount(String item) {
        return this.items.getOrDefault(item, 0L);
    }

    boolean isEmpty() {
        return this.used == 0;
    }

    /** A copy of the contents (item key to amount, only kinds with more than zero). */
    Map<String, Long> snapshot() {
        return new TreeMap<>(this.items);
    }

    void add(String item, long amount) {
        if (amount <= 0) {
            return;
        }
        this.items.merge(item, amount, StorageMath::saturatingAdd);
        this.used = StorageMath.saturatingAdd(this.used, amount);
    }

    /** Removes up to {@code amount} of an item; returns how many were removed. */
    long take(String item, long amount) {
        long stored = amount(item);
        long taken = Math.min(stored, Math.max(0, amount));
        if (taken <= 0) {
            return 0;
        }
        if (taken == stored) {
            this.items.remove(item);
        } else {
            this.items.put(item, stored - taken);
        }
        this.used -= taken;
        return taken;
    }

    /** Replaces the contents (loading, and undoing a change). */
    void replace(Map<String, Long> contents) {
        this.items.clear();
        long total = 0;
        for (Map.Entry<String, Long> entry : contents.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                this.items.put(entry.getKey(), entry.getValue());
                total = StorageMath.saturatingAdd(total, entry.getValue());
            }
        }
        this.used = total;
    }
}
