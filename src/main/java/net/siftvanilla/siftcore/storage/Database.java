package net.siftvanilla.siftcore.storage;

import java.util.concurrent.CompletableFuture;

/**
 * SiftCore's database. Writes are applied by one writer thread, strictly in submission order, each inside its own
 * savepoint and committed in small groups; a write's future completes only after its group is durably committed.
 * Reads run on a separate pool and never block writes. Nothing here may be called from a world thread and then
 * waited on with {@code join()}; compose the futures instead.
 */
public interface Database {

    Dialect dialect();

    /** Queues a write. The future completes after commit with the work's result, or exceptionally on failure. */
    <T> CompletableFuture<T> write(SqlWork<T> work);

    /** Runs a read-only query on the read pool. */
    <T> CompletableFuture<T> read(SqlWork<T> work);

    /** Writes queued but not yet committed. */
    int pendingWrites();

    /** Total writes committed since start. */
    long committedWrites();

    /** Blocks until every write queued so far is committed (used on shutdown and by tests). */
    void flush();

    /** Stops accepting work, flushes and closes all connections. */
    void close();
}
