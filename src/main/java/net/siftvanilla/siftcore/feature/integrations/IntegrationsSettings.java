package net.siftvanilla.siftcore.feature.integrations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/** Parsed {@code features/integrations.yml}. */
record IntegrationsSettings(boolean placeholderApi, boolean floodgate, LuckPerms luckPerms, Backups backups, int auditPageSize,
                            int listPageSize, Store store) {

    /** Rank labels from LuckPerms. */
    record LuckPerms(boolean enabled, String labelMeta, String colorMeta, String gradientMeta, Set<String> hiddenGroups) {
    }

    /** Automatic SQLite backups. */
    record Backups(Duration interval, int keep) {
        boolean automatic() {
            return !this.interval.isZero();
        }
    }

    /** Limits and messages of store delivery. */
    record Store(long maxMoney, long maxShards, int maxKeys, Set<String> rankGroups, Duration minRankDuration,
                 Duration maxRankDuration, boolean notifyPlayer, boolean announce) {

        /** Whether a group may be granted (an empty list allows every LuckPerms group). */
        boolean allowsGroup(String group) {
            return this.rankGroups.isEmpty() || this.rankGroups.contains(group);
        }
    }

    private static String metaName(ConfigReader section, String path, String fallback) {
        String name = section.string(path, fallback).strip();
        if (name.isEmpty()) {
            section.problem(path, "must not be empty");
            return fallback;
        }
        return name;
    }

    static IntegrationsSettings parse(ConfigReader r, MoneyFormat money) {
        ConfigReader lp = r.section("luckperms");
        Set<String> hidden = new LinkedHashSet<>();
        for (String group : lp.stringList("hidden-groups", List.of("default"))) {
            String clean = StoreRules.group(group);
            if (clean == null) {
                lp.problem("hidden-groups", "'" + group + "' is not a LuckPerms group name");
            } else {
                hidden.add(clean);
            }
        }
        String meta = lp.string("label-meta", "siftcore-rank");
        if (meta.isBlank()) {
            lp.problem("label-meta", "must not be empty");
            meta = "siftcore-rank";
        }
        ConfigReader backups = r.section("backups");
        ConfigReader store = r.section("store");
        Set<String> groups = new LinkedHashSet<>();
        for (String group : store.stringList("rank-groups", List.of("prospector", "baron", "tycoon"))) {
            String clean = StoreRules.group(group);
            if (clean == null) {
                store.problem("rank-groups", "'" + group + "' is not a LuckPerms group name");
            } else {
                groups.add(clean);
            }
        }
        Duration minRank = store.duration("min-rank-duration", Duration.ofMinutes(1), Duration.ofDays(36_500), Duration.ofHours(1));
        Duration maxRank = store.duration("max-rank-duration", Duration.ofMinutes(1), Duration.ofDays(36_500), Duration.ofDays(3_650));
        if (maxRank.compareTo(minRank) < 0) {
            store.problem("max-rank-duration", "must not be shorter than min-rank-duration");
            maxRank = minRank;
        }
        return new IntegrationsSettings(
            r.section("placeholderapi").bool("enabled", true),
            r.section("floodgate").bool("enabled", true),
            new LuckPerms(lp.bool("enabled", true), meta, metaName(lp, "color-meta", "siftcore-rank-color"),
                metaName(lp, "gradient-meta", "siftcore-rank-gradient"), Set.copyOf(hidden)),
            new Backups(backups.duration("interval", Duration.ZERO, Duration.ofDays(30), Duration.ofHours(24)),
                backups.integer("keep", 0, 1000, 7)),
            r.section("audit").integer("page-size", 5, 50, 10),
            r.section("registries").integer("page-size", 5, 100, 20),
            new Store(
                store.money("max-money", money, false, 100_000_000L),
                store.longValue("max-shards", 1, Long.MAX_VALUE / 4, 1_000_000L),
                store.integer("max-keys", 1, 1_000_000, 1_000),
                Set.copyOf(groups),
                minRank,
                maxRank,
                store.bool("notify-player", true),
                store.bool("announce", false)));
    }
}
