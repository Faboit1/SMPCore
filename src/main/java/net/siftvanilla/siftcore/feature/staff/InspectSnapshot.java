package net.siftvanilla.siftcore.feature.staff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * A copy of a target's items taken on the target's thread, shown read-only to staff. Taking an item later only
 * succeeds if the live slot still holds exactly the item that was shown (same item, same data, same amount), so a
 * stale view can never remove something different. Generic over the item type so the rule is testable without a
 * server.
 *
 * @param <T> the item type ({@code ItemStack} in game)
 */
final class InspectSnapshot<T> {

    private final InspectLayout.Kind kind;
    private final List<T> items;
    private final long takenAt;

    /**
     * @param items one entry per source index of {@code kind} (null or empty for an empty slot)
     */
    InspectSnapshot(InspectLayout.Kind kind, List<T> items, long takenAt) {
        if (items.size() != kind.sourceSize()) {
            throw new IllegalArgumentException("Expected " + kind.sourceSize() + " slots, got " + items.size());
        }
        this.kind = kind;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.takenAt = takenAt;
    }

    InspectLayout.Kind kind() {
        return this.kind;
    }

    long takenAt() {
        return this.takenAt;
    }

    /** The item that was in {@code sourceIndex}, or null. */
    T item(int sourceIndex) {
        return sourceIndex < 0 || sourceIndex >= this.items.size() ? null : this.items.get(sourceIndex);
    }

    /** Number of slots holding something, given what counts as empty. */
    int stacks(Predicate<T> empty) {
        int count = 0;
        for (T item : this.items) {
            if (item != null && !empty.test(item)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes the item from the live slot if it is still exactly {@code expected}. Call on the thread that owns the
     * container. Returns what was removed, or null when the slot changed (then nothing is touched).
     *
     * @param expected what the snapshot showed
     * @param current  what the live slot holds now (may be null)
     * @param remove   empties the live slot
     * @param same     exact equality (item, data and amount)
     * @param empty    what counts as an empty slot
     */
    static <T> T takeIfUnchanged(T expected, T current, Runnable remove, BiPredicate<T, T> same, Predicate<T> empty) {
        if (expected == null || empty.test(expected) || current == null || empty.test(current)) {
            return null;
        }
        if (!same.test(expected, current)) {
            return null;
        }
        remove.run();
        return current;
    }
}
