package net.siftvanilla.siftcore.feature.staff;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Freeze: a frozen player keeps their position (they may still look around and chat), can only use the allowed
 * commands, can't break, place, use or attack anything, and can't be hurt by players. The state survives relogs;
 * logging out while frozen tells staff and can ban. Every listener runs on the frozen player's own thread.
 */
final class FreezeService implements Listener {

    private final Scheduler scheduler;
    private final StaffStore store;
    private final AuditLog audit;
    private final Messenger messenger;
    private final StaffText text;
    private final StaffNotices notices;
    private final Punishments punishments;
    private final Setting<StaffSettings> settings;
    private final Logger logger;
    private final Map<UUID, StaffStore.Freeze> frozen = new ConcurrentHashMap<>();
    private final Map<UUID, Task> timers = new ConcurrentHashMap<>();
    /**
     * Marks flight this feature gave a frozen player (so the server doesn't kick them for "flying" while they hang in
     * the air). It is kept in the player's saved data, so the flight is taken back even after a crash while frozen.
     */
    private static final NamespacedKey GRANTED_FLIGHT = new NamespacedKey("siftcore", "staff_freeze_flight");
    private static final Set<PlayerTeleportEvent.TeleportCause> BLOCKED_TELEPORTS = EnumSet.of(PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
        PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
        PlayerTeleportEvent.TeleportCause.END_PORTAL, PlayerTeleportEvent.TeleportCause.END_GATEWAY, PlayerTeleportEvent.TeleportCause.SPECTATE);
    /** Online players with {@link #GRANTED_FLIGHT}, for the shutdown. */
    private final Set<UUID> grantedFlight = ConcurrentHashMap.newKeySet();
    /** Held while a change is applied in memory and queued for storage, so a self-test sees both or neither. */
    private final Object lock = new Object();

    FreezeService(Scheduler scheduler, StaffStore store, AuditLog audit, Messenger messenger, StaffText text,
                  StaffNotices notices, Punishments punishments, Setting<StaffSettings> settings, Logger logger) {
        this.scheduler = scheduler;
        this.store = store;
        this.audit = audit;
        this.messenger = messenger;
        this.text = text;
        this.notices = notices;
        this.punishments = punishments;
        this.settings = settings;
        this.logger = logger;
    }

    /** Loads frozen players; call once from enable (blocks on storage). */
    void load() throws Exception {
        this.frozen.clear();
        this.frozen.putAll(this.store.frozen().get());
    }

    boolean frozen(UUID player) {
        return this.frozen.containsKey(player);
    }

    private boolean frozen(Player player) {
        return !this.frozen.isEmpty() && this.frozen.containsKey(player.getUniqueId());
    }

    Optional<StaffStore.Freeze> get(UUID player) {
        return Optional.ofNullable(this.frozen.get(player));
    }

    int count() {
        return this.frozen.size();
    }

    /** Frozen players in memory and in storage, counted at the same moment (self-test). */
    CompletableFuture<String> checkStorage() {
        int memory;
        CompletableFuture<Integer> stored;
        synchronized (this.lock) {
            memory = this.frozen.size();
            stored = this.store.countFrozen();
        }
        return stored.thenApply(count -> count == memory ? null : memory + " frozen in memory, " + count + " stored");
    }

    /** Freezes or unfreezes a player who may be offline. Returns false when nothing changed. Safe from any thread. */
    boolean set(UUID target, boolean freeze, Actor actor) {
        synchronized (this.lock) {
            if (freeze) {
                StaffStore.Freeze record = new StaffStore.Freeze(target, actor.id(), actor.name(), System.currentTimeMillis());
                if (this.frozen.putIfAbsent(target, record) != null) {
                    return false;
                }
                this.store.freeze(record).whenComplete(this::logFailure);
            } else {
                if (this.frozen.remove(target) == null) {
                    return false;
                }
                this.store.unfreeze(target).whenComplete(this::logFailure);
            }
        }
        this.audit.record(actor.id(), freeze ? "staff.freeze.on" : "staff.freeze.off", target.toString(), null);
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            Runnable apply = () -> apply(online, true);
            if (this.scheduler.owns(online)) {
                apply.run();
            } else {
                this.scheduler.entity(online, apply, null);
            }
        }
        return true;
    }

    /** On the player's thread: brings them in line with their freeze state. */
    private void apply(Player player, boolean announce) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (frozen(id)) {
            player.leaveVehicle();
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
                player.getPersistentDataContainer().set(GRANTED_FLIGHT, PersistentDataType.BYTE, (byte) 1);
                this.grantedFlight.add(id);
            } else if (player.getPersistentDataContainer().has(GRANTED_FLIGHT, PersistentDataType.BYTE)) {
                this.grantedFlight.add(id);
            }
            startTimer(player);
            if (announce) {
                this.messenger.send(player, StaffMessages.FROZEN);
            }
        } else {
            stopTimer(id);
            revokeFlight(player);
            if (announce) {
                this.messenger.send(player, StaffMessages.UNFROZEN);
            }
        }
    }

    /** Takes back flight this feature gave (and only that). Player's thread. */
    private void revokeFlight(Player player) {
        this.grantedFlight.remove(player.getUniqueId());
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.has(GRANTED_FLIGHT, PersistentDataType.BYTE)) {
            data.remove(GRANTED_FLIGHT);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void startTimer(Player player) {
        UUID id = player.getUniqueId();
        if (this.timers.containsKey(id)) {
            return;
        }
        long period = Math.max(20L, this.settings.get().freezeReminder().toMillis() / 50L);
        Task[] task = new Task[1];
        task[0] = this.scheduler.entityTimer(player, () -> {
            if (frozen(id)) {
                this.messenger.send(player, StaffMessages.FREEZE_REMINDER);
            } else {
                stopTimer(id);
            }
        }, () -> this.timers.remove(id, task[0]), 1L, period);
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

    /** Called from disable (the shutdown thread may touch every player): takes back the flight we gave. */
    void shutdown() {
        for (Task task : this.timers.values()) {
            task.cancel();
        }
        this.timers.clear();
        for (UUID id : Set.copyOf(this.grantedFlight)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                try {
                    revokeFlight(player);
                } catch (RuntimeException e) {
                    this.logger.log(Level.WARNING, "Could not take back the freeze flight of " + player.getName(), e);
                }
            }
        }
        this.grantedFlight.clear();
    }

    // ------------------------------------------------------------------ join and quit

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (frozen(event.getPlayer())) {
            apply(event.getPlayer(), true);
        } else {
            // Unfrozen while offline, or the server stopped without taking the flight back.
            revokeFlight(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        stopTimer(id);
        revokeFlight(player);
        StaffStore.Freeze record = this.frozen.get(id);
        if (record == null || event.getReason() == PlayerQuitEvent.QuitReason.KICKED) {
            return;
        }
        Arg name = Arg.text("name", player.getName());
        this.audit.record("system", "staff.freeze.logout", id.toString(), "frozen by " + this.text.staff(record.staff(), record.staffName())
            + ", left with reason " + event.getReason());
        StaffSettings s = this.settings.get();
        if (s.freezeBanOnLogout() && this.punishments.activeBan(id).isEmpty()) {
            this.punishments.issue(PunishmentType.BAN, id, player.getName(), Actor.stored(record.staff(), record.staffName()),
                s.freezeBanReason(), s.freezeBanLength(), true);
            this.notices.send(StaffNodes.FREEZE, null, StaffMessages.FREEZE_LOGOUT_BANNED, name);
        } else {
            this.notices.send(StaffNodes.FREEZE, null, StaffMessages.FREEZE_LOGOUT, name);
        }
    }

    // ------------------------------------------------------------------ what frozen players can't do

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (FreezeRules.changesPosition(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ())
            || !from.getWorld().equals(to.getWorld())) {
            Location kept = from.clone();
            kept.setYaw(to.getYaw());
            kept.setPitch(to.getPitch());
            event.setTo(kept);
        }
    }

    /** Pearls thrown before the freeze, chorus fruit and portals don't move a frozen player; staff teleports still do. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (BLOCKED_TELEPORTS.contains(event.getCause()) && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (frozen(event.getPlayer()) && !FreezeRules.commandAllowed(event.getMessage(), this.settings.get().freezeAllowedCommands())) {
            event.setCancelled(true);
            this.messenger.send(event.getPlayer(), StaffMessages.FREEZE_BLOCKED_COMMAND);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
            if (event.getAction() != Action.PHYSICAL) {
                this.messenger.send(event.getPlayer(), StaffMessages.FREEZE_BLOCKED);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    /** Frozen players don't deal damage and aren't hurt by players (or their arrows). Runs on the victim's thread. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (this.frozen.isEmpty()) {
            return;
        }
        Entity attacker = attacker(event);
        if (attacker instanceof Player player && frozen(player)) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player victim && frozen(victim) && attacker instanceof Player) {
            event.setCancelled(true);
        }
    }

    private static Entity attacker(EntityDamageByEntityEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing != null) {
            return causing;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return event.getDamager();
    }

    private void block(Player player, Cancellable event) {
        if (frozen(player)) {
            event.setCancelled(true);
            this.messenger.send(player, StaffMessages.FREEZE_BLOCKED);
        }
    }

    private void logFailure(Object ignored, Throwable error) {
        if (error != null) {
            this.logger.log(Level.SEVERE, "Could not store a freeze change", error);
        }
    }
}
