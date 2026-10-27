package net.siftvanilla.siftcore.feature.friends;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Batching of friend request alerts per target. The first request in a quiet period is shown at once and opens a
 * window; requests arriving while the window is open are collected, and when it closes they are told in one line.
 * A window that collected something stays open for another round, so a steady stream of requests gives one line per
 * window and never a flood. A window that closes empty ends the batch. Thread-safe; pure (the caller runs the timers).
 */
public final class RequestBatcher {

    /** What to do with an alert that just arrived. */
    public enum Decision {
        /** Show it now and close the window after the batch time. */
        SHOW_NOW,
        /** A window is open: it was collected and is told when the window closes. */
        COLLECTED
    }

    private final Map<UUID, Set<UUID>> windows = new HashMap<>();

    /**
     * A request from {@code sender} to {@code target} should be told.
     *
     * @param batching false when batching is off (every alert is shown at once)
     */
    public synchronized Decision offer(UUID target, UUID sender, boolean batching) {
        if (!batching) {
            return Decision.SHOW_NOW;
        }
        Set<UUID> window = this.windows.get(target);
        if (window == null) {
            this.windows.put(target, new LinkedHashSet<>());
            return Decision.SHOW_NOW;
        }
        window.add(sender);
        return Decision.COLLECTED;
    }

    /**
     * The window of {@code target} closes: returns what it collected, in arrival order. When that is not empty the
     * window stays open (empty) for another round and the caller schedules the next close; when it is empty the
     * batch is over.
     */
    public synchronized List<UUID> close(UUID target) {
        Set<UUID> window = this.windows.get(target);
        if (window == null) {
            return List.of();
        }
        if (window.isEmpty()) {
            this.windows.remove(target);
            return List.of();
        }
        List<UUID> collected = List.copyOf(window);
        window.clear();
        return collected;
    }

    /** Forgets the target's window (they left). */
    public synchronized void forget(UUID target) {
        this.windows.remove(target);
    }

    public synchronized void clear() {
        this.windows.clear();
    }

    /** Open windows (for tests and the self-test). */
    public synchronized int size() {
        return this.windows.size();
    }
}
