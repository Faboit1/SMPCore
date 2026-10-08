package net.siftvanilla.siftcore.feature.stats;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.feature.stats.StatsStorage.PendingWrite;

/**
 * Every player's stats, kept in memory and written behind.
 * <p>
 * Events only touch memory: each change is composed into the player's pending {@link StatsDelta}. A save moves the
 * pending changes in flight and writes them as atomic increments, so players who are offline (money earned from an
 * auction while away) and rows that were never loaded are updated correctly without reading them first. A change is
 * always in exactly one place (pending, in flight, or confirmed), so nothing is counted twice across saves and a
 * failed save is retried with the next one. Per player only one database operation runs at a time, which keeps
 * writes in event order and stops a load from racing a write.
 * <p>
 * Online players are loaded before they enter the world and stay in memory; offline players are loaded on demand
 * (someone views their stats) and dropped again after a while. Thread-safe; nothing here blocks except
 * {@link #close(Duration)}.
 */
public final class StatsStore implements StatsRecorder {

    private final StatsStorage storage;
    private final Logger logger;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, PlayerRecord> records = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> writing = ConcurrentHashMap.newKeySet();
    private final Object closeLock = new Object();
    private final AtomicLong storedWrites = new AtomicLong();
    private final AtomicLong failedSaves = new AtomicLong();
    private volatile long keepOfflineMillis;
    private volatile boolean closed;

    public StatsStore(StatsStorage storage, Logger logger, LongSupplier clock, Duration keepOffline) {
        this.storage = storage;
        this.logger = logger;
        this.clock = clock;
        keepOffline(keepOffline);
    }

    /** How long stats of an offline player stay in memory after they were last used. */
    public void keepOffline(Duration keep) {
        this.keepOfflineMillis = Math.max(0, keep.toMillis());
    }

    // ------------------------------------------------------------------ StatsRecorder

    @Override
    public void add(UUID player, Stat stat, long amount) {
        if (player == null || stat == null || amount <= 0) {
            return;
        }
        record(player, StatsDelta.add(Counter.of(stat), amount));
    }

    @Override
    public void kill(UUID killer, UUID victim) {
        if (victim == null) {
            return;
        }
        if (killer != null && !killer.equals(victim)) {
            record(killer, StatsDelta.kill());
        }
        record(victim, StatsDelta.death());
    }

    @Override
    public void death(UUID victim) {
        if (victim != null) {
            record(victim, StatsDelta.death());
        }
    }

    /** The current value; exact for loaded players (online, or recently viewed), 0 for players not in memory. */
    @Override
    public long get(UUID player, Stat stat) {
        StatsSnapshot current = current(player);
        return current == null ? 0 : current.get(Counter.of(stat));
    }

    @Override
    public int streak(UUID player) {
        StatsSnapshot current = current(player);
        return current == null ? 0 : (int) Math.min(Integer.MAX_VALUE, current.streak());
    }

    @Override
    public int bestStreak(UUID player) {
        StatsSnapshot current = current(player);
        return current == null ? 0 : (int) Math.min(Integer.MAX_VALUE, current.bestStreak());
    }

    // ------------------------------------------------------------------ changes

    /** Records a change for a player. Any thread; memory only. */
    public void record(UUID player, StatsDelta delta) {
        if (delta.isEmpty()) {
            return;
        }
        long now = this.clock.getAsLong();
        this.records.compute(player, (key, record) -> (record == null ? PlayerRecord.fresh(now) : record).add(delta));
        if (this.closed) {
            drain(player);
        }
    }

    /** A staff correction: sets a counter and saves right away. */
    public CompletableFuture<Void> set(UUID player, Counter counter, long value) {
        record(player, StatsDelta.set(counter, value));
        return save(player);
    }

    /** A staff correction: adds to a counter and saves right away. */
    public CompletableFuture<Void> give(UUID player, Counter counter, long amount) {
        record(player, StatsDelta.add(counter, amount));
        return save(player);
    }

    /** A staff reset: every stat back to zero, saved right away. */
    public CompletableFuture<Void> reset(UUID player) {
        record(player, StatsDelta.reset());
        return save(player);
    }

    /** Current stats including unsaved changes, or null when the player is not loaded. */
    public StatsSnapshot current(UUID player) {
        PlayerRecord record = player == null ? null : this.records.get(player);
        return record == null ? null : record.current();
    }

    public boolean loaded(UUID player) {
        PlayerRecord record = this.records.get(player);
        return record != null && record.loaded();
    }

    // ------------------------------------------------------------------ loading

    /**
     * Loads a player's stats if they are not in memory yet and completes with their current values. Concurrent
     * calls share one load; a load waits for a write of the same player that is still running.
     */
    public CompletableFuture<StatsSnapshot> load(UUID player) {
        long now = this.clock.getAsLong();
        PlayerRecord record = this.records.compute(player, (key, existing) -> {
            PlayerRecord next = (existing == null ? PlayerRecord.fresh(now) : existing).touch(now);
            return next.loaded() || next.waiting() != null ? next : next.waiting(new CompletableFuture<>());
        });
        if (record.loaded()) {
            return CompletableFuture.completedFuture(record.current());
        }
        CompletableFuture<StatsSnapshot> waiting = record.waiting();
        startLoad(player);
        return waiting;
    }

    private void startLoad(UUID player) {
        AtomicReference<PlayerRecord> started = new AtomicReference<>();
        this.records.computeIfPresent(player, (key, record) -> {
            if (!record.canLoad()) {
                return record;
            }
            PlayerRecord next = record.startLoad();
            started.set(next);
            return next;
        });
        if (started.get() == null) {
            return;
        }
        CompletableFuture<StatsSnapshot> loading;
        try {
            loading = this.storage.load(player);
        } catch (RuntimeException e) {
            loading = CompletableFuture.failedFuture(e);
        }
        loading.whenComplete((stored, error) -> loaded(player, stored, error));
    }

    private void loaded(UUID player, StatsSnapshot stored, Throwable error) {
        AtomicReference<CompletableFuture<StatsSnapshot>> waiting = new AtomicReference<>();
        PlayerRecord after = this.records.computeIfPresent(player, (key, record) -> {
            waiting.set(record.waiting());
            return error == null ? record.loaded(stored == null ? StatsSnapshot.ZERO : stored) : record.loadFailed();
        });
        CompletableFuture<StatsSnapshot> future = waiting.get();
        if (error != null) {
            this.logger.log(Level.WARNING, "Could not load the stats of " + player
                + " (an online player is retried with the next save; changes meanwhile are kept and stored)", error);
            if (future != null) {
                future.completeExceptionally(error);
            }
            return;
        }
        if (future != null) {
            future.complete(after == null ? stored : after.current());
        }
    }

    // ------------------------------------------------------------------ players

    /** A player entered the world: keep them in memory and make sure they are loaded. */
    public void join(UUID player) {
        long now = this.clock.getAsLong();
        PlayerRecord record = this.records.compute(player,
            (key, existing) -> (existing == null ? PlayerRecord.fresh(now) : existing).online(true, now));
        if (!record.loaded()) {
            load(player);
        }
    }

    /** A player left: save what they have pending. Their stats stay cached for a while in case they come back. */
    public void quit(UUID player) {
        long now = this.clock.getAsLong();
        this.records.computeIfPresent(player, (key, record) -> record.online(false, now));
        save(player);
    }

    // ------------------------------------------------------------------ saving

    /**
     * Saves every player's pending changes. Completes when stored; fails if the save failed (it is retried). After
     * {@link #close} this does nothing: the final save already took everything and later changes are written as
     * they happen.
     */
    public CompletableFuture<Void> save() {
        synchronized (this.closeLock) {
            if (this.closed) {
                return CompletableFuture.completedFuture(null);
            }
            List<PendingWrite> batch = new ArrayList<>();
            for (UUID player : this.records.keySet()) {
                take(player, batch);
            }
            return submit(batch);
        }
    }

    /** Saves one player's pending changes. */
    public CompletableFuture<Void> save(UUID player) {
        synchronized (this.closeLock) {
            if (this.closed) {
                return CompletableFuture.completedFuture(null);
            }
            List<PendingWrite> batch = new ArrayList<>(1);
            take(player, batch);
            return submit(batch);
        }
    }

    private void take(UUID player, List<PendingWrite> batch) {
        this.records.computeIfPresent(player, (key, record) -> {
            if (!record.canFlush()) {
                return record;
            }
            batch.add(new PendingWrite(key, record.pending()));
            return record.startFlush();
        });
    }

    private CompletableFuture<Void> submit(List<PendingWrite> batch) {
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<PendingWrite> writes = List.copyOf(batch);
        CompletableFuture<Map<UUID, String>> stored;
        try {
            stored = this.storage.write(writes);
        } catch (RuntimeException e) {
            stored = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Void> done = stored.handle((refused, error) -> finish(writes, refused, error))
            .thenCompose(failure -> failure == null ? CompletableFuture.<Void>completedFuture(null) : CompletableFuture.<Void>failedFuture(failure));
        this.writing.add(done);
        done.whenComplete((ignored, error) -> this.writing.remove(done));
        return done;
    }

    /**
     * Settles a finished write: stored changes become confirmed, refused or failed ones go back in front of the
     * player's pending changes for the next save. Returns why something was not stored, or null when all of it was.
     */
    private Throwable finish(List<PendingWrite> writes, Map<UUID, String> refused, Throwable error) {
        Map<UUID, String> failed = new HashMap<>();
        if (error != null) {
            String reason = String.valueOf(error.getCause() == null ? error : error.getCause());
            for (PendingWrite write : writes) {
                failed.put(write.player(), reason);
            }
        } else if (refused != null) {
            failed.putAll(refused);
        }
        this.storedWrites.addAndGet(writes.size() - failed.size());
        if (!failed.isEmpty()) {
            this.failedSaves.incrementAndGet();
        }
        long now = this.clock.getAsLong();
        long keep = this.keepOfflineMillis;
        int recovered = 0;
        List<UUID> report = new ArrayList<>();
        int worst = 0;
        for (PendingWrite write : writes) {
            boolean stored = !failed.containsKey(write.player());
            int[] failuresBefore = {0};
            PlayerRecord after = this.records.computeIfPresent(write.player(), (key, record) -> {
                failuresBefore[0] = record.failures();
                PlayerRecord next = record.flushed(stored);
                return next.evictable(now, keep) ? null : next;
            });
            if (stored && failuresBefore[0] > 0) {
                recovered++;
            }
            if (!stored && after != null && (after.failures() == 1 || after.failures() % 10 == 0)) {
                report.add(write.player());
                worst = Math.max(worst, after.failures());
            }
            if (after != null && after.canLoad()) {
                startLoad(write.player());
            }
        }
        if (recovered > 0) {
            this.logger.info("The stats of " + recovered + " player(s) are being saved again; nothing was lost.");
        }
        if (!report.isEmpty()) {
            UUID first = report.getFirst();
            this.logger.log(Level.WARNING, "Could not save the stats of " + report.size() + " player(s) (" + worst
                + " attempt(s) in a row); they stay in memory and are retried with the next save. " + first + ": "
                + failed.get(first));
        }
        if (failed.isEmpty()) {
            return null;
        }
        return error != null ? error : new SQLException("The stats of " + failed.size() + " player(s) were not stored: "
            + failed.values().iterator().next());
    }

    // ------------------------------------------------------------------ housekeeping

    /** Drops offline players nobody needs any more; returns how many were dropped. */
    public int sweep() {
        long now = this.clock.getAsLong();
        long keep = this.keepOfflineMillis;
        int dropped = 0;
        for (UUID player : this.records.keySet()) {
            boolean[] removed = {false};
            this.records.computeIfPresent(player, (key, record) -> {
                if (record.evictable(now, keep)) {
                    removed[0] = true;
                    return null;
                }
                return record;
            });
            if (removed[0]) {
                dropped++;
            }
        }
        return dropped;
    }

    /** Loads online players whose earlier load failed. */
    public void retryLoads() {
        for (var entry : this.records.entrySet()) {
            PlayerRecord record = entry.getValue();
            if (record.online() && !record.loaded() && !record.loading() && record.waiting() == null) {
                load(entry.getKey());
            }
        }
    }

    /** The online players (of {@code online}) whose stats are not loaded. */
    public List<UUID> notLoaded(Collection<UUID> online) {
        List<UUID> missing = new ArrayList<>();
        for (UUID player : online) {
            if (!loaded(player)) {
                missing.add(player);
            }
        }
        return missing;
    }

    /** Players held in memory. */
    public int size() {
        return this.records.size();
    }

    /** Players with changes that are not stored yet. */
    public int unsaved() {
        int count = 0;
        for (PlayerRecord record : this.records.values()) {
            if (!record.pending().isEmpty() || record.inflight() != null) {
                count++;
            }
        }
        return count;
    }

    /** Player writes stored since start. */
    public long storedWrites() {
        return this.storedWrites.get();
    }

    /** Writes since start that failed for at least one player. */
    public long failedSaves() {
        return this.failedSaves.get();
    }

    /** Players whose last write failed; their changes stay in memory and are retried with every save. */
    public int failing() {
        int count = 0;
        for (PlayerRecord record : this.records.values()) {
            if (record.failures() > 0) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Stops write-behind and stores everything synchronously: waits for running saves, then writes every pending
     * change in one batch and waits for it. Changes recorded afterwards (late events while the server stops) are
     * written straight away. Never throws.
     */
    public void close(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<?>[] running;
        synchronized (this.closeLock) {
            this.closed = true;
            // Saves start only under this lock and only while open, so every write still running is in this set.
            running = this.writing.toArray(new CompletableFuture<?>[0]);
        }
        if (running.length > 0) {
            // A failed save puts its changes back in pending, so the batch below retries them.
            await(CompletableFuture.allOf(running).handle((ignored, error) -> Map.<UUID, String>of()), deadline, "running stats saves");
        }
        List<PendingWrite> batch = new ArrayList<>();
        CompletableFuture<Map<UUID, String>> stored;
        synchronized (this.closeLock) {
            for (UUID player : this.records.keySet()) {
                this.records.computeIfPresent(player, (key, record) -> {
                    if (record.pending().isEmpty()) {
                        return record;
                    }
                    batch.add(new PendingWrite(key, record.pending()));
                    return record.drained();
                });
            }
            if (batch.isEmpty()) {
                return;
            }
            stored = writeQuietly(List.copyOf(batch));
        }
        Map<UUID, String> refused = await(stored, deadline, "the final stats save");
        if (refused == null) {
            this.logger.severe("The stats changes of " + batch.size() + " player(s) since the last save could not be stored at shutdown.");
            return;
        }
        this.storedWrites.addAndGet(batch.size() - refused.size());
        if (!refused.isEmpty()) {
            UUID first = refused.keySet().iterator().next();
            this.logger.severe("The stats changes of " + refused.size() + " player(s) since the last save could not be stored at shutdown. "
                + first + ": " + refused.get(first));
        }
    }

    private void drain(UUID player) {
        synchronized (this.closeLock) {
            AtomicReference<PendingWrite> taken = new AtomicReference<>();
            this.records.computeIfPresent(player, (key, record) -> {
                if (record.pending().isEmpty()) {
                    return record;
                }
                taken.set(new PendingWrite(key, record.pending()));
                return record.drained();
            });
            PendingWrite write = taken.get();
            if (write != null) {
                writeQuietly(List.of(write)).whenComplete((refused, error) -> {
                    if (error != null || !refused.isEmpty()) {
                        this.logger.warning("A late stats change of " + write.player() + " could not be stored: "
                            + (error != null ? error : refused.get(write.player())));
                    }
                });
            }
        }
    }

    private CompletableFuture<Map<UUID, String>> writeQuietly(List<PendingWrite> writes) {
        try {
            return this.storage.write(writes);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Waits until the deadline; returns the result, or null (after logging why) when it failed or took too long. */
    private <T> T await(CompletableFuture<T> future, long deadline, String what) {
        try {
            return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            this.logger.warning("Timed out waiting for " + what + ".");
        } catch (ExecutionException e) {
            this.logger.log(Level.WARNING, "Failed while waiting for " + what, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
