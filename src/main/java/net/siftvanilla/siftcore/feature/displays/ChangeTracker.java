package net.siftvanilla.siftcore.feature.displays;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the last value produced for each key and says whether a new one differs, so a refresh only sends text
 * to players when it actually changed. Thread-safe.
 */
final class ChangeTracker<K, V> {

    private final Map<K, V> last = new ConcurrentHashMap<>();

    /** Stores {@code value}; true when it differs from the previous value or there was none. */
    boolean update(K key, V value) {
        Objects.requireNonNull(value);
        return !value.equals(this.last.put(key, value));
    }

    /** The last stored value, or null. */
    V get(K key) {
        return this.last.get(key);
    }

    void forget(K key) {
        this.last.remove(key);
    }

    void clear() {
        this.last.clear();
    }

    int size() {
        return this.last.size();
    }
}
