package net.siftvanilla.siftcore.feature.stats;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers which blocks players placed recently, so mining them again does not count as blocks mined (no
 * place-and-break farming). Bounded: at most {@code capacity} positions are kept and the oldest are forgotten first;
 * entries also stop mattering once they are older than the window the caller passes in.
 * <p>
 * Block events run on many region threads at once, so the positions are split over independently locked stripes.
 * Every operation is O(1) except {@link #sweep}, which the feature runs off the world threads.
 */
final class PlacedBlocks {

    /** A block position in a world. */
    record Key(UUID world, long position) {
    }

    private static final int STRIPES = 16;

    private final List<LinkedHashMap<Key, Long>> stripes = new ArrayList<>(STRIPES);
    private volatile int perStripe;

    PlacedBlocks(int capacity) {
        for (int i = 0; i < STRIPES; i++) {
            this.stripes.add(new LinkedHashMap<>());
        }
        capacity(capacity);
    }

    /** Changes the bound; extra entries are dropped on the next insert into each stripe. */
    void capacity(int capacity) {
        this.perStripe = Math.max(1, capacity / STRIPES);
    }

    int capacity() {
        return this.perStripe * STRIPES;
    }

    /** Packs a block position like the game does (26 bits x, 26 bits z, 12 bits y). */
    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    private LinkedHashMap<Key, Long> stripe(Key key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return this.stripes.get(hash & (STRIPES - 1));
    }

    /** Records that a player placed a block at this position now. */
    void placed(UUID world, int x, int y, int z, long now) {
        put(new Key(world, pack(x, y, z)), now);
    }

    private void put(Key key, long placedAt) {
        LinkedHashMap<Key, Long> stripe = stripe(key);
        synchronized (stripe) {
            stripe.remove(key);
            stripe.put(key, placedAt);
            int limit = this.perStripe;
            Iterator<Map.Entry<Key, Long>> oldest = stripe.entrySet().iterator();
            while (stripe.size() > limit && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    /**
     * Forgets the position (the block is gone) and tells whether a player placed it less than {@code windowMillis}
     * ago.
     */
    boolean takeIfRecent(UUID world, int x, int y, int z, long now, long windowMillis) {
        Key key = new Key(world, pack(x, y, z));
        LinkedHashMap<Key, Long> stripe = stripe(key);
        Long placedAt;
        synchronized (stripe) {
            placedAt = stripe.remove(key);
        }
        return placedAt != null && now - placedAt < windowMillis;
    }

    /** Forgets the position (the block was destroyed some other way). */
    void forget(UUID world, int x, int y, int z) {
        Key key = new Key(world, pack(x, y, z));
        LinkedHashMap<Key, Long> stripe = stripe(key);
        synchronized (stripe) {
            stripe.remove(key);
        }
    }

    /**
     * Blocks moved by a piston keep their placement time at their new position. {@code positions} are the blocks
     * before the move, each {@code {x, y, z}}; all move by the same offset.
     */
    void moved(UUID world, List<int[]> positions, int dx, int dy, int dz) {
        List<Key> targets = new ArrayList<>(positions.size());
        List<Long> times = new ArrayList<>(positions.size());
        for (int[] p : positions) {
            Key from = new Key(world, pack(p[0], p[1], p[2]));
            LinkedHashMap<Key, Long> stripe = stripe(from);
            Long placedAt;
            synchronized (stripe) {
                placedAt = stripe.remove(from);
            }
            if (placedAt != null) {
                targets.add(new Key(world, pack(p[0] + dx, p[1] + dy, p[2] + dz)));
                times.add(placedAt);
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            put(targets.get(i), times.get(i));
        }
    }

    /** Drops entries older than the window; returns how many were dropped. */
    int sweep(long now, long windowMillis) {
        int dropped = 0;
        for (LinkedHashMap<Key, Long> stripe : this.stripes) {
            synchronized (stripe) {
                Iterator<Long> times = stripe.values().iterator();
                while (times.hasNext()) {
                    if (now - times.next() >= windowMillis) {
                        times.remove();
                        dropped++;
                    }
                }
            }
        }
        return dropped;
    }

    int size() {
        int size = 0;
        for (LinkedHashMap<Key, Long> stripe : this.stripes) {
            synchronized (stripe) {
                size += stripe.size();
            }
        }
        return size;
    }

    void clear() {
        for (LinkedHashMap<Key, Long> stripe : this.stripes) {
            synchronized (stripe) {
                stripe.clear();
            }
        }
    }
}
