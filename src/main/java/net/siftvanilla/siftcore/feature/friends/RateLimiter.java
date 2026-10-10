package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Sliding one-minute buckets for friend requests, one per player and one per (hashed) address, so a player with
 * several accounts can't multiply the rate. Held in memory only: they reset on restart (the daily cap does not, it
 * comes from the history). Thread-safe; pure (the caller passes the time).
 */
public final class RateLimiter {

    /** The window every bucket counts over. */
    public static final long WINDOW_MILLIS = 60_000L;

    private final Map<String, Deque<Long>> buckets = new HashMap<>();

    /**
     * Takes one request from the player's and the address's bucket if both have room, and returns
     * {@link Duration#ZERO}; otherwise takes nothing and returns how long until the fuller bucket has room.
     *
     * @param ipHash the player's hashed address, or null when unknown (only the player's bucket counts then)
     */
    public synchronized Duration tryAcquire(UUID player, String ipHash, int perMinute, long now) {
        Deque<Long> own = bucket("u:" + player, now);
        Deque<Long> address = ipHash == null ? null : bucket("i:" + ipHash, now);
        long wait = 0;
        if (own.size() >= perMinute) {
            wait = Math.max(wait, waitFor(own, perMinute, now));
        }
        if (address != null && address.size() >= perMinute) {
            wait = Math.max(wait, waitFor(address, perMinute, now));
        }
        if (wait > 0) {
            return Duration.ofMillis(wait);
        }
        own.addLast(now);
        if (address != null) {
            address.addLast(now);
        }
        return Duration.ZERO;
    }

    private Deque<Long> bucket(String key, long now) {
        Deque<Long> deque = this.buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        drop(deque, now);
        return deque;
    }

    /** Time until the bucket holds fewer than {@code perMinute} entries. */
    private static long waitFor(Deque<Long> deque, int perMinute, long now) {
        int over = deque.size() - perMinute;
        Iterator<Long> it = deque.iterator();
        long entry = it.next();
        for (int i = 0; i < over && it.hasNext(); i++) {
            entry = it.next();
        }
        return Math.max(1, entry + WINDOW_MILLIS - now);
    }

    private static void drop(Deque<Long> deque, long now) {
        while (!deque.isEmpty() && deque.peekFirst() <= now - WINDOW_MILLIS) {
            deque.pollFirst();
        }
    }

    /** Forgets buckets with nothing left in the window. */
    public synchronized void prune(long now) {
        this.buckets.values().forEach(deque -> drop(deque, now));
        this.buckets.values().removeIf(Deque::isEmpty);
    }

    /** Number of buckets held (for tests and the self-test). */
    public synchronized int size() {
        return this.buckets.size();
    }
}
