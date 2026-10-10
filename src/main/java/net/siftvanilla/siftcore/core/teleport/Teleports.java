package net.siftvanilla.siftcore.core.teleport;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.siftvanilla.siftcore.core.link.FreezeStatus;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
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
 * combat-tagged, runs a warmup with a countdown that is cancelled if the player moves to another block or takes
 * damage, resolves the destination (possibly async, e.g. RTP), and teleports with {@code teleportAsync} so chunks
 * load off the region thread. One pending teleport per player; a new one replaces the old. A player frozen by staff
 * is refused at the start, after the warmup and right before the move (a frozen player never changes block, so the
 * warmup alone would not stop them).
 * <p>
 * The countdown and the arrival line show where the player's {@link #DISPLAY} setting says (above the hotbar, as a
 * title, one chat line, or not at all; see {@link TeleportDisplay}). Cancel messages and failures always show; a
 * countdown title is taken off the screen first, so it never sits over the line that says the teleport was called off.
 * Features with an arrival line of their own (a home's welcome, a random teleport's landing) start the teleport with
 * {@code announces} and send that line through {@link #arrival}, so it follows the same setting.
 */
public final class Teleports implements Listener {

    public static final String BYPASS_WARMUP = "siftcore.teleport.bypass-warmup";

    /**
     * Where the warmup countdown and the arrival line show: above the hotbar (the default), as a title, one chat line,
     * or not at all. Registered in Settings &gt; Teleports &amp; homes when the teleports are built.
     */
    public static final Choice<AlertStyle> DISPLAY = Choices.alert("teleport-display", AlertStyle.ACTIONBAR,
            AlertStyle.ACTIONBAR, AlertStyle.TITLE, AlertStyle.CHAT, AlertStyle.OFF)
        .text(TeleportMessages.SETTING_DISPLAY, TeleportMessages.SETTING_DISPLAY_DESCRIPTION).build();
    /** {@link #DISPLAY}'s place among the teleport settings (its position in the settings catalog). */
    static final int DISPLAY_ORDER = 4;

    private record Pending(UUID player, Player entity, String source, int blockX, int blockY, int blockZ, String world, Task timer,
                           Runnable cancelled) {
    }

    private final Scheduler scheduler;
    private final Messenger messenger;
    private final CombatStatus combat;
    private final PlayerSettings settings;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** Players whose screen may still show a countdown title (cleared when the teleport is called off or fails). */
    private final Set<UUID> countdownTitles = ConcurrentHashMap.newKeySet();
    private volatile FreezeStatus freezes = FreezeStatus.NONE;

    /**
     * @param settings the players' settings, for where the warmup countdown and arrival show ({@link #DISPLAY}, which
     *                 this registers); null in tests that don't need it (everyone then reads the default)
     */
    public Teleports(Scheduler scheduler, Messenger messenger, CombatStatus combat, PlayerSettings settings) {
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.combat = combat;
        this.settings = settings;
        if (settings != null && settings.setting(DISPLAY.id()) == null) {
            settings.register(SettingCategories.TELEPORT, DISPLAY, SettingOptions.<AlertStyle>builder().order(DISPLAY_ORDER).build());
        }
    }

    /** The players' settings this teleport service reads. */
    public PlayerSettings settings() {
        return this.settings;
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
        clearCountdown(player);
        this.messenger.send(player, TeleportMessages.FROZEN);
        callback.accept(false);
        return true;
    }

    /** The player's {@link #DISPLAY} choice (the default when no settings are wired). */
    private AlertStyle display(Player player) {
        return this.settings == null ? DISPLAY.defaultValue() : this.settings.get(player, DISPLAY);
    }

    /**
     * Sends a teleport's arrival line where the player's {@link #DISPLAY} setting says: the line's own place (above the
     * hotbar, or where the feedback channel puts results), a title, a chat line, or nothing. Features that start a
     * teleport with {@code announces} call this from their {@code done} callback. Any thread.
     */
    public void arrival(Player player, MessageKey key, Arg... args) {
        arrival(player, false, key, args);
    }

    /**
     * {@link #arrival(Player, MessageKey, Arg...)} for a line the player must see even with the display off (it says
     * money was taken for the teleport): "off" then shows it in its usual place.
     */
    public void arrival(Player player, boolean essential, MessageKey key, Arg... args) {
        switch (TeleportDisplay.arrival(display(player), essential)) {
            case FEEDBACK, STATUS -> this.messenger.send(player, key, args);
            case TITLE -> {
                // Replaces the countdown title.
                this.countdownTitles.remove(player.getUniqueId());
                this.messenger.alert(player, AlertStyle.TITLE, key, args);
            }
            case CHAT -> this.messenger.alert(player, AlertStyle.CHAT, key, args);
            case NONE -> {
            }
        }
    }

    /**
     * One second of the warmup countdown, where the player's {@link #DISPLAY} setting says. As a title, each second
     * replaces the number in place (only the first fades in) and stays just long enough to meet the next one
     * ({@link TeleportDisplay#countdownTimes}); quiet in combat doesn't apply, any hit cancels the warmup anyway.
     */
    private void countdown(Player player, Duration left, boolean first) {
        Arg time = Arg.time("time", left);
        switch (TeleportDisplay.countdown(display(player), first)) {
            case STATUS, FEEDBACK -> this.messenger.send(player, TeleportMessages.WARMUP, time);
            case TITLE -> {
                this.countdownTitles.add(player.getUniqueId());
                player.showTitle(Title.title(this.messenger.lang().get(TeleportMessages.WARMUP, time), Component.empty(),
                    TeleportDisplay.countdownTimes(first)));
            }
            case CHAT -> this.messenger.alert(player, AlertStyle.CHAT, false, TeleportMessages.WARMUP, time);
            case NONE -> {
            }
        }
    }

    /**
     * Takes a countdown title off the player's screen, if one may still show: the teleport was called off or failed, and
     * the line saying so (above the hotbar or in chat) must not sit under "Teleporting in 3s. Don't move.". Only a
     * title this service put up is cleared. Sends a packet, so any thread.
     */
    private void clearCountdown(Player player) {
        if (this.countdownTitles.remove(player.getUniqueId())) {
            player.clearTitle();
        }
    }

    /**
     * Starts a teleport that ends with "Teleported." (where the player's {@link #DISPLAY} says). Must be called on the
     * player's thread.
     *
     * @param source      short id used for logs and events, e.g. {@code home}
     * @param warmup      warmup duration (skipped with {@link #BYPASS_WARMUP})
     * @param destination supplies the destination when the warmup ends; may complete with null to abort silently
     *                    (the supplier is responsible for telling the player why)
     * @param done        receives true after a successful teleport, false otherwise (may be null)
     */
    public void teleport(Player player, String source, Duration warmup, Supplier<CompletableFuture<Location>> destination,
                         Consumer<Boolean> done) {
        teleport(player, source, warmup, destination, done, false);
    }

    /**
     * Starts a teleport. Must be called on the player's thread.
     *
     * @param announces true when the caller tells the player about the arrival itself, with {@link #arrival} from
     *                  {@code done}: nothing is said here on success then (failures are still told)
     * @see #teleport(Player, String, Duration, Supplier, Consumer)
     */
    public void teleport(Player player, String source, Duration warmup, Supplier<CompletableFuture<Location>> destination,
                         Consumer<Boolean> done, boolean announces) {
        Consumer<Boolean> callback = done == null ? ok -> { } : done;
        UUID id = player.getUniqueId();
        if (frozen(player, callback)) {
            return;
        }
        if (this.combat.tagged(id)) {
            clearCountdown(player);
            this.messenger.send(player, TeleportMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(id)));
            callback.accept(false);
            return;
        }
        Pending previous = this.pending.remove(id);
        if (previous != null) {
            previous.timer().cancel();
            clearCountdown(player);
            this.messenger.send(player, TeleportMessages.CANCELLED_REPLACED);
        }
        long seconds = warmup.toSeconds();
        if (seconds <= 0 || player.hasPermission(BYPASS_WARMUP)) {
            go(player, destination, callback, announces);
            return;
        }
        Location here = player.getLocation();
        int[] left = {(int) seconds};
        boolean[] first = {true};
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
                go(player, destination, callback, announces);
                return;
            }
            countdown(player, Duration.ofSeconds(left[0]), first[0]);
            first[0] = false;
            left[0]--;
        }, () -> this.pending.remove(id), 1L, 20L);
        this.pending.put(id, new Pending(id, player, source, here.getBlockX(), here.getBlockY(), here.getBlockZ(),
            here.getWorld().getName(), timer[0], () -> callback.accept(false)));
    }

    private void go(Player player, Supplier<CompletableFuture<Location>> destination, Consumer<Boolean> callback, boolean announces) {
        UUID id = player.getUniqueId();
        if (frozen(player, callback)) {
            return;
        }
        if (this.combat.tagged(id)) {
            clearCountdown(player);
            this.messenger.send(player, TeleportMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(id)));
            callback.accept(false);
            return;
        }
        CompletableFuture<Location> future;
        try {
            future = destination.get();
        } catch (RuntimeException e) {
            clearCountdown(player);
            this.messenger.send(player, TeleportMessages.FAILED);
            callback.accept(false);
            return;
        }
        // The callback always hears the outcome, also when the player leaves meanwhile (a retired entity task, or no
        // task at all when the player is already gone): random teleport pays a cost back when the move didn't happen.
        future.whenComplete((location, error) -> {
            Task scheduled = this.scheduler.entity(player, () -> arrive(player, location, error, callback, announces),
                () -> callback.accept(false));
            if (scheduled == Task.NONE) {
                callback.accept(false);
            }
        });
    }

    /** Teleports to the resolved destination. Runs on the player's thread. */
    private void arrive(Player player, Location location, Throwable error, Consumer<Boolean> callback, boolean announces) {
        UUID id = player.getUniqueId();
        if (error != null) {
            clearCountdown(player);
            this.messenger.send(player, TeleportMessages.FAILED);
            callback.accept(false);
            return;
        }
        if (location == null) {
            // The destination's supplier told the player why (the other player left, a region with no safe spot...).
            clearCountdown(player);
            callback.accept(false);
            return;
        }
        if (frozen(player, callback)) {
            return;
        }
        if (this.combat.tagged(id)) {
            clearCountdown(player);
            this.messenger.send(player, TeleportMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(id)));
            callback.accept(false);
            return;
        }
        player.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, failure) -> {
            boolean success = failure == null && Boolean.TRUE.equals(ok);
            Task scheduled = this.scheduler.entity(player, () -> {
                if (!success) {
                    clearCountdown(player);
                    this.messenger.send(player, TeleportMessages.FAILED);
                } else {
                    // Arrived: the arrival line (a title too, with this display) takes over, or the countdown's last
                    // second runs out by itself.
                    this.countdownTitles.remove(player.getUniqueId());
                    if (!announces) {
                        arrival(player, TeleportMessages.DONE);
                    }
                }
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
        clearCountdown(pending.entity());
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
        this.countdownTitles.remove(event.getPlayer().getUniqueId());
    }
}
