package net.siftvanilla.siftcore.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link Database} over JDBC with a single ordered writer thread and group commit.
 * <p>
 * Each queued write runs in its own savepoint, so a failing unit rolls back alone; the group is then committed
 * once. Futures are completed on a separate callback thread after the commit, so callers' continuations never run
 * on (or stall) the writer. If a commit itself fails, every unit of the group is retried alone in its own
 * transaction so one bad unit cannot sink its neighbours.
 */
public final class JdbcDatabase implements Database {

    private static final int MAX_GROUP = 128;

    private record Job<T>(SqlWork<T> work, CompletableFuture<T> future) {
    }

    private final ConnectionSource source;
    private final Logger logger;
    private final BlockingQueue<Job<?>> queue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private final ExecutorService readers;
    private final ExecutorService callbacks;
    private final ThreadLocal<Connection> pinnedReader = new ThreadLocal<>();
    private final List<Connection> pinnedReaders = new ArrayList<>();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong totalCommitNanos = new AtomicLong();
    private final AtomicLong groups = new AtomicLong();
    private volatile boolean accepting = true;
    private volatile boolean running = true;
    private Connection writerConnection;

    public JdbcDatabase(ConnectionSource source, Logger logger) throws SQLException {
        this.source = source;
        this.logger = logger;
        this.writerConnection = source.openWriter();
        this.writerConnection.setAutoCommit(false);
        this.writer = new Thread(this::writerLoop, "SiftCore-DB-Writer");
        this.writer.setDaemon(false);
        this.readers = Executors.newFixedThreadPool(source.readerThreads(), named("SiftCore-DB-Reader"));
        this.callbacks = Executors.newFixedThreadPool(2, named("SiftCore-DB-Callback"));
        this.writer.start();
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public Dialect dialect() {
        return this.source.dialect();
    }

    @Override
    public <T> CompletableFuture<T> write(SqlWork<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!this.accepting) {
            future.completeExceptionally(new RejectedExecutionException("The database is shutting down"));
            return future;
        }
        this.queue.add(new Job<>(work, future));
        return future;
    }

    @Override
    public <T> CompletableFuture<T> read(SqlWork<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            this.readers.execute(() -> {
                try {
                    T result = runRead(work);
                    this.callbacks.execute(() -> future.complete(result));
                } catch (Throwable t) {
                    this.callbacks.execute(() -> future.completeExceptionally(t));
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private <T> T runRead(SqlWork<T> work) throws SQLException {
        if (this.source.pinReaders()) {
            Connection connection = this.pinnedReader.get();
            if (connection == null || connection.isClosed()) {
                connection = this.source.openReader();
                this.pinnedReader.set(connection);
                synchronized (this.pinnedReaders) {
                    this.pinnedReaders.add(connection);
                }
            }
            return work.run(connection);
        }
        try (Connection connection = this.source.openReader()) {
            return work.run(connection);
        }
    }

    @Override
    public int pendingWrites() {
        return this.queue.size();
    }

    @Override
    public long committedWrites() {
        return this.committed.get();
    }

    public long failedWrites() {
        return this.failed.get();
    }

    /** Average commit time per group in microseconds. */
    public long averageGroupMicros() {
        long count = this.groups.get();
        return count == 0 ? 0 : this.totalCommitNanos.get() / count / 1_000;
    }

    private void writerLoop() {
        List<Job<?>> group = new ArrayList<>(MAX_GROUP);
        while (this.running || !this.queue.isEmpty()) {
            try {
                Job<?> first = this.queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                group.add(first);
                this.queue.drainTo(group, MAX_GROUP - 1);
                runGroup(group);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                this.logger.log(Level.SEVERE, "The database writer hit an unexpected error", t);
            } finally {
                group.clear();
            }
        }
    }

    private void runGroup(List<Job<?>> group) {
        long start = System.nanoTime();
        Object[] results = new Object[group.size()];
        Throwable[] errors = new Throwable[group.size()];
        try {
            Connection connection = writer();
            for (int i = 0; i < group.size(); i++) {
                Savepoint savepoint = connection.setSavepoint();
                try {
                    results[i] = group.get(i).work().run(connection);
                    connection.releaseSavepoint(savepoint);
                } catch (Throwable t) {
                    errors[i] = t;
                    connection.rollback(savepoint);
                }
            }
            connection.commit();
        } catch (SQLException commitFailure) {
            this.logger.log(Level.WARNING, "A database group commit failed; retrying each write on its own", commitFailure);
            rollbackQuietly();
            retryIndividually(group);
            return;
        }
        this.totalCommitNanos.addAndGet(System.nanoTime() - start);
        this.groups.incrementAndGet();
        for (int i = 0; i < group.size(); i++) {
            complete(group.get(i), results[i], errors[i]);
        }
    }

    private void retryIndividually(List<Job<?>> group) {
        for (Job<?> job : group) {
            Object result = null;
            Throwable error = null;
            try {
                Connection connection = writer();
                result = job.work().run(connection);
                connection.commit();
            } catch (Throwable t) {
                error = t;
                rollbackQuietly();
            }
            complete(job, result, error);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void complete(Job<T> job, Object result, Throwable error) {
        if (error == null) {
            this.committed.incrementAndGet();
        } else {
            this.failed.incrementAndGet();
        }
        Runnable completion = error == null
            ? () -> job.future().complete((T) result)
            : () -> job.future().completeExceptionally(error);
        try {
            this.callbacks.execute(completion);
        } catch (RejectedExecutionException e) {
            completion.run();
        }
    }

    private Connection writer() throws SQLException {
        if (this.writerConnection == null || this.writerConnection.isClosed() || !this.writerConnection.isValid(2)) {
            closeQuietly(this.writerConnection);
            this.writerConnection = this.source.openWriter();
            this.writerConnection.setAutoCommit(false);
        }
        return this.writerConnection;
    }

    private void rollbackQuietly() {
        try {
            if (this.writerConnection != null && !this.writerConnection.isClosed()) {
                this.writerConnection.rollback();
            }
        } catch (SQLException ignored) {
            closeQuietly(this.writerConnection);
            this.writerConnection = null;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Already broken.
            }
        }
    }

    @Override
    public void flush() {
        if (Thread.currentThread() == this.writer) {
            throw new IllegalStateException("flush() called from the writer thread");
        }
        CompletableFuture<Void> marker = new CompletableFuture<>();
        this.queue.add(new Job<>(c -> null, marker));
        try {
            marker.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            this.logger.log(Level.WARNING, "Timed out waiting for the database to flush", e);
        }
    }

    @Override
    public void close() {
        this.accepting = false;
        flush();
        this.running = false;
        try {
            this.writer.join(TimeUnit.SECONDS.toMillis(60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.readers.shutdown();
        try {
            if (!this.readers.awaitTermination(10, TimeUnit.SECONDS)) {
                this.readers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.callbacks.shutdown();
        try {
            this.callbacks.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this.pinnedReaders) {
            this.pinnedReaders.forEach(JdbcDatabase::closeQuietly);
            this.pinnedReaders.clear();
        }
        closeQuietly(this.writerConnection);
        this.source.close();
    }
}
