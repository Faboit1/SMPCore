package net.siftvanilla.siftcore.feature.stats;

import java.util.concurrent.CompletableFuture;

/**
 * One player's write-behind state. Immutable: {@link StatsStore} replaces it atomically per player, so every change
 * is a pure transition that the unit tests can drive directly.
 * <p>
 * A change lives in exactly one place at any time: {@code pending} (not written), {@code inflight} (being written)
 * or {@code base} (confirmed by the database). A successful write folds {@code inflight} into {@code base}; a failed
 * one puts it back in front of {@code pending}. Only one database operation (a write or a load) runs per player at
 * a time, so writes reach the database in the order the events happened and a load never races a write.
 *
 * @param base       what the database holds after every confirmed write, or null while not loaded
 * @param inflight   the change being written right now, or null
 * @param pending    changes not written yet ({@link StatsDelta#NONE} when there are none)
 * @param loading    a load is running
 * @param waiting    completes with the stats once loaded; null when nobody asked for them
 * @param online     the player is on this server (online records are never evicted)
 * @param lastAccess when the record was last used, for evicting offline records
 * @param failures   writes of this player that failed in a row (0 after a successful one)
 */
record PlayerRecord(StatsSnapshot base, StatsDelta inflight, StatsDelta pending, boolean loading,
                    CompletableFuture<StatsSnapshot> waiting, boolean online, long lastAccess, int failures) {

    static PlayerRecord fresh(long now) {
        return new PlayerRecord(null, null, StatsDelta.NONE, false, null, false, now, 0);
    }

    PlayerRecord add(StatsDelta delta) {
        return new PlayerRecord(this.base, this.inflight, this.pending.then(delta), this.loading, this.waiting, this.online,
            this.lastAccess, this.failures);
    }

    PlayerRecord touch(long now) {
        return new PlayerRecord(this.base, this.inflight, this.pending, this.loading, this.waiting, this.online, now, this.failures);
    }

    PlayerRecord online(boolean online, long now) {
        return new PlayerRecord(this.base, this.inflight, this.pending, this.loading, this.waiting, online, now, this.failures);
    }

    PlayerRecord waiting(CompletableFuture<StatsSnapshot> future) {
        return new PlayerRecord(this.base, this.inflight, this.pending, this.loading, future, this.online, this.lastAccess,
            this.failures);
    }

    boolean loaded() {
        return this.base != null;
    }

    /** A write may start: nothing else is running for this player and there is something to write. */
    boolean canFlush() {
        return this.inflight == null && !this.loading && !this.pending.isEmpty();
    }

    /** Moves the pending changes in flight. Call only when {@link #canFlush()}. */
    PlayerRecord startFlush() {
        if (!canFlush()) {
            throw new IllegalStateException("Cannot start a write now");
        }
        return new PlayerRecord(this.base, this.pending, StatsDelta.NONE, false, this.waiting, this.online, this.lastAccess,
            this.failures);
    }

    /** The write in flight finished. */
    PlayerRecord flushed(boolean success) {
        if (this.inflight == null) {
            return this;
        }
        if (success) {
            StatsSnapshot confirmed = this.base == null ? null : this.inflight.applyTo(this.base);
            return new PlayerRecord(confirmed, null, this.pending, this.loading, this.waiting, this.online, this.lastAccess, 0);
        }
        return new PlayerRecord(this.base, null, this.inflight.then(this.pending), this.loading, this.waiting, this.online,
            this.lastAccess, this.failures == Integer.MAX_VALUE ? this.failures : this.failures + 1);
    }

    /** A load may start: somebody waits, nothing else is running, and the stats are not loaded yet. */
    boolean canLoad() {
        return this.base == null && this.waiting != null && !this.loading && this.inflight == null;
    }

    PlayerRecord startLoad() {
        if (!canLoad()) {
            throw new IllegalStateException("Cannot start a load now");
        }
        return new PlayerRecord(null, null, this.pending, true, this.waiting, this.online, this.lastAccess, this.failures);
    }

    /** The load finished with the stored values; the waiters are completed by the store. */
    PlayerRecord loaded(StatsSnapshot stored) {
        return new PlayerRecord(stored, this.inflight, this.pending, false, null, this.online, this.lastAccess, this.failures);
    }

    PlayerRecord loadFailed() {
        return new PlayerRecord(this.base, this.inflight, this.pending, false, null, this.online, this.lastAccess, this.failures);
    }

    /**
     * Takes the pending changes out to be written without tracking them (shutdown, see {@link StatsStore#close}).
     * They count as stored from now on, so the values in memory stay what they were; a write still in flight is
     * folded in the same way and its late result is ignored.
     */
    PlayerRecord drained() {
        return new PlayerRecord(current(), null, StatsDelta.NONE, this.loading, this.waiting, this.online, this.lastAccess,
            this.failures);
    }

    /** Current stats including everything not stored yet, or null while not loaded. */
    StatsSnapshot current() {
        if (this.base == null) {
            return null;
        }
        StatsSnapshot confirmedOrWriting = this.inflight == null ? this.base : this.inflight.applyTo(this.base);
        return this.pending.applyTo(confirmedOrWriting);
    }

    /** Nothing left to do and nobody needs it: offline, idle, and either never loaded or not used for a while. */
    boolean evictable(long now, long keepMillis) {
        return !this.online && this.inflight == null && !this.loading && this.waiting == null && this.pending.isEmpty()
            && (this.base == null || now - this.lastAccess >= keepMillis);
    }
}
