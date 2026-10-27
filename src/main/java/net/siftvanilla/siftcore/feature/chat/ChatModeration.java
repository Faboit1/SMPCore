package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;

/**
 * Staff controls over public chat: a lock (only players with the bypass permission can talk) and slow mode (one
 * message per player every so often). Both last until staff change them or the server restarts. Thread-safe.
 */
final class ChatModeration {

    private volatile boolean locked;
    private volatile Duration slow = Duration.ZERO;

    boolean locked() {
        return this.locked;
    }

    /** Locks or unlocks; returns false when it already was that way. */
    boolean lock(boolean lock) {
        synchronized (this) {
            if (this.locked == lock) {
                return false;
            }
            this.locked = lock;
            return true;
        }
    }

    /** The slow mode gap, zero when slow mode is off. */
    Duration slow() {
        return this.slow;
    }

    void slow(Duration slow) {
        this.slow = slow == null || slow.isNegative() ? Duration.ZERO : slow;
    }
}
