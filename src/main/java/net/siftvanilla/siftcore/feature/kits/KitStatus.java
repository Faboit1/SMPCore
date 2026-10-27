package net.siftvanilla.siftcore.feature.kits;

import java.time.Duration;

/** Where a player stands with a kit's cooldown. Pure. */
sealed interface KitStatus {

    /** The kit can be claimed now. */
    KitStatus READY = new Ready();

    /** The kit can be claimed now. */
    record Ready() implements KitStatus {
    }

    /**
     * The kit is on cooldown.
     *
     * @param left    time until it is ready
     * @param readyAt when it is ready (epoch milliseconds)
     */
    record Waiting(Duration left, long readyAt) implements KitStatus {

        /** The time left rounded up to whole seconds, so a few milliseconds never read as "0s". */
        Duration shown() {
            long seconds = Math.max(1, (this.left.toMillis() + 999) / 1000);
            return Duration.ofSeconds(seconds);
        }
    }

    /**
     * A once-only kit that was claimed.
     *
     * @param at when it was claimed (epoch milliseconds)
     */
    record Claimed(long at) implements KitStatus {
    }

    default boolean ready() {
        return this instanceof Ready;
    }
}
