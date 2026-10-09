package net.siftvanilla.siftcore.economy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import org.bukkit.entity.Entity;

/**
 * Hands something to a player on their own thread after it already left its source (a committed transaction), and
 * makes sure it never just disappears. Every hand-off is tracked until exactly one of two things happens: it is
 * delivered on the player's thread, or its fallback gets it (the player was removed first, the scheduler refused the
 * task because the player is already gone or the plugin is stopping, or shutdown {@linkplain #drain() drained} it).
 * <p>
 * On Canvas an entity's scheduler returns no task once the entity is retired, and then runs neither the task nor its
 * retired callback; while the plugin is being disabled it throws instead. Both cases go to the fallback here. Work that
 * will start a hand-off once storage answers can be counted with {@link #begin()} / {@link #end()}, so shutdown can
 * wait for those answers before it drains.
 *
 * @param <T> what is handed over
 */
public final class Handoffs<T> {

    /** Schedules a task on the receiving player's thread: {@code run} there, or {@code retired} if they leave first. */
    @FunctionalInterface
    public interface Scheduling {
        Task schedule(Runnable run, Runnable retired);
    }

    private record Pending<T>(T payload, Consumer<T> fallback) {
    }

    private final Map<Long, Pending<T>> pending = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();
    private final AtomicInteger waiting = new AtomicInteger();
    private volatile boolean closed;

    /** Hands {@code payload} to {@code entity} on its thread; see {@link #hand(Object, Scheduling, Consumer, Consumer)}. */
    public void hand(Scheduler scheduler, Entity entity, T payload, Consumer<T> deliver, Consumer<T> fallback) {
        hand(payload, (run, retired) -> scheduler.entity(entity, run, retired), deliver, fallback);
    }

    /**
     * Hands {@code payload} over: {@code deliver} receives it on the receiver's thread, or, if that can't happen,
     * {@code fallback} receives it (on whatever thread found out). Exactly one of the two runs, exactly once.
     *
     * @return the hand-off's token
     */
    public long hand(T payload, Scheduling scheduling, Consumer<T> deliver, Consumer<T> fallback) {
        long token = this.tokens.incrementAndGet();
        this.pending.put(token, new Pending<>(payload, fallback));
        Runnable retired = () -> {
            Pending<T> entry = this.pending.remove(token);
            if (entry != null) {
                entry.fallback().accept(entry.payload());
            }
        };
        Task task;
        try {
            task = scheduling.schedule(() -> {
                Pending<T> entry = this.pending.remove(token);
                if (entry != null) {
                    deliver.accept(entry.payload());
                }
            }, retired);
        } catch (RuntimeException e) {
            // The scheduler refuses new work while the plugin stops.
            task = Task.NONE;
        }
        if (task == null || task == Task.NONE) {
            retired.run();
        }
        return token;
    }

    /**
     * Runs {@code task} on the entity's thread, or {@code instead} when it can't (the entity is retired before or
     * already gone, or the scheduler refuses the task). Exactly one of the two runs, at most once. Nothing is tracked
     * for shutdown: use it when {@code instead} is safe to skip at a stop (or what it does is covered otherwise).
     */
    public static void onEntity(Scheduler scheduler, Entity entity, Runnable task, Runnable instead) {
        once((run, retired) -> scheduler.entity(entity, run, retired), task, instead);
    }

    /** {@link #onEntity} with any scheduling: exactly one of {@code task} and {@code instead} runs, at most once. */
    public static void once(Scheduling scheduling, Runnable task, Runnable instead) {
        AtomicBoolean done = new AtomicBoolean();
        Runnable fallback = () -> {
            if (done.compareAndSet(false, true)) {
                instead.run();
            }
        };
        Task scheduled;
        try {
            scheduled = scheduling.schedule(() -> {
                if (done.compareAndSet(false, true)) {
                    task.run();
                }
            }, fallback);
        } catch (RuntimeException e) {
            scheduled = Task.NONE;
        }
        if (scheduled == null || scheduled == Task.NONE) {
            fallback.run();
        }
    }

    /** Hand-offs that have not reached their receiver or fallback yet. */
    public int pending() {
        return this.pending.size();
    }

    /** Marks work that will start a hand-off once storage answers; pair every call with exactly one {@link #end()}. */
    public void begin() {
        this.waiting.incrementAndGet();
    }

    /**
     * {@link #begin()}, unless shutdown {@linkplain #close() closed} these hand-offs: then nothing is counted and this
     * returns false, and the caller leaves what it would have handed off where it is stored. The work is counted before
     * the check, so whatever runs while shutdown closes either sees the close here or is waited for by
     * {@link #awaitIdle}: nothing can start after shutdown stopped waiting.
     */
    public boolean beginUnlessClosed() {
        begin();
        if (this.closed) {
            end();
            return false;
        }
        return true;
    }

    /** Refuses new work from {@link #beginUnlessClosed()}. Called on shutdown, before {@link #awaitIdle}. */
    public void close() {
        this.closed = true;
    }

    public void end() {
        if (this.waiting.decrementAndGet() < 0) {
            this.waiting.incrementAndGet();
            throw new IllegalStateException("end() without begin()");
        }
    }

    /** Work started with {@link #begin()} that has not ended yet. */
    public int waiting() {
        return this.waiting.get();
    }

    /**
     * Waits until no work started with {@link #begin()} is waiting any more, at most {@code timeout}. Called on shutdown
     * after the database flushed, when the remaining answers are only callbacks that are about to run.
     */
    public boolean awaitIdle(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (this.waiting.get() > 0) {
            if (System.nanoTime() - end >= 0) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return this.waiting.get() <= 0;
            }
        }
        return true;
    }

    /** Gives every hand-off that has not run yet to its fallback. Called on shutdown, before storage closes. */
    public void drain() {
        for (Long token : List.copyOf(this.pending.keySet())) {
            Pending<T> entry = this.pending.remove(token);
            if (entry != null) {
                entry.fallback().accept(entry.payload());
            }
        }
    }
}
