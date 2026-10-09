package net.siftvanilla.siftcore.core.teleport;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.link.FreezeStatus;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Every plugin teleport (homes, TPA, RTP, spawn, team home) goes through here: it refuses while the player is
 * combat-tagged, runs a warmup with an action-bar countdown that is cancelled if the player moves to another block
 * or takes damage, resolves the destination (possibly async, e.g. RTP), and teleports with {@code teleportAsync} so
 * chunks load off the region thread. One pending teleport per player; a new one replaces the old. A player frozen
 * by staff is refused at the start, after the warmup and right before the move (a frozen player never changes block,
 * so the warmup alone would not stop them).
 */
public final class Teleports implements Listener {

    public static final String BYPASS_WARMUP = "siftcore.teleport.bypass-warmup";

    private record Pending(UUID player, String source, int blockX, int blockY, int blockZ, String world, Task timer,
                           Runnable cancelled) {
    }

    private final Scheduler scheduler;
    private final Messenger messenger;
    private final CombatStatus combat;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private volatile FreezeStatus freezes = FreezeStatus.NONE;

    public Teleports(Scheduler scheduler, Messenger messenger, CombatStatus combat) {
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.combat = combat;
    }

    /** Installs who is frozen by staff (the staff feature, wired once at startup): frozen players never teleport. */
    public void freezes(FreezeStatus freezes) {
        this.freezes = freezes;
    }

    /** True (after telling the player and the callback) when a staff freeze keeps the player in place. */
    private boolean frozen(Player player, Consumer<Boolean> callback) {
        if (!this.freezes.frozen(player.getUniqueId())) {
            return false;
        }
        this.messenger.send(player, TeleportMessages.FROZEN);
        callback.accept(false);
        return true;
    }

    /**
     * Starts a teleport. Must be called on the player's thread.
     *
     * @param source      short id used for logs and events, e.g. {@code home}
     * @param warmup      warmup duration (skipped with {@link #BYPASS_WARMUP})
     * @param destination supplies the destination when the warmup ends; may complete with null to abort silently
     *                    (the supplier is responsible for telling the player why)
     * @param done        receives true after a successful teleport, false otherwise (may be null)
     */
    public void teleport(Player player, String source, Duration warmup, Supplier<CompletableFuture<Location>> destination,
                         Consumer<Boolean> done) {
        Consumer<Boolean> callback = done == null ? ok -> { } : done;
        UUID id = player.getUniqueId();
        if (frozen(player, callback)) {
            return;
        }
        if (this.combat.tagged(id)) {
            this.messenger.send(player, TeleportMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(id)));
            callback.accept(false);
            return;
        }
        Pending previous = this.pending.remove(id);
        if (previous != null) {
            previous.timer().cancel();
            this.messenger.send(player, TeleportMessages.CANCELLED_REPLACED);
        }
        long seconds = warmup.toSeconds();
        if (seconds <= 0 || player.hasPermission(BYPASS_WARMUP)) {
            go(player, destination, callback);
            return;
        }
        Location here = player.getLocation();
        int[] left = {(int) seconds};
        Task[] timer = new Task[1];
        timer[0] = this.scheduler.entityTimer(player, () -> {
            Pending current = this.pending.get(id);
            if (current == null || current.timer() != timer[0]) {
                timer[0].cancel();
                return;
            }
            if (left[0] <= 0) {
                this.pending.remove(id, current);
                timer[0].cancel();
                go(player, destination, callback);
                return;
            }
            this.messenger.send(player, TeleportMessages.WARMUP, Arg.time("time", Duration.ofSeconds(left[0])));
            left[0]--;
        }, () -> this.pending.remove(id), 1L, 20L);
        this.pending.put(id, new Pending(id, source, here.getBlockX(), here.getBlockY(), here.getBlockZ(),
            here.getWorld().getName(), timer[0], () -> callback.accept(false)));
    }

    private void go(Player player, Supplier<CompletableFuture<Location>> destination, Consumer<Boolean> callback) {
        UUID id = player.getUniqueId();
        if (frozen(player, callback)) {
            return;
        }
        if (this.combat.tagged(id)) {
            this.messenger.send(player, TeleportMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(id)));
            callback.accept(false);
            return;
        }
        CompletableFuture<Location> future;
        try {
            future = destination.get();
        } catch (RuntimeException e) {
            this.messenger.send(player, TeleportMessages.FAILED);
            callback.accept(false);
            return;
        }
        // The callback always hears the outcome, also when the player leaves meanwhile (a retired entity task, or no
        // task at all when the player is already gone): random teleport pays a cost back when the move didn't happen.
        future.whenComplete((location, error) -> {
            Task scheduled = this.scheduler.entity(player, () -> arrive(player, location, error, callback), () -> callback.accept(false));
            if (scheduled == Task.NONE) {
                callback.accept(false);
            }
        });
    }

    /** Teleports to the resolved destination. Runs on the player's thread. */
    private void arrive(Player player, Location location, Throwable error, Consumer<Boolean> callback) {
        UUID id = player.getUniqueId();
        if (error != null) {
            this.messenger.send(player, TeleportMessages.FAILED);
            callback.accept(false);
            return;
        }
        if (location == null) {
            callback.accept(false);
            return;
        }
        if (frozen(player, callback)) {
            return;
        }
        if (this.combat.tagged(id)) {
            this.messenger.send(player, TeleportMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(id)));
            callback.accept(false);
            return;
        }
        player.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, failure) -> {
            boolean success = failure == null && Boolean.TRUE.equals(ok);
            Task scheduled = this.scheduler.entity(player, () -> {
                this.messenger.send(player, success ? TeleportMessages.DONE : TeleportMessages.FAILED);
                callback.accept(success);
            }, () -> callback.accept(success));
            if (scheduled == Task.NONE) {
                callback.accept(success);
            }
        });
    }

    /** Cancels a pending teleport, if any. Returns true if one was cancelled. */
    public boolean cancel(UUID player) {
        Pending pending = this.pending.remove(player);
        if (pending == null) {
            return false;
        }
        pending.timer().cancel();
        pending.cancelled().run();
        return true;
    }

    public boolean pending(UUID player) {
        return this.pending.containsKey(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (this.pending.isEmpty() || !event.hasChangedBlock()) {
            return;
        }
        Pending pending = this.pending.get(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        Location to = event.getTo();
        if (to.getBlockX() != pending.blockX() || to.getBlockY() != pending.blockY() || to.getBlockZ() != pending.blockZ()
            || !to.getWorld().getName().equals(pending.world())) {
            if (cancel(pending.player())) {
                this.messenger.send(event.getPlayer(), TeleportMessages.CANCELLED_MOVE);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (this.pending.isEmpty() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (cancel(player.getUniqueId())) {
            this.messenger.send(player, TeleportMessages.CANCELLED_DAMAGE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Pending pending = this.pending.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            pending.timer().cancel();
        }
    }
}
