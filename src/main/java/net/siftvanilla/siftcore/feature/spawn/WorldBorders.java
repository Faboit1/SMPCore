package net.siftvanilla.siftcore.feature.spawn;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigException;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.config.YamlFiles;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

/**
 * The world borders from {@code features/spawn.yml}: applies them (on the global region thread, where the border
 * ticks) and answers "where is the border of this world", which random teleport uses to keep its rings inside.
 */
public final class WorldBorders {

    static final String FILE = "features/spawn.yml";
    private static final double EPSILON = 0.01;

    private final Supplier<SpawnSettings> settings;
    private final YamlFiles files;
    private final Logger logger;

    WorldBorders(Supplier<SpawnSettings> settings, YamlFiles files, Logger logger) {
        this.settings = settings;
        this.files = files;
        this.logger = logger;
    }

    /**
     * The border a world will have: the one configured in {@code features/spawn.yml} as it is on disk right now (so
     * a reload that changes both files is validated against the new border), or the world's current border when
     * SiftCore does not manage it. Empty when the world is not loaded and not configured.
     */
    public Optional<BorderSpec> planned(String world) {
        SpawnSettings.Borders onDisk = readFromDisk();
        BorderSpec configured = onDisk.border(world);
        if (configured != null) {
            return Optional.of(configured);
        }
        return live(world);
    }

    /** The border a world has now (configured value when managed, else the live border). */
    public Optional<BorderSpec> current(String world) {
        BorderSpec configured = this.settings.get().borders().border(world);
        return configured != null ? Optional.of(configured) : live(world);
    }

    /** The live border of a loaded world. Reads two fields, safe from any thread. */
    public static Optional<BorderSpec> live(String world) {
        World loaded = Bukkit.getWorld(world);
        if (loaded == null) {
            return Optional.empty();
        }
        WorldBorder border = loaded.getWorldBorder();
        Location center = border.getCenter();
        return Optional.of(new BorderSpec(center.getX(), center.getZ(), border.getSize()));
    }

    private SpawnSettings.Borders readFromDisk() {
        try {
            ConfigReader reader = new ConfigReader(FILE, this.files.load(FILE));
            if (!reader.has("world-border")) {
                return this.settings.get().borders();
            }
            SpawnSettings.Borders borders = SpawnSettings.parseBorders(reader.section("world-border"), name -> true);
            return reader.problems().isEmpty() ? borders : this.settings.get().borders();
        } catch (ConfigException | RuntimeException e) {
            return this.settings.get().borders();
        }
    }

    /** Applies the configured borders. Call on the global region thread. */
    void apply() {
        SpawnSettings.Borders borders = this.settings.get().borders();
        if (!borders.enabled()) {
            return;
        }
        for (Map.Entry<String, BorderSpec> entry : borders.worlds().entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) {
                continue;
            }
            BorderSpec spec = entry.getValue();
            WorldBorder border = world.getWorldBorder();
            Location center = border.getCenter();
            boolean changed = false;
            if (Math.abs(center.getX() - spec.centerX()) > EPSILON || Math.abs(center.getZ() - spec.centerZ()) > EPSILON) {
                border.setCenter(spec.centerX(), spec.centerZ());
                changed = true;
            }
            if (Math.abs(border.getSize() - spec.size()) > EPSILON) {
                border.setSize(spec.size());
                changed = true;
            }
            if (changed) {
                this.logger.info("World border of " + world.getName() + " set to " + Math.round(spec.size()) + " wide around "
                    + Math.round(spec.centerX()) + ", " + Math.round(spec.centerZ()) + ".");
            }
        }
    }

    /** Problems with the live borders compared to the config, or null when they match (self-test). */
    String mismatch() {
        SpawnSettings.Borders borders = this.settings.get().borders();
        if (!borders.enabled()) {
            return null;
        }
        StringBuilder problems = new StringBuilder();
        for (Map.Entry<String, BorderSpec> entry : borders.worlds().entrySet()) {
            Optional<BorderSpec> live = live(entry.getKey());
            BorderSpec spec = entry.getValue();
            if (live.isEmpty()) {
                problems.append(entry.getKey()).append(" is not loaded; ");
            } else if (Math.abs(live.get().size() - spec.size()) > EPSILON || Math.abs(live.get().centerX() - spec.centerX()) > EPSILON
                || Math.abs(live.get().centerZ() - spec.centerZ()) > EPSILON) {
                problems.append(entry.getKey()).append(" is ").append(Math.round(live.get().size())).append(" wide around ")
                    .append(Math.round(live.get().centerX())).append(", ").append(Math.round(live.get().centerZ()))
                    .append(" instead of ").append(Math.round(spec.size())).append("; ");
            }
        }
        return problems.isEmpty() ? null : problems.substring(0, problems.length() - 2);
    }
}
