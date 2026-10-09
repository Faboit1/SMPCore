package net.siftvanilla.siftcore.feature.settings;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs steps that follow database reads one after another, in the order they were asked for.
 * <p>
 * The database completes its futures on a pool of callback threads, so the continuations of two reads queued one after
 * the other (the values before two changes of a player who is not online) may run in either order, and the writes
 * they queue (the change itself, its audit row) with them. A step given here runs only after every step given before
 * it has run, whatever thread completes its read, so what the steps queue lands in the order of the calls.
 * <p>
 * The read is started lazily, once every earlier step ran: a step that reads what an earlier one changes (a staff
 * change after another one for the same player who is not online, or a reset after a change) sees that change,
 * because the earlier step queued its write before this read was queued. A caller that already queued its change
 * right after its read passes that read as it is ({@code () -> read}). Steps must be short and must not block: a slow
 * one holds the later ones back. They run on a database callback thread, or on the calling thread when nothing is
 * pending, and never under a lock of this class.
 */
final class InOrder {

    private final Logger logger;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    InOrder(Logger logger) {
        this.logger = logger;
    }

    /**
     * Starts {@code read} once every earlier step ran, then runs {@code step} with its value (or its failure, unwrapped;
     * a read that throws counts as failed). A step that throws is logged and does not stop later ones.
     */
    <T> void then(Supplier<CompletableFuture<T>> read, BiConsumer<? super T, ? super Throwable> step) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Void> before;
        synchronized (this) {
            before = this.tail;
            this.tail = done;
        }
        before.thenCompose(ignored -> read.get()).whenComplete((value, error) -> {
            try {
                step.accept(error == null ? value : null, unwrap(error));
            } catch (RuntimeException e) {
                this.logger.log(Level.WARNING, "A settings step after a database read failed", e);
            } finally {
                done.complete(null);
            }
        });
    }

    /** Completes once every step given so far has run (it never fails). */
    CompletableFuture<Void> idle() {
        synchronized (this) {
            return this.tail;
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
