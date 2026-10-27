package net.siftvanilla.siftcore.feature.spawners;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.economy.Ledger;

/**
 * Writes loot to the database behind the loot cycles: every spawner whose storage changed since it was last written
 * gets its complete snapshot (XP and item rows) written, on a timer, when its chunk unloads and at shutdown. The
 * snapshots are taken and queued while holding the economy lock, so they reach the ordered database writer in the
 * same order as the transactions that change storages: a write never overtakes a newer one. A crash loses at most
 * the loot since the last write, never duplicates it. A failed write marks the spawners for the next one.
 */
final class WriteBehind {

    private final Ledger ledger;
    private final SpawnerStore store;
    private final Logger logger;
    private final AtomicLong written = new AtomicLong();
    private volatile long lastFlush;

    WriteBehind(Ledger ledger, SpawnerStore store, Logger logger) {
        this.ledger = ledger;
        this.store = store;
        this.logger = logger;
    }

    /** Queues the changed spawners among {@code spawners}; returns how many were queued. */
    int flush(Collection<ManagedSpawner> spawners) {
        this.lastFlush = System.currentTimeMillis();
        return this.ledger.locked(() -> {
            List<SpawnerStore.Snapshot> batch = new ArrayList<>();
            List<ManagedSpawner> included = new ArrayList<>();
            for (ManagedSpawner spawner : spawners) {
                if (spawner.dirty() && !spawner.removed()) {
                    spawner.dirty(false);
                    batch.add(new SpawnerStore.Snapshot(spawner.id, spawner.xp(), spawner.storage.snapshot()));
                    included.add(spawner);
                }
            }
            if (batch.isEmpty()) {
                return 0;
            }
            this.store.write(SpawnerStore.persist(batch)).whenComplete((ignored, error) -> {
                if (error == null) {
                    this.written.addAndGet(batch.size());
                    return;
                }
                this.logger.log(Level.WARNING, "Writing the loot of " + batch.size() + " spawners failed; it will be written again", error);
                this.ledger.locked(() -> {
                    for (ManagedSpawner spawner : included) {
                        if (!spawner.removed()) {
                            spawner.dirty(true);
                        }
                    }
                    return null;
                });
            });
            return batch.size();
        });
    }

    /** Spawners with loot not written yet. */
    int pending(Collection<ManagedSpawner> spawners) {
        return this.ledger.locked(() -> {
            int count = 0;
            for (ManagedSpawner spawner : spawners) {
                if (spawner.dirty() && !spawner.removed()) {
                    count++;
                }
            }
            return count;
        });
    }

    long written() {
        return this.written.get();
    }

    long lastFlush() {
        return this.lastFlush;
    }
}
