package net.siftvanilla.siftcore.feature.rtp;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.feature.spawn.BorderSpec;

/** Parsed {@code features/rtp.yml}. */
record RtpSettings(Duration warmup, int maxAttempts, int spotsPerChunk, int borderMargin, Map<String, Region> regions) {

    /** The largest radius anyone could need (the vanilla world is 30 million blocks from the centre to the edge). */
    static final int MAX_RADIUS = 29_999_984;

    RtpSettings {
        regions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(regions));
    }

    /**
     * One place players can random teleport to: a ring around a centre in one world.
     *
     * @param permission extra permission needed, or null for everyone who can use /rtp
     */
    record Region(String id, String name, boolean enabled, String world, String permission, long cost, Duration cooldown,
                  double centerX, double centerZ, int minRadius, int maxRadius) {
    }

    /** Finds a region by id or, failing that, by world name (so /rtp world_nether works too). */
    Optional<Region> find(String idOrWorld) {
        String key = idOrWorld.toLowerCase(Locale.ROOT);
        Region byId = this.regions.get(key);
        if (byId != null) {
            return Optional.of(byId);
        }
        for (Region region : this.regions.values()) {
            if (region.world().equalsIgnoreCase(idOrWorld)) {
                return Optional.of(region);
            }
        }
        return Optional.empty();
    }

    /**
     * Parses the file and checks every ring against the border it will have.
     *
     * @param borderOf    the border a world will have (configured in features/spawn.yml or the live one)
     * @param worldExists whether a world with that name is loaded
     */
    static RtpSettings parse(ConfigReader r, MoneyFormat money, Function<String, Optional<BorderSpec>> borderOf,
                             Predicate<String> worldExists) {
        Duration warmup = r.duration("warmup", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(5));
        int maxAttempts = r.integer("max-attempts", 1, 64, 12);
        int spotsPerChunk = r.integer("spots-per-chunk", 1, 64, 8);
        int margin = r.integer("border-margin", 0, 10_000, 32);
        Map<String, Region> regions = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigReader> entry : r.children("regions").entrySet()) {
            String id = entry.getKey();
            ConfigReader g = entry.getValue();
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                r.problem("regions." + id, "has an invalid id; use 1-32 lowercase letters, digits, - or _");
                continue;
            }
            String name = g.string("name", id);
            if (name.isBlank() || name.length() > 32) {
                g.problem("name", "must be 1-32 characters");
                name = id;
            }
            boolean enabled = g.bool("enabled", true);
            String world = g.string("world", "world");
            if (!worldExists.test(world)) {
                g.problem("world", "is '" + world + "', but no world with that name is loaded");
            }
            String permission = g.optionalString("permission", "").trim();
            if (!permission.isEmpty() && !permission.matches("[a-z0-9_.-]{1,128}")) {
                g.problem("permission", "must be a permission node like siftcore.rtp.end, or empty");
                permission = "";
            }
            long cost = g.money("cost", money, true, 0);
            Duration cooldown = g.duration("cooldown", Duration.ZERO, Duration.ofDays(1), Duration.ofSeconds(60));
            int centerX = g.has("center-x") ? g.integer("center-x", -MAX_RADIUS, MAX_RADIUS, 0) : 0;
            int centerZ = g.has("center-z") ? g.integer("center-z", -MAX_RADIUS, MAX_RADIUS, 0) : 0;
            int minRadius = g.integer("min-radius", 0, MAX_RADIUS, 0);
            int maxRadius = g.integer("max-radius", 1, MAX_RADIUS, Math.max(1, minRadius + 1));
            if (maxRadius <= minRadius) {
                g.problem("max-radius", "is " + maxRadius + " but must be larger than min-radius (" + minRadius + ")");
                maxRadius = minRadius + 1;
            }
            Optional<BorderSpec> border = borderOf.apply(world);
            if (border.isPresent()) {
                String problem = RtpGeometry.problem(world, border.get(), centerX, centerZ, minRadius, maxRadius, margin);
                if (problem != null) {
                    g.problem("max-radius", problem);
                }
            }
            regions.put(id, new Region(id, name, enabled, world, permission.isEmpty() ? null : permission, cost, cooldown,
                centerX, centerZ, minRadius, maxRadius));
        }
        if (regions.isEmpty() && r.problems().isEmpty()) {
            r.problem("regions", "has no regions; add at least one (see the shipped file for an example)");
        }
        return new RtpSettings(warmup, maxAttempts, spotsPerChunk, margin, regions);
    }
}
