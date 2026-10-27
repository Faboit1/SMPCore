package net.siftvanilla.siftcore.feature.afk;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Corner;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Point;

/**
 * Parsed {@code features/afk.yml}.
 *
 * @param statusEvery how often the zone countdown is shown on the action bar (zero: never)
 * @param shardSound  played to a player the moment the zone pays them shards, or null for none
 */
record AfkSettings(
    Duration afkAfter,
    double lookThreshold,
    Duration manualGrace,
    Duration motionLimit,
    boolean kickEnabled,
    Duration kickAfter,
    Duration warnBefore,
    boolean zoneEnabled,
    ZoneSpec zone,
    boolean zoneSafe,
    Duration teleportWarmup,
    Duration teleportCooldown,
    Duration interval,
    long shards,
    Map<String, Long> rankShards,
    long dailyCap,
    Duration statusEvery,
    Sound shardSound) {

    static final ZoneSpec DEFAULT_ZONE = new ZoneSpec(ZoneSpec.Anchor.SPAWN, "world", new Corner(16, -16, -6),
        new Corner(28, 24, 6), null);

    AfkSettings {
        rankShards = Map.copyOf(rankShards);
    }

    AfkClock.Timing timing() {
        return new AfkClock.Timing(this.afkAfter.toMillis(), this.kickEnabled ? this.kickAfter.toMillis() : 0,
            this.warnBefore.toMillis(), this.manualGrace.toMillis(), this.motionLimit.toMillis());
    }

    ActivityClassifier.Settings activity() {
        ActivityClassifier.Settings defaults = ActivityClassifier.Settings.DEFAULTS;
        return new ActivityClassifier.Settings(this.lookThreshold, defaults.pathMemory(), defaults.straightLimit(),
            defaults.maxStep(), defaults.maxGlideStep(), defaults.pushedMillis());
    }

    /** Shards per interval for a player: the best rank tier they have, never less than the base amount. */
    long shardsFor(Predicate<String> hasTier) {
        long best = this.shards;
        for (Map.Entry<String, Long> tier : this.rankShards.entrySet()) {
            if (tier.getValue() > best && hasTier.test(tier.getKey())) {
                best = tier.getValue();
            }
        }
        return best;
    }

    static AfkSettings parse(ConfigReader r) {
        ConfigReader detection = r.section("detection");
        ConfigReader kick = r.section("kick");
        ConfigReader zone = r.section("zone");
        ConfigReader rewards = r.section("rewards");

        Duration kickAfter = kick.duration("after", Duration.ofSeconds(10), Duration.ofHours(24), Duration.ofMinutes(30));
        Duration warnBefore = kick.duration("warn-before", Duration.ZERO, Duration.ofHours(1), Duration.ofMinutes(1));
        if (warnBefore.compareTo(kickAfter) >= 0) {
            kick.problem("warn-before", "must be shorter than kick.after");
            warnBefore = Duration.ZERO;
        }

        ZoneSpec.Anchor anchor = zone.enumValue("anchor", ZoneSpec.Anchor.class, DEFAULT_ZONE.anchor());
        String world = zone.string("world", DEFAULT_ZONE.world()).trim();
        if (world.isEmpty()) {
            zone.problem("world", "is empty");
            world = DEFAULT_ZONE.world();
        }
        Corner from = zone.custom("from", Corner::parse, "three whole numbers like \"16 -16 -6\"", DEFAULT_ZONE.from());
        Corner to = zone.custom("to", Corner::parse, "three whole numbers like \"28 24 6\"", DEFAULT_ZONE.to());
        String arrivalText = zone.string("arrival", "auto").trim();
        Point arrival = null;
        if (!arrivalText.equalsIgnoreCase("auto")) {
            arrival = zone.custom("arrival", Point::parse, "auto, \"x y z\" or \"x y z yaw pitch\"", null);
        }
        ZoneSpec spec = new ZoneSpec(anchor, world, from, to, arrival);
        if (arrival != null) {
            ZoneBox box = spec.resolve(0, 0, 0);
            if (!box.contains(world, arrival.x(), arrival.y(), arrival.z())) {
                zone.problem("arrival", "is outside the zone between " + from.format() + " and " + to.format());
                spec = new ZoneSpec(anchor, world, from, to, null);
            }
        }

        Map<String, Long> ranks = new LinkedHashMap<>();
        ConfigReader rankSection = rewards.section("ranks", false);
        for (String tier : rankSection.keys()) {
            if (!tier.matches("[a-z0-9_-]{1,32}")) {
                rankSection.problem(tier, "is not a valid tier name (lowercase letters, digits, - and _)");
                continue;
            }
            ranks.put(tier, rankSection.longValue(tier, 1, 1_000, 1));
        }

        Duration statusEvery = rewards.duration("status-every", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(1));
        if (!statusEvery.isZero() && statusEvery.compareTo(Duration.ofSeconds(1)) < 0) {
            rewards.problem("status-every", "must be 0s (off) or at least 1s");
            statusEvery = Duration.ofSeconds(1);
        }

        return new AfkSettings(
            detection.duration("afk-after", Duration.ofSeconds(10), Duration.ofHours(2), Duration.ofMinutes(5)),
            detection.decimal("look-threshold", 1.0, 45.0, 5.0),
            detection.duration("manual-grace", Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(3)),
            detection.duration("motion-limit", Duration.ZERO, Duration.ofHours(24), Duration.ofMinutes(15)),
            kick.bool("enabled", true),
            kickAfter,
            warnBefore,
            zone.bool("enabled", true),
            spec,
            zone.bool("safe", true),
            zone.duration("teleport-warmup", Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(3)),
            zone.duration("teleport-cooldown", Duration.ZERO, Duration.ofHours(1), Duration.ofSeconds(10)),
            rewards.duration("interval", Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(60)),
            rewards.longValue("shards", 1, 1_000, 1),
            ranks,
            rewards.longValue("daily-cap", 0, 10_000_000, 0),
            statusEvery,
            sound(rewards.section("sound")));
    }

    /** The shard sound: {@code enabled}, {@code sound} (a sound id), {@code volume} and {@code pitch}; null when off. */
    static Sound sound(ConfigReader r) {
        if (!r.bool("enabled", true)) {
            return null;
        }
        Key key = r.key("sound", DEFAULT_SOUND);
        float volume = (float) r.decimal("volume", 0.0, 2.0, 0.8);
        float pitch = (float) r.decimal("pitch", 0.5, 2.0, 1.2);
        return Sound.sound(key, Sound.Source.MASTER, volume, pitch);
    }

    /** The shipped shard sound: an amethyst chime. */
    static final Key DEFAULT_SOUND = Key.key("block.amethyst_block.chime");
}
