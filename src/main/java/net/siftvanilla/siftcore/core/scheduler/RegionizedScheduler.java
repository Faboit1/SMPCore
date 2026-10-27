package net.siftvanilla.siftcore.core.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * {@link Scheduler} backed by Paper's region scheduler API. Paper implements this API on top of its single main
 * thread and Folia/Canvas implement it with real region threads, so one implementation serves both.
 * Every task is wrapped so an exception is logged with the plugin's logger instead of killing a region thread.
 */
public final class RegionizedScheduler implements Scheduler {

    private static final long MIN_DELAY = 1L;

    private final Plugin plugin;
    private final Server server;
    private final boolean regionized;
    private final Executor asyncExecutor;

    public RegionizedScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.regionized = detectRegionized();
        this.asyncExecutor = this::async;
    }

    private static boolean detectRegionized() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean regionized() {
        return this.regionized;
    }

    private Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                this.plugin.getLogger().log(Level.SEVERE, "A scheduled task failed", t);
            }
        };
    }

    private static Task wrap(ScheduledTask task) {
        return task == null ? Task.NONE : task::cancel;
    }

    private static long ticks(long delay) {
        return Math.max(MIN_DELAY, delay);
    }

    @Override
    public Task global(Runnable task) {
        Runnable guarded = guard(task);
        return wrap(this.server.getGlobalRegionScheduler().run(this.plugin, t -> guarded.run()));
    }

    @Override
    public Task globalLater(Runnable task, long delayTicks) {
        Runnable guarded = guard(task);
        return wrap(this.server.getGlobalRegionScheduler().runDelayed(this.plugin, t -> guarded.run(), ticks(delayTicks)));
    }

    @Override
    public Task globalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        Runnable guarded = guard(task);
        return wrap(this.server.getGlobalRegionScheduler().runAtFixedRate(this.plugin, t -> guarded.run(),
            ticks(initialDelayTicks), ticks(periodTicks)));
    }

    @Override
    public Task region(Location location, Runnable task) {
        Runnable guarded = guard(task);
        return wrap(this.server.getRegionScheduler().run(this.plugin, location, t -> guarded.run()));
    }

    @Override
    public Task region(World world, int chunkX, int chunkZ, Runnable task) {
        Runnable guarded = guard(task);
        return wrap(this.server.getRegionScheduler().run(this.plugin, world, chunkX, chunkZ, t -> guarded.run()));
    }

    @Override
    public Task regionLater(Location location, Runnable task, long delayTicks) {
        Runnable guarded = guard(task);
        return wrap(this.server.getRegionScheduler().runDelayed(this.plugin, location, t -> guarded.run(), ticks(delayTicks)));
    }

    @Override
    public Task regionTimer(Location location, Runnable task, long initialDelayTicks, long periodTicks) {
        Runnable guarded = guard(task);
        return wrap(this.server.getRegionScheduler().runAtFixedRate(this.plugin, location, t -> guarded.run(),
            ticks(initialDelayTicks), ticks(periodTicks)));
    }

    @Override
    public Task entity(Entity entity, Runnable task, Runnable retired) {
        Runnable guarded = guard(task);
        return wrap(entity.getScheduler().run(this.plugin, t -> guarded.run(), retired == null ? null : guard(retired)));
    }

    @Override
    public Task entityLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        Runnable guarded = guard(task);
        return wrap(entity.getScheduler().runDelayed(this.plugin, t -> guarded.run(),
            retired == null ? null : guard(retired), ticks(delayTicks)));
    }

    @Override
    public Task entityTimer(Entity entity, Runnable task, Runnable retired, long initialDelayTicks, long periodTicks) {
        Runnable guarded = guard(task);
        return wrap(entity.getScheduler().runAtFixedRate(this.plugin, t -> guarded.run(),
            retired == null ? null : guard(retired), ticks(initialDelayTicks), ticks(periodTicks)));
    }

    @Override
    public Task async(Runnable task) {
        Runnable guarded = guard(task);
        return wrap(this.server.getAsyncScheduler().runNow(this.plugin, t -> guarded.run()));
    }

    @Override
    public Task asyncLater(Runnable task, Duration delay) {
        Runnable guarded = guard(task);
        return wrap(this.server.getAsyncScheduler().runDelayed(this.plugin, t -> guarded.run(),
            Math.max(1L, delay.toMillis()), TimeUnit.MILLISECONDS));
    }

    @Override
    public Task asyncTimer(Runnable task, Duration initialDelay, Duration period) {
        Runnable guarded = guard(task);
        return wrap(this.server.getAsyncScheduler().runAtFixedRate(this.plugin, t -> guarded.run(),
            Math.max(1L, initialDelay.toMillis()), Math.max(1L, period.toMillis()), TimeUnit.MILLISECONDS));
    }

    @Override
    public Executor entityExecutor(Entity entity) {
        return command -> {
            if (!this.plugin.isEnabled()) {
                throw new RejectedExecutionException("SiftCore is disabled");
            }
            entity(entity, command, null);
        };
    }

    @Override
    public Executor asyncExecutor() {
        return this.asyncExecutor;
    }

    @Override
    public <T> CompletableFuture<T> supplyOnEntity(Entity entity, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (owns(entity)) {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return future;
        }
        Task scheduled = entity(entity, () -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, () -> future.completeExceptionally(new EntityRetiredException()));
        if (scheduled == Task.NONE) {
            future.completeExceptionally(new EntityRetiredException());
        }
        return future;
    }

    @Override
    public boolean owns(Entity entity) {
        return this.server.isOwnedByCurrentRegion(entity);
    }

    @Override
    public boolean owns(Location location) {
        return this.server.isOwnedByCurrentRegion(location);
    }

    @Override
    public boolean isGlobalThread() {
        return this.server.isGlobalTickThread();
    }

    @Override
    public boolean isTickThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void cancelAll() {
        this.server.getGlobalRegionScheduler().cancelTasks(this.plugin);
        this.server.getAsyncScheduler().cancelTasks(this.plugin);
    }

    /** Thrown when an entity was removed before a task scheduled on it could run. */
    public static final class EntityRetiredException extends RuntimeException {
        public EntityRetiredException() {
            super("The entity was removed before the task could run", null, false, false);
        }
    }
}
