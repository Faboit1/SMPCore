package net.siftvanilla.siftcore.core.scheduler;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * The only way SiftCore runs code later or elsewhere. It is region-aware so the same code is correct on
 * Paper (one main thread) and on Folia/Canvas (one thread per region):
 * <ul>
 *   <li>global: world-independent state such as the world border, game rules and timers that touch no entity;</li>
 *   <li>region: anything touching blocks or chunks at a location;</li>
 *   <li>entity: anything touching a player or entity (inventory, teleport, potion effects, messages are thread-safe);</li>
 *   <li>async: I/O and CPU work that touches no world state.</li>
 * </ul>
 * Tick delays are in server ticks (20 per second).
 */
public interface Scheduler {

    /** True when running on Folia or a fork of it (Canvas). Informational only. */
    boolean regionized();

    Task global(Runnable task);

    Task globalLater(Runnable task, long delayTicks);

    Task globalTimer(Runnable task, long initialDelayTicks, long periodTicks);

    Task region(Location location, Runnable task);

    Task region(World world, int chunkX, int chunkZ, Runnable task);

    Task regionLater(Location location, Runnable task, long delayTicks);

    Task regionTimer(Location location, Runnable task, long initialDelayTicks, long periodTicks);

    /**
     * Runs on the thread that owns the entity. If the entity is removed before the task runs, {@code retired}
     * runs instead (may be null). Returns {@link Task#NONE} when the entity is already gone.
     */
    Task entity(Entity entity, Runnable task, Runnable retired);

    Task entityLater(Entity entity, Runnable task, Runnable retired, long delayTicks);

    Task entityTimer(Entity entity, Runnable task, Runnable retired, long initialDelayTicks, long periodTicks);

    Task async(Runnable task);

    Task asyncLater(Runnable task, Duration delay);

    Task asyncTimer(Runnable task, Duration initialDelay, Duration period);

    /** An executor that runs on the entity's owning thread; tasks for a removed entity are dropped. */
    Executor entityExecutor(Entity entity);

    /** An executor backed by {@link #async(Runnable)}. */
    Executor asyncExecutor();

    /** Runs {@code task} on the entity's thread and completes with its result, or exceptionally if retired. */
    <T> CompletableFuture<T> supplyOnEntity(Entity entity, Supplier<T> task);

    /** True if the current thread may touch this entity right now. */
    boolean owns(Entity entity);

    /** True if the current thread may touch this location right now. */
    boolean owns(Location location);

    /** True on the global region thread (Folia) or the main thread (Paper). */
    boolean isGlobalThread();

    /** True on any thread that ticks the world (main thread on Paper, any region thread on Folia). */
    boolean isTickThread();

    /** Cancels every task this plugin scheduled. Called on disable. */
    void cancelAll();
}
