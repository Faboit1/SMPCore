package net.siftvanilla.siftcore.feature.spawn;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

/** Parsed {@code features/spawn.yml}. */
public record SpawnSettings(
    String defaultWorld,
    Duration warmup,
    Duration cooldown,
    boolean firstJoinAtSpawn,
    boolean firstJoinWelcome,
    boolean respawnAtSpawn,
    Protection protection,
    Borders borders) {

    /** The largest border vanilla allows. */
    public static final int MAX_BORDER = 59_999_968;
    private static final int MAX_COORDINATE = 29_999_984;
    private static final Pattern BLOCK_KEY = Pattern.compile("#?([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    public static final List<String> DEFAULT_ALLOWED = List.of("#minecraft:buttons", "#minecraft:doors", "#minecraft:trapdoors",
        "#minecraft:fence_gates", "#minecraft:pressure_plates");
    public static final Set<SpawnReason> DEFAULT_BLOCKED_REASONS = Set.copyOf(EnumSet.of(SpawnReason.NATURAL, SpawnReason.JOCKEY,
        SpawnReason.PATROL, SpawnReason.RAID, SpawnReason.REINFORCEMENTS, SpawnReason.VILLAGE_INVASION, SpawnReason.TRAP,
        SpawnReason.SILVERFISH_BLOCK, SpawnReason.ENDER_PEARL, SpawnReason.DROWNED, SpawnReason.NETHER_PORTAL));

    /** How the protected area is shaped. */
    public enum Shape {
        RADIUS,
        CUBOID
    }

    /** Two corners of a box in one world. */
    public record Corners(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    /** Spawn protection rules. */
    public record Protection(boolean enabled, Shape shape, int radius, Corners cuboid, List<String> allowedInteractions,
                             Set<SpawnReason> blockedSpawnReasons) {
        public Protection {
            allowedInteractions = List.copyOf(allowedInteractions);
            blockedSpawnReasons = Set.copyOf(blockedSpawnReasons);
        }
    }

    /** World borders managed by SiftCore, keyed by world name. */
    public record Borders(boolean enabled, Map<String, BorderSpec> worlds) {
        public static final Borders NONE = new Borders(false, Map.of());

        public Borders {
            worlds = Map.copyOf(worlds);
        }

        /** The managed border of a world, or null when SiftCore leaves that world's border alone. */
        public BorderSpec border(String world) {
            return this.enabled ? this.worlds.get(world) : null;
        }
    }

    /**
     * Parses the file.
     *
     * @param worldExists     whether a world with that name is loaded
     * @param blockKeyExists  whether a block name ({@code oak_door}) or block tag ({@code #minecraft:doors}) exists
     */
    public static SpawnSettings parse(ConfigReader r, Predicate<String> worldExists, Predicate<String> blockKeyExists) {
        ConfigReader spawn = r.section("spawn");
        String defaultWorld = spawn.string("default-world", "world");
        if (!worldExists.test(defaultWorld)) {
            spawn.problem("default-world", "is '" + defaultWorld + "', but no world with that name is loaded");
        }
        Duration warmup = spawn.duration("warmup", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(3));
        Duration cooldown = spawn.duration("cooldown", Duration.ZERO, Duration.ofHours(1), Duration.ZERO);

        ConfigReader arrival = r.section("arrival");
        boolean firstJoinAtSpawn = arrival.bool("first-join-at-spawn", true);
        boolean firstJoinWelcome = arrival.bool("first-join-welcome", true);
        boolean respawnAtSpawn = arrival.bool("respawn-at-spawn", true);

        Protection protection = parseProtection(r.section("protection"), defaultWorld, worldExists, blockKeyExists);
        Borders borders = parseBorders(r.section("world-border"), worldExists);
        return new SpawnSettings(defaultWorld, warmup, cooldown, firstJoinAtSpawn, firstJoinWelcome, respawnAtSpawn,
            protection, borders);
    }

    private static Protection parseProtection(ConfigReader p, String defaultWorld, Predicate<String> worldExists,
                                              Predicate<String> blockKeyExists) {
        boolean enabled = p.bool("enabled", true);
        Shape shape = p.enumValue("shape", Shape.class, Shape.RADIUS);
        int radius = p.integer("radius", 0, 10_000, 64);
        Corners corners = new Corners(defaultWorld, -64, -64, -64, 64, 320, 64);
        if (shape == Shape.CUBOID || p.has("cuboid")) {
            ConfigReader cuboid = p.section("cuboid", shape == Shape.CUBOID);
            if (cuboid.has("world") || shape == Shape.CUBOID) {
                String world = cuboid.string("world", defaultWorld);
                if (!worldExists.test(world)) {
                    cuboid.problem("world", "is '" + world + "', but no world with that name is loaded");
                }
                int[] from = cuboid.custom("from", SpawnSettings::blockCoordinates, "three whole numbers like \"-64 -64 -64\"",
                    new int[] {-64, -64, -64});
                int[] to = cuboid.custom("to", SpawnSettings::blockCoordinates, "three whole numbers like \"64 320 64\"",
                    new int[] {64, 320, 64});
                corners = new Corners(world, from[0], from[1], from[2], to[0], to[1], to[2]);
            }
        }
        List<String> allowed = new ArrayList<>();
        for (String raw : p.has("allowed-interactions") ? p.stringList("allowed-interactions", DEFAULT_ALLOWED) : DEFAULT_ALLOWED) {
            String key = raw.trim().toLowerCase(Locale.ROOT);
            if (!BLOCK_KEY.matcher(key).matches()) {
                p.problem("allowed-interactions", "contains '" + raw + "'; use a block name like oak_door or a tag like #minecraft:doors");
                continue;
            }
            if (!blockKeyExists.test(key)) {
                p.problem("allowed-interactions", "contains '" + raw + "', which is not a block " + (key.startsWith("#") ? "tag" : "type")
                    + " in this version");
                continue;
            }
            allowed.add(key);
        }
        Set<SpawnReason> reasons = EnumSet.noneOf(SpawnReason.class);
        if (p.has("blocked-spawn-reasons")) {
            for (String raw : p.stringList("blocked-spawn-reasons", List.of())) {
                String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
                try {
                    reasons.add(SpawnReason.valueOf(normalized));
                } catch (IllegalArgumentException e) {
                    p.problem("blocked-spawn-reasons", "contains '" + raw + "', which is not a spawn reason (for example natural, jockey, patrol)");
                }
            }
        } else {
            reasons.addAll(DEFAULT_BLOCKED_REASONS);
        }
        return new Protection(enabled, shape, radius, corners, allowed, reasons);
    }

    /** Parses the {@code world-border} section. Also used by random teleport to validate its rings. */
    public static Borders parseBorders(ConfigReader b, Predicate<String> worldExists) {
        boolean enabled = b.bool("enabled", true);
        Map<String, BorderSpec> worlds = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigReader> entry : b.children("worlds").entrySet()) {
            String world = entry.getKey();
            ConfigReader w = entry.getValue();
            if (!worldExists.test(world)) {
                b.problem("worlds." + world, "names a world that is not loaded");
            }
            long size = w.longValue("size", 1, MAX_BORDER, 10_000);
            int centerX = w.has("center-x") ? w.integer("center-x", -MAX_COORDINATE, MAX_COORDINATE, 0) : 0;
            int centerZ = w.has("center-z") ? w.integer("center-z", -MAX_COORDINATE, MAX_COORDINATE, 0) : 0;
            worlds.put(world, new BorderSpec(centerX, centerZ, size));
        }
        return new Borders(enabled, worlds);
    }

    private static int[] blockCoordinates(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) {
            throw new IllegalArgumentException("needs exactly three numbers");
        }
        int[] result = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + parts[i] + "' is not a whole number");
            }
            if (Math.abs((long) result[i]) > MAX_COORDINATE) {
                throw new IllegalArgumentException("'" + parts[i] + "' is outside the world");
            }
        }
        return result;
    }

    /** The protected region for the given spawn point under these settings. */
    public ProtectedRegion region(SpawnPoint spawn) {
        Protection p = this.protection;
        if (!p.enabled()) {
            return ProtectedRegion.NONE;
        }
        if (p.shape() == Shape.CUBOID) {
            Corners c = p.cuboid();
            return ProtectedRegion.Cuboid.of(c.world(), c.x1(), c.y1(), c.z1(), c.x2(), c.y2(), c.z2());
        }
        if (spawn == null) {
            return ProtectedRegion.NONE;
        }
        return new ProtectedRegion.Radius(spawn.world(), spawn.x(), spawn.z(), p.radius());
    }
}
