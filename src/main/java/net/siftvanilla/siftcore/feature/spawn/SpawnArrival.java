package net.siftvanilla.siftcore.feature.spawn;

import io.papermc.paper.event.player.AbstractRespawnEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Where players arrive: brand-new players start at spawn (set before they enter the world, so there is no visible
 * teleport) and get a short welcome; players who die without a bed or respawn anchor respawn at spawn.
 * <p>
 * Respawn on Canvas does not fire {@code PlayerRespawnEvent}; it fires Canvas's own
 * {@code io.canvasmc.canvas.event.PlayerRespawnAsyncEvent}, which is registered by name so the plugin keeps no
 * compile-time dependency on Canvas. On Paper the regular event is handled instead.
 */
final class SpawnArrival implements Listener {

    private static final String CANVAS_RESPAWN = "io.canvasmc.canvas.event.PlayerRespawnAsyncEvent";
    /** A first-join player further than this from spawn after joining is teleported there (fallback). */
    private static final double ARRIVAL_TOLERANCE = 3.0;

    private final Supplier<SpawnSettings> settings;
    private final Supplier<Location> spawn;
    private final Messenger messenger;
    private final Scheduler scheduler;
    private final Logger logger;

    SpawnArrival(Supplier<SpawnSettings> settings, Supplier<Location> spawn, Messenger messenger, Scheduler scheduler, Logger logger) {
        this.settings = settings;
        this.spawn = spawn;
        this.messenger = messenger;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /** Registers this listener and, on Canvas, the asynchronous respawn event. Returns whether Canvas's event was found. */
    boolean register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Class<? extends Event> type;
        MethodHandle setter;
        try {
            type = Class.forName(CANVAS_RESPAWN).asSubclass(Event.class);
            setter = MethodHandles.publicLookup().findVirtual(type, "setRespawnLocation",
                MethodType.methodType(void.class, Location.class));
        } catch (ClassNotFoundException e) {
            return false;
        } catch (ReflectiveOperationException | ClassCastException e) {
            this.logger.log(Level.WARNING, "Canvas's respawn event has an unexpected shape; respawning at spawn is off", e);
            return false;
        }
        MethodHandle set = setter;
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.NORMAL, (listener, event) -> {
            if (type.isInstance(event) && event instanceof AbstractRespawnEvent respawn) {
                Location target = respawnTarget(respawn);
                if (target != null) {
                    try {
                        set.invoke(event, target);
                    } catch (Throwable t) {
                        this.logger.log(Level.WARNING, "Could not set the respawn location of " + respawn.getPlayer().getName(), t);
                    }
                }
            }
        }, plugin, false);
        return true;
    }

    /** Spawn, when this respawn should go there: death, no bed and no respawn anchor. Null otherwise. */
    private Location respawnTarget(AbstractRespawnEvent event) {
        if (!this.settings.get().respawnAtSpawn() || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH
            || event.isBedSpawn() || event.isAnchorSpawn()) {
            return null;
        }
        return this.spawn.get();
    }

    /** Paper (not fired on Canvas, where the asynchronous event above is used). */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!this.settings.get().respawnAtSpawn() || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH
            || event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        Location target = this.spawn.get();
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }

    /** New players are placed at spawn before they enter the world (runs off the main threads). */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        if (!event.isNewPlayer() || !this.settings.get().firstJoinAtSpawn()) {
            return;
        }
        Location target = this.spawn.get();
        if (target != null) {
            event.setSpawnLocation(target);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) {
            return;
        }
        SpawnSettings s = this.settings.get();
        Location target = this.spawn.get();
        if (s.firstJoinAtSpawn() && target != null && (!player.getWorld().equals(target.getWorld())
            || player.getLocation().distanceSquared(target) > ARRIVAL_TOLERANCE * ARRIVAL_TOLERANCE)) {
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        if (s.firstJoinWelcome()) {
            this.scheduler.entityLater(player, () -> {
                this.messenger.title(player, SpawnMessages.WELCOME_TITLE, SpawnMessages.WELCOME_SUBTITLE,
                    Arg.text("name", player.getName()));
                this.messenger.send(player, SpawnMessages.WELCOME_CHAT);
            }, null, 20L);
        }
    }
}
