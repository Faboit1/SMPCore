package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Limits how often something happens: at most once per player per cooldown, and at most so many times per second
 * on the whole server. Used for kill effects (so a mass fight or a kill farm can't flood clients with particles)
 * and their previews. Thread-safe (one lock; calls are rare and short); pure, so it is unit tested.
 */
final class RateGate {

    /** The answer. */
    enum Outcome {
        /** Allowed (and counted). */
        OK,
        /** The same player did it too recently. */
        COOLDOWN,
        /** The server-wide limit for this second is reached. */
        BUSY
    }

    private static final long WINDOW_MILLIS = 1_000L;

    private final Map<UUID, Long> last = new HashMap<>();
    private final Deque<Long> recent = new ArrayDeque<>();

    /**
     * Asks to do it now. Nothing is counted when the answer is not {@link Outcome#OK}.
     *
     * @param cooldownMillis the shortest time between two of one player (0: no limit)
     * @param maxPerSecond   the most per second on the server (0: no limit)
     */
    synchronized Outcome tryAcquire(UUID player, long now, long cooldownMillis, int maxPerSecond) {
        Long previous = this.last.get(player);
        if (cooldownMillis > 0 && previous != null && now - previous < cooldownMillis) {
            return Outcome.COOLDOWN;
        }
        while (!this.recent.isEmpty() && now - this.recent.peekFirst() >= WINDOW_MILLIS) {
            this.recent.pollFirst();
        }
        if (maxPerSecond > 0 && this.recent.size() >= maxPerSecond) {
            return Outcome.BUSY;
        }
        this.recent.addLast(now);
        this.last.put(player, now);
        return Outcome.OK;
    }

    /** Forgets players whose last use is older than {@code keepMillis} (memory stays bounded by active players). */
    synchronized void sweep(long now, long keepMillis) {
        Iterator<Map.Entry<UUID, Long>> it = this.last.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > keepMillis) {
                it.remove();
            }
        }
    }

    synchronized void forget(UUID player) {
        this.last.remove(player);
    }

    synchronized int tracked() {
        return this.last.size();
    }
}
