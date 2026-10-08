package net.siftvanilla.siftcore.feature.spawn;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Spawn: {@code /spawn} and {@code /setspawn}, the protected spawn area (implements {@link SpawnArea} for random
 * teleport, combat and AFK), where new and respawning players arrive, and the world borders.
 */
public final class SpawnFeature implements Feature {

    public static final String COMMAND = "siftcore.command.spawn";
    public static final String ADMIN_OTHERS = "siftcore.admin.spawn";
    public static final String ADMIN_SET = "siftcore.admin.setspawn";
    static final String COOLDOWN_KEY = "spawn:teleport";

    private final Services services;
    private final Logger logger;
    private final Setting<SpawnSettings> settings;
    private final SpawnStore store;
    private final WorldBorders borders;
    private final SpawnProtection protection;
    private final SpawnCommands commands;
    private final SpawnArea area;
    private volatile SpawnPoint point;
    private volatile ProtectedRegion region = ProtectedRegion.NONE;
    private volatile boolean canvasRespawn;

    public SpawnFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        this.settings = services.configs().register(WorldBorders.FILE,
            reader -> SpawnSettings.parse(reader, name -> Bukkit.getWorld(name) != null, SpawnProtection::exists), problems);
        services.lang().register(SpawnMessages.class);
        var perms = services.permissions();
        perms.declare(COMMAND, "Use /spawn", true);
        perms.declare(ADMIN_OTHERS, "Send other players to spawn with /spawn <player>", false);
        perms.declare(ADMIN_SET, "Set the server spawn with /setspawn", false);
        perms.declare(SpawnProtection.BYPASS, "Build and use everything inside the protected spawn area", false);
        this.store = new SpawnStore(services.plugin().getDataFolder().toPath().resolve("data/spawn.yml"),
            services.scheduler().asyncExecutor(), this.logger);
        this.borders = new WorldBorders(this.settings::get, services.configs().files(), this.logger);
        this.protection = new SpawnProtection(() -> this.region, this.settings::get, services.messenger());
        this.commands = new SpawnCommands(services, this);
        this.area = location -> location != null && location.getWorld() != null
            && this.region.contains(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    @Override
    public String id() {
        return "spawn";
    }

    /** The protected spawn area (pure arithmetic, safe from any thread). */
    public SpawnArea area() {
        return this.area;
    }

    /** The configured world borders, for features that must stay inside them. */
    public WorldBorders borders() {
        return this.borders;
    }

    Setting<SpawnSettings> settings() {
        return this.settings;
    }

    /** The spawn as a point: the one set with /setspawn, else the default world's own spawn point. Null if neither exists. */
    SpawnPoint point() {
        SpawnPoint set = this.point;
        if (set != null) {
            return set;
        }
        World world = Bukkit.getWorld(this.settings.get().defaultWorld());
        if (world == null) {
            return null;
        }
        Location fallback = world.getSpawnLocation();
        return new SpawnPoint(world.getName(), fallback.getBlockX() + 0.5, fallback.getY(), fallback.getBlockZ() + 0.5,
            fallback.getYaw(), fallback.getPitch());
    }

    /** Where players go with /spawn, or null when the spawn world is not loaded. Safe from any thread. */
    public Location location() {
        SpawnPoint current = point();
        return current == null ? null : current.toLocation();
    }

    @Override
    public void enable() {
        try {
            Optional<SpawnPoint> saved = this.store.load();
            this.point = saved.orElse(null);
        } catch (IOException e) {
            this.logger.severe("The saved spawn point could not be read (" + e.getMessage() + "); using the world spawn of "
                + this.settings.get().defaultWorld() + " until /setspawn is used again.");
        }
        rebuildRegion();
        Bukkit.getPluginManager().registerEvents(this.protection, this.services.plugin());
        SpawnArrival arrival = new SpawnArrival(this.settings::get, this::location, this.services.messenger(),
            this.services.scheduler(), this.logger);
        this.canvasRespawn = arrival.register(this.services.plugin());
        this.services.scheduler().global(this.borders::apply);
        this.settings.onReload(s -> {
            rebuildRegion();
            this.services.scheduler().global(this.borders::apply);
        });
        this.services.hub().register(new HubEntry("spawn", 65, SpawnMessages.HUB_LABEL, SpawnMessages.HUB_DESCRIPTION, COMMAND,
            this::sendToSpawn));
    }

    private void rebuildRegion() {
        this.region = this.settings.get().region(point());
    }

    /** Sets and saves the spawn point, and moves the world's own spawn point there too. */
    void set(SpawnPoint spawn) {
        this.point = spawn;
        rebuildRegion();
        this.store.save(spawn);
        this.services.scheduler().global(() -> {
            Location location = spawn.toLocation();
            if (location != null) {
                location.getWorld().setSpawnLocation(location);
            }
        });
    }

    /** Starts /spawn for a player (warmup, refused in combat). Runs on the player's thread. */
    void sendToSpawn(Player player) {
        Location target = location();
        if (target == null) {
            this.services.messenger().send(player, SpawnMessages.NOT_AVAILABLE);
            return;
        }
        UUID id = player.getUniqueId();
        SpawnSettings s = this.settings.get();
        Duration left = this.services.cooldowns().remaining(id, COOLDOWN_KEY);
        if (!left.isZero() && !player.hasPermission("siftcore.bypass.cooldown")) {
            this.services.messenger().send(player, CoreMessages.COOLDOWN,
                Arg.time("time", left));
            return;
        }
        this.services.teleports().teleport(player, "spawn", s.warmup(), () -> {
            Location now = location();
            if (now == null) {
                this.services.messenger().send(player, SpawnMessages.NOT_AVAILABLE);
            }
            return CompletableFuture.completedFuture(now);
        }, ok -> {
            if (ok) {
                this.services.cooldowns().start(id, COOLDOWN_KEY, this.settings.get().cooldown());
            }
        });
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void disable() {
        this.store.flush(10);
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "spawn point is available", () -> {
            SpawnPoint current = point();
            if (current == null) {
                return "no spawn: world " + this.settings.get().defaultWorld() + " is not loaded and /setspawn was never used";
            }
            return current.toLocation() == null ? "the spawn world " + current.world() + " is not loaded" : null;
        });
        test.check(id(), "protected area covers the spawn point", () -> {
            SpawnSettings s = this.settings.get();
            SpawnPoint current = point();
            if (!s.protection().enabled() || current == null) {
                return null;
            }
            return this.region.contains(current.world(), current.x(), current.y(), current.z()) ? null
                : "the spawn point is outside the protected area (" + this.region.describe() + ")";
        });
        test.check(id(), "protected area answers from any thread", () -> {
            SpawnPoint current = point();
            if (current == null || !this.settings.get().protection().enabled()) {
                return null;
            }
            Location far = current.toLocation();
            if (far == null) {
                return null;
            }
            far.add(30_000_000, 0, 0);
            return this.area.contains(far) ? "a point 30 million blocks away counts as spawn" : null;
        });
        test.check(id(), "world borders match the config", this.borders::mismatch);
        test.check(id(), "spawn is inside the world border", () -> {
            SpawnPoint current = point();
            if (current == null) {
                return null;
            }
            return this.borders.current(current.world())
                .map(border -> border.inside(current.x(), current.z(), 0) ? null
                    : "spawn " + Math.round(current.x()) + ", " + Math.round(current.z()) + " is outside the border of " + current.world())
                .orElse(null);
        });
        test.check(id(), "respawn hook is installed", () -> this.canvasRespawn || !this.services.scheduler().regionized() ? null
            : "Canvas's respawn event was not found; players respawn at the world spawn instead");
    }
}
