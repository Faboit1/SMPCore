package net.siftvanilla.siftcore.feature.homes;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code features/homes.yml}. */
record HomesSettings(int defaultLimit, Duration warmup, Duration cooldown, Set<String> disabledWorlds) {

    HomesSettings {
        disabledWorlds = Set.copyOf(disabledWorlds);
    }

    boolean disabled(String world) {
        return this.disabledWorlds.contains(world);
    }

    static HomesSettings parse(ConfigReader r, Predicate<String> worldExists) {
        int limit = r.integer("default-limit", 0, 1_000, 2);
        Duration warmup = r.duration("warmup", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(3));
        Duration cooldown = r.duration("cooldown", Duration.ZERO, Duration.ofHours(1), Duration.ofSeconds(5));
        Set<String> disabled = new LinkedHashSet<>();
        for (String world : r.optionalStringList("disabled-worlds")) {
            if (!worldExists.test(world)) {
                r.problem("disabled-worlds", "contains '" + world + "', but no world with that name is loaded");
            }
            disabled.add(world);
        }
        return new HomesSettings(limit, warmup, cooldown, disabled);
    }
}
