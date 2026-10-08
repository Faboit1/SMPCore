package net.siftvanilla.siftcore.feature.displays;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Positions set in-game: the {@code displays} table mirrored in memory. Memory changes only after the database
 * committed, so a failed write never leaves a display somewhere it is not stored. One change per display at a time:
 * a second change while the first is being saved is refused, which makes double submits harmless.
 */
final class Placements {

    /** How a change ended. */
    enum Outcome {
        DONE,
        /** Another change of the same display is still being saved. */
        BUSY,
        /** The name is already taken (create). */
        TAKEN,
        /** There was no row (delete). */
        MISSING,
        /** The database refused the write; nothing changed. */
        FAILED
    }

    private final PlacementStore store;
    private final Logger logger;
    private final Runnable changed;
    private final Map<String, Placement> rows = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    /** {@code changed} runs after every committed change (on a database callback thread). */
    Placements(PlacementStore store, Logger logger, Runnable changed) {
        this.store = store;
        this.logger = logger;
        this.changed = changed;
    }

    /** Loads every row; blocking, startup only. */
    void load() throws Exception {
        this.rows.putAll(this.store.loadAll().get());
    }

    Placement get(String id) {
        return this.rows.get(id);
    }

    Map<String, Placement> snapshot() {
        return Map.copyOf(this.rows);
    }

    /** Stores a new display; {@link Outcome#TAKEN} when a row with that name already exists. */
    CompletableFuture<Outcome> create(Placement placement) {
        return guarded(placement.id(), () -> this.store.insert(placement).thenApply(inserted -> {
            if (!inserted) {
                return Outcome.TAKEN;
            }
            this.rows.put(placement.id(), placement);
            return Outcome.DONE;
        }));
    }

    /** Stores a position, replacing an existing row of that display. */
    CompletableFuture<Outcome> save(Placement placement) {
        return guarded(placement.id(), () -> this.store.save(placement).thenApply(ignored -> {
            this.rows.put(placement.id(), placement);
            return Outcome.DONE;
        }));
    }

    /** Deletes a display's row; {@link Outcome#MISSING} when there was none. */
    CompletableFuture<Outcome> delete(String id) {
        return guarded(id, () -> this.store.delete(id).thenApply(deleted -> {
            this.rows.remove(id);
            return deleted ? Outcome.DONE : Outcome.MISSING;
        }));
    }

    private CompletableFuture<Outcome> guarded(String id, Supplier<CompletableFuture<Outcome>> write) {
        if (!this.busy.add(id)) {
            return CompletableFuture.completedFuture(Outcome.BUSY);
        }
        CompletableFuture<Outcome> started;
        try {
            started = write.get();
        } catch (RuntimeException e) {
            this.busy.remove(id);
            this.logger.log(Level.WARNING, "Saving display " + id + " failed", e);
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        return started.handle((outcome, error) -> {
            try {
                if (error != null) {
                    this.logger.log(Level.WARNING, "Saving display " + id + " failed", error);
                    return Outcome.FAILED;
                }
                if (outcome == Outcome.DONE || outcome == Outcome.MISSING) {
                    try {
                        this.changed.run();
                    } catch (RuntimeException e) {
                        this.logger.log(Level.SEVERE, "Displays could not be updated after saving " + id, e);
                    }
                }
                return outcome;
            } finally {
                this.busy.remove(id);
            }
        });
    }
}
