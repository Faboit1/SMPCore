package net.siftvanilla.siftcore.feature.spawn;

import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.api.event.CombatTagEvent;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * {@code /fly} for players with {@code siftcore.spawn.fly}: flight inside the protected spawn area, and only there,
 * up to {@code fly.max-height} blocks above the spawn point. A player who turned it on gets a check on their own
 * thread twice a second (nobody else is ever checked), which turns it off when they left the area, got into combat or
 * lost the permission, and drops them when they fly above the height limit; a game mode or world change turns it off
 * at once, and so does a combat tag. Players in creative or spectator fly anyway and are left alone.
 * <p>
 * Whenever flight stops in the air, the fall that follows does no damage until the player lands, however long it
 * takes ({@link FlightRules.Fall}, checked on their own thread four times a second), so nobody dies from it; the
 * height limit keeps that fall short, so flying high inside spawn is no launch pad out of it.
 * <p>
 * The flight this gives is marked on the player, so flight left over from a crash (saved while they flew) is taken
 * away when they join again; quitting and shutting down turn it off before the player is saved.
 */
final class SpawnFlight implements Listener {

    static final String NODE = "siftcore.spawn.fly";
    /** How often a flying player's position and permission are checked. */
    private static final long CHECK_TICKS = 10;
    /** How often a protected fall is looked at. */
    private static final long FALL_TICKS = 5;

    /** Why flight turned off. */
    enum Off {
        COMMAND,
        LEFT,
        COMBAT,
        PERMISSION,
        GAME_MODE,
        WORLD,
        DISABLED,
        QUIT
    }

    /** A protected fall and the check that follows it. */
    private record Guard(FlightRules.Fall fall, Task task) {
    }

    private final Services services;
    private final Supplier<SpawnSettings> settings;
    private final SpawnArea area;
    private final DoubleSupplier ceiling;
    private final CombatStatus combat;
    private final NamespacedKey granted;
    /** Players flying with /fly, and the check that follows them. */
    private final Map<UUID, Task> flying = new ConcurrentHashMap<>();
    /** Players whose fall does no damage until they land. */
    private final Map<UUID, Guard> falls = new ConcurrentHashMap<>();

    /**
     * @param ceiling the highest y anyone may fly at spawn right now (the spawn point plus {@code fly.max-height})
     */
    SpawnFlight(Services services, Supplier<SpawnSettings> settings, SpawnArea area, DoubleSupplier ceiling, CombatStatus combat) {
        this.services = services;
        this.settings = settings;
        this.area = area;
        this.ceiling = ceiling;
        this.combat = combat;
        this.granted = new NamespacedKey(services.plugin(), "spawn_flight");
    }

    SiftCommand command() {
        return new SimpleCommand("fly", List.of(), "Turns flying at spawn on or off", NODE,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(NODE))
                .executes(ctx -> {
                    Player player = this.services.commands().player(ctx);
                    if (player != null) {
                        toggle(player);
                    }
                    return CommandSupport.OK;
                }));
    }

    /** Whether a player flies with /fly right now. */
    boolean flying(UUID player) {
        return this.flying.containsKey(player);
    }

    /** {@code /fly}. Player's thread. */
    void toggle(Player player) {
        if (!player.hasPermission(NODE)) {
            this.services.messenger().send(player, CoreMessages.NO_PERMISSION);
            return;
        }
        if (this.flying.containsKey(player.getUniqueId())) {
            stop(player, Off.COMMAND);
            return;
        }
        if (!this.settings.get().fly().enabled()) {
            this.services.messenger().send(player, SpawnMessages.FLY_DISABLED);
            return;
        }
        if (!survival(player)) {
            this.services.messenger().send(player, SpawnMessages.FLY_GAME_MODE);
            return;
        }
        if (this.combat.tagged(player.getUniqueId())) {
            this.services.messenger().send(player, SpawnMessages.FLY_IN_COMBAT,
                Arg.text("time", Durations.format(this.combat.remaining(player.getUniqueId()))));
            return;
        }
        if (!this.area.contains(player.getLocation())) {
            this.services.messenger().send(player, SpawnMessages.FLY_OUTSIDE);
            return;
        }
        if (FlightRules.aboveCeiling(player.getLocation().getY(), this.ceiling.getAsDouble())) {
            this.services.messenger().send(player, SpawnMessages.FLY_TOO_HIGH, height());
            return;
        }
        start(player);
        this.services.messenger().send(player, SpawnMessages.FLY_ON);
    }

    private Arg height() {
        return Arg.text("height", Lang.number(this.settings.get().fly().maxHeight()));
    }

    private void start(Player player) {
        UUID id = player.getUniqueId();
        player.setAllowFlight(true);
        player.getPersistentDataContainer().set(this.granted, PersistentDataType.BYTE, (byte) 1);
        Task check = this.services.scheduler().entityTimer(player, () -> check(player), () -> forget(id), CHECK_TICKS, CHECK_TICKS);
        Task previous = this.flying.put(id, check);
        if (previous != null) {
            previous.cancel();
        }
    }

    /** Twice a second for a flying player, on their thread. */
    private void check(Player player) {
        if (!this.flying.containsKey(player.getUniqueId())) {
            return;
        }
        if (!survival(player)) {
            // Creative or spectator fly anyway; nothing to take away.
            forget(player.getUniqueId());
            player.getPersistentDataContainer().remove(this.granted);
            return;
        }
        if (!player.hasPermission(NODE)) {
            stop(player, Off.PERMISSION);
        } else if (!this.settings.get().fly().enabled()) {
            stop(player, Off.DISABLED);
        } else if (this.combat.tagged(player.getUniqueId())) {
            stop(player, Off.COMBAT);
        } else if (!this.area.contains(player.getLocation())) {
            stop(player, Off.LEFT);
        } else if (player.isFlying() && FlightRules.aboveCeiling(player.getLocation().getY(), this.ceiling.getAsDouble())) {
            // Too high: they drop (flight stays allowed, so they can fly again lower down), and the drop is protected.
            player.setFlying(false);
            protectFall(player);
            this.services.messenger().send(player, SpawnMessages.FLY_CEILING, height());
        }
    }

    /** Turns flight off and says why. Player's thread (or the shutdown thread). */
    void stop(Player player, Off why) {
        UUID id = player.getUniqueId();
        if (!forget(id)) {
            return;
        }
        player.getPersistentDataContainer().remove(this.granted);
        // Leaving (quit, shutdown) needs no landing; reading the block below is only done on the player's own thread.
        boolean falling = why != Off.QUIT && airborne(player);
        if (survival(player)) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        boolean safe = falling && protectFall(player);
        switch (why) {
            case COMMAND -> this.services.messenger().send(player, SpawnMessages.FLY_OFF);
            case LEFT -> this.services.messenger().send(player, safe ? SpawnMessages.FLY_OFF_LEFT_FALLING : SpawnMessages.FLY_OFF_LEFT);
            case COMBAT -> this.services.messenger().send(player, SpawnMessages.FLY_OFF_COMBAT);
            case PERMISSION, WORLD, DISABLED -> this.services.messenger().send(player, SpawnMessages.FLY_OFF_OTHER);
            case GAME_MODE, QUIT -> {
                // The game mode change speaks for itself; nobody sees a message after quitting.
            }
        }
    }

    /**
     * Protects the fall a player starts now until they land (when fall protection is on). Player's thread. Returns
     * whether it is protected.
     */
    private boolean protectFall(Player player) {
        if (!this.settings.get().fly().fallProtection()) {
            return false;
        }
        UUID id = player.getUniqueId();
        FlightRules.Fall fall = new FlightRules.Fall(System.currentTimeMillis(), player.getFallDistance());
        Guard[] guard = new Guard[1];
        Task task = this.services.scheduler().entityTimer(player, () -> {
            if (guard[0] != null && fall.over(sample(player), System.currentTimeMillis())) {
                endFall(id, guard[0]);
            }
        }, () -> endFall(id, guard[0]), FALL_TICKS, FALL_TICKS);
        guard[0] = new Guard(fall, task);
        Guard previous = this.falls.put(id, guard[0]);
        if (previous != null) {
            previous.task().cancel();
        }
        return true;
    }

    /** Ends a protected fall (only that one: a newer one for the same player stays). */
    private void endFall(UUID player, Guard guard) {
        if (guard != null && this.falls.remove(player, guard)) {
            guard.task().cancel();
        }
    }

    /** How a falling player is doing. Player's thread. */
    private static FlightRules.Sample sample(Player player) {
        return new FlightRules.Sample(player.isOnGround(), !player.getLocation().subtract(0, 0.2, 0).getBlock().isPassable(),
            player.isInWater() || player.isInLava(), player.isClimbing(), player.isGliding(), player.isInsideVehicle(),
            player.isFlying(), player.getFallDistance());
    }

    /** Drops a player's flight state; true when they were flying with /fly. */
    private boolean forget(UUID player) {
        Task check = this.flying.remove(player);
        if (check == null) {
            return false;
        }
        check.cancel();
        return true;
    }

    /** Whether the player is in the air: flying, or nothing solid right under their feet. Player's thread. */
    private static boolean airborne(Player player) {
        return player.isFlying() || player.getLocation().subtract(0, 0.2, 0).getBlock().isPassable();
    }

    private static boolean survival(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    // ------------------------------------------------------------------ events

    /** The landing of a protected fall does no damage (once; the protection ends with it). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Guard guard = this.falls.remove(player.getUniqueId());
        if (guard != null) {
            guard.task().cancel();
            event.setCancelled(true);
        }
    }

    /** Combat ends flight at once. The tagged player may belong to another region (a long shot): on their thread. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTag(CombatTagEvent event) {
        Player player = event.player();
        if (this.flying.containsKey(player.getUniqueId())) {
            this.services.scheduler().entity(player, () -> stop(player, Off.COMBAT), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (this.flying.containsKey(event.getPlayer().getUniqueId())) {
            stop(event.getPlayer(), Off.GAME_MODE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        if (this.flying.containsKey(event.getPlayer().getUniqueId())) {
            stop(event.getPlayer(), Off.WORLD);
        }
    }

    /** Flight is taken away before the player is saved, so it never outlives the session. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer(), Off.QUIT);
        Guard guard = this.falls.remove(event.getPlayer().getUniqueId());
        if (guard != null) {
            guard.task().cancel();
        }
    }

    /** Flight saved by a crash while flying is taken away (it would work everywhere otherwise). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getPersistentDataContainer().has(this.granted, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(this.granted);
            if (survival(player)) {
                boolean falling = airborne(player);
                player.setFlying(false);
                player.setAllowFlight(false);
                if (falling) {
                    protectFall(player);
                }
            }
        }
    }

    /** At shutdown (no quit events): flight off for everyone flying with /fly, before players are saved. */
    void stopAll() {
        for (UUID id : List.copyOf(this.flying.keySet())) {
            Player player = this.services.plugin().getServer().getPlayer(id);
            if (player == null) {
                forget(id);
            } else {
                stop(player, Off.QUIT);
            }
        }
        for (Guard guard : List.copyOf(this.falls.values())) {
            guard.task().cancel();
        }
        this.falls.clear();
    }
}
