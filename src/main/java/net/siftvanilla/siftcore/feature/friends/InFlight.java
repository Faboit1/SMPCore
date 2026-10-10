package net.siftvanilla.siftcore.feature.friends;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The actions a player has queued, one per pair: while an action of a player on another player is on its way to the
 * database, a second action on the same pair is refused ("Still working on that."). This keeps a burst of clicks or
 * commands down to one write, on top of the units being idempotent anyway. Thread-safe (one concurrent set of
 * pair keys, so acquiring and releasing never race); pure.
 */
public final class InFlight {

    private record Key(UUID actor, UUID other) {
    }

    private final Set<Key> queued = ConcurrentHashMap.newKeySet();

    /** Marks the pair as busy; false when an action on it is already queued. */
    public boolean tryAcquire(UUID actor, UUID other) {
        return this.queued.add(new Key(actor, other));
    }

    /** Frees the pair again. */
    public void release(UUID actor, UUID other) {
        this.queued.remove(new Key(actor, other));
    }

    /** Whether an action of {@code actor} on {@code other} is queued. */
    public boolean busy(UUID actor, UUID other) {
        return this.queued.contains(new Key(actor, other));
    }

    /** Queued actions (for the self-test and tests). */
    public int size() {
        return this.queued.size();
    }
}
