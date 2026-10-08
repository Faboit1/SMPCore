package net.siftvanilla.siftcore.feature.staff;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Vanish: hides staff from every player without {@code siftcore.staff.vanish.see} with
 * {@link Player#hidePlayer(Plugin, Player)}, shows them again with {@link Player#showPlayer(Plugin, Player)}, and keeps
 * that true for players who join later and for permission changes.
 * <p>
 * Threading (verified in the Canvas 962 sources, see docs/features/staff.md): {@code viewer.hidePlayer(plugin, v)}
 * records the hidden player in the viewer's visibility map (a {@code ConcurrentHashMap}) and then removes the
 * viewer from <em>v's</em> entity tracker ({@code seenBy}, a plain set owned by the region thread that ticks v).
 * {@code showPlayer} adds the viewer to that tracker. So every hide and show of v runs on v's own thread.
 * The one exception is the join of another player, handled in {@link #onJoin}.
 */
final class VanishService implements VanishStatus, Listener {

    private final Plugin plugin;
    private final Scheduler scheduler;
    private final StaffStore store;
    private final AuditLog audit;
    private final Messenger messenger;
    private final Setting<StaffSettings> settings;
    private final Logger logger;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Task> timers = new ConcurrentHashMap<>();
    /** Held while a change is applied in memory and queued for storage, so a self-test sees both or neither. */
    private final Object lock = new Object();

    VanishService(Plugin plugin, Scheduler scheduler, StaffStore store, AuditLog audit, Messenger messenger,
                  Setting<StaffSettings> settings, Logger logger) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.store = store;
        this.audit = audit;
        this.messenger = messenger;
        this.settings = settings;
        this.logger = logger;
    }

    /** Loads who is vanished; call once from enable (blocks on storage). */
    void load() throws Exception {
        this.vanished.clear();
        this.vanished.addAll(this.store.vanished().get());
    }

    @Override
    public boolean vanished(UUID player) {
        return this.vanished.contains(player);
    }

    boolean vanished(Player player) {
        return this.vanished.contains(player.getUniqueId());
    }

    /** Number of vanished staff (online or not). */
    int count() {
        return this.vanished.size();
    }

    /** Number of vanished staff who are online right now. */
    int online() {
        if (this.vanished.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (UUID id : this.vanished) {
            if (Bukkit.getPlayer(id) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Turns vanish on or off for a player who may be offline (it applies when they join). Returns false when the
     * player already was in that state. Safe from any thread.
     */
    boolean set(UUID target, boolean vanish, Actor actor) {
        synchronized (this.lock) {
            boolean changed = vanish ? this.vanished.add(target) : this.vanished.remove(target);
            if (!changed) {
                return false;
            }
            if (vanish) {
                this.store.vanish(target, System.currentTimeMillis()).whenComplete(this::logFailure);
            } else {
                this.store.unvanish(target).whenComplete(this::logFailure);
            }
        }
        this.audit.record(actor.id(), vanish ? "staff.vanish.on" : "staff.vanish.off", target.toString(), null);
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            onTargetThread(online, () -> reconcile(online));
        }
        return true;
    }

    /** Runs a visibility change of {@code target} on the thread that owns it (now if this thread does). */
    private void onTargetThread(Player target, Runnable task) {
        if (this.scheduler.owns(target)) {
            task.run();
        } else {
            this.scheduler.entity(target, task, null);
        }
    }

    /**
     * Makes every online player's view of {@code target} match its state: hidden from players without the see
     * permission while vanished, visible to everyone otherwise. Must run on target's thread. Only our own hides are
     * undone; a player hidden by another plugin stays hidden.
     */
    private void reconcile(Player target) {
        if (!target.isOnline()) {
            return;
        }
        boolean hidden = vanished(target);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) {
                continue;
            }
            boolean shouldSee = !hidden || viewer.hasPermission(StaffNodes.VANISH_SEE);
            boolean sees = viewer.canSee(target);
            if (shouldSee && !sees) {
                viewer.showPlayer(this.plugin, target);
            } else if (!shouldSee && sees) {
                viewer.hidePlayer(this.plugin, target);
            }
        }
        mark(target, hidden);
        if (hidden) {
            startTimer(target);
        } else {
            stopTimer(target.getUniqueId());
        }
    }

    /**
     * Sets or clears the "vanished" metadata, the convention other plugins (TAB's online counts, EssentialsX,
     * PremiumVanish users) read to leave a vanished player out. Target's thread.
     */
    private void mark(Player target, boolean hidden) {
        if (hidden) {
            target.setMetadata("vanished", new org.bukkit.metadata.FixedMetadataValue(this.plugin, true));
        } else {
            target.removeMetadata("vanished", this.plugin);
        }
    }

    // ------------------------------------------------------------------ reminder and upkeep

    private void startTimer(Player target) {
        UUID id = target.getUniqueId();
        if (this.timers.containsKey(id)) {
            return;
        }
        long period = Math.max(20L, this.settings.get().vanishReminder().toMillis() / 50L);
        Task[] task = new Task[1];
        task[0] = this.scheduler.entityTimer(target, () -> tick(target), () -> this.timers.remove(id, task[0]), period, period);
        if (task[0] != Task.NONE && this.timers.putIfAbsent(id, task[0]) != null) {
            task[0].cancel();
        }
    }

    private void stopTimer(UUID id) {
        Task task = this.timers.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    /** On the vanished player's thread: re-applies visibility (permissions may have changed) and reminds them. */
    private void tick(Player target) {
        if (!vanished(target)) {
            stopTimer(target.getUniqueId());
            return;
        }
        reconcile(target);
        this.messenger.send(target, StaffMessages.VANISH_REMINDER);
    }

    void stopAll() {
        for (Task task : this.timers.values()) {
            task.cancel();
        }
        this.timers.clear();
    }

    // ------------------------------------------------------------------ joins and quits

    /**
     * Applies vanish before the join completes, so nobody sees a flash of a vanished player. This handler runs on the
     * joining player's thread, inside {@code PlayerList#placeNewPlayer} after the player was added to the world with
     * its entity tracker suppressed and before the tab-list packets go out and tracking starts.
     * <ul>
     *   <li>A vanished player joining: hidden from every viewer here. The joining player has no tracker entry yet and
     *       has not been listed, so {@code hidePlayer} only records the hide in the viewers' visibility maps (thread
     *       safe); the tab-list entries and tracking that follow skip those viewers.</li>
     *   <li>Anyone joining without the see permission: every vanished player is hidden from them here. If the joiner's
     *       thread owns the vanished player (same region) that is the normal owner-thread call. Otherwise the call
     *       still only records the hide in the joiner's own map plus one read-only lookup in the vanished player's
     *       tracker (the joiner can't be in it: trackers only add players their own region owns), and nothing is
     *       sent because the joiner is not listed yet. Doing it here is what keeps the vanished player out of the
     *       joiner's tab list; scheduling it on the other region would only apply after the tab list was sent.</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        UUID id = joined.getUniqueId();
        if (vanished(id)) {
            if (joined.hasPermission(StaffNodes.VANISH)) {
                mark(joined, true);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (!viewer.equals(joined) && !viewer.hasPermission(StaffNodes.VANISH_SEE) && viewer.canSee(joined)) {
                        viewer.hidePlayer(this.plugin, joined);
                    }
                }
                startTimer(joined);
            } else {
                set(id, false, Actor.console());
                this.messenger.send(joined, StaffMessages.VANISH_CLEARED);
            }
        }
        if (this.vanished.isEmpty() || joined.hasPermission(StaffNodes.VANISH_SEE)) {
            return;
        }
        for (UUID other : this.vanished) {
            Player hidden = other.equals(id) ? null : Bukkit.getPlayer(other);
            if (hidden != null && joined.canSee(hidden)) {
                hideFromJoiner(joined, hidden);
            }
        }
    }

    private void hideFromJoiner(Player joined, Player hidden) {
        try {
            joined.hidePlayer(this.plugin, hidden);
            if (!vanished(hidden)) {
                // Unvanished while we were hiding: let the owner thread settle everyone's view.
                this.scheduler.entity(hidden, () -> reconcile(hidden), null);
            }
        } catch (RuntimeException e) {
            // A concurrent change of the other region's tracker made the read-only lookup fail. The hide itself was
            // recorded before the lookup; redo it on the owner thread to be certain.
            this.logger.log(Level.FINE, "Re-applying vanish of " + hidden.getName() + " for " + joined.getName() + " on its own thread", e);
            this.scheduler.entity(hidden, () -> {
                if (joined.isOnline() && vanished(hidden) && !joined.hasPermission(StaffNodes.VANISH_SEE) && joined.canSee(hidden)) {
                    joined.hidePlayer(this.plugin, hidden);
                }
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stopTimer(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ what vanished players don't do

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && vanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        if (vanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && !this.vanished.isEmpty() && vanished(player)) {
            event.setCancelled(true);
        }
    }

    /** Pressure plates, tripwires, farmland and turtle eggs report a player standing on them as a physical interact. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPhysical(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && vanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Sculk sensors and shriekers would hear a vanished player walking. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVibration(BlockReceiveGameEvent event) {
        if (event.getEntity() instanceof Player player && vanished(player)) {
            event.setCancelled(true);
        }
    }

    /** Vanished players are not in the server list's player sample or count. Runs on a network thread. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPing(PaperServerListPingEvent event) {
        int hidden = online();
        if (hidden == 0) {
            return;
        }
        event.getListedPlayers().removeIf(info -> this.vanished.contains(info.id()));
        if (!event.shouldHidePlayers()) {
            event.setNumPlayers(Math.max(0, event.getNumPlayers() - hidden));
        }
    }

    /** Vanished players in memory and in storage, counted at the same moment (self-test). */
    CompletableFuture<String> checkStorage() {
        int memory;
        CompletableFuture<Integer> stored;
        synchronized (this.lock) {
            memory = this.vanished.size();
            stored = this.store.countVanished();
        }
        return stored.thenApply(count -> count == memory ? null : memory + " vanished in memory, " + count + " stored");
    }

    /** Null when every online vanished player is hidden from every online player who may not see them. */
    String checkHidden() {
        for (UUID id : this.vanished) {
            Player hidden = Bukkit.getPlayer(id);
            if (hidden == null) {
                continue;
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(hidden) && !viewer.hasPermission(StaffNodes.VANISH_SEE) && viewer.canSee(hidden)) {
                    return viewer.getName() + " can see vanished " + hidden.getName();
                }
            }
        }
        return null;
    }

    private void logFailure(Object ignored, Throwable error) {
        if (error != null) {
            this.logger.log(Level.SEVERE, "Could not store a vanish change", error);
        }
    }
}
