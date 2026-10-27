package net.siftvanilla.siftcore.feature.tpa;

import java.time.Duration;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code features/tpa.yml}. */
record TpaSettings(Duration expireAfter, Duration warmup, Duration requestCooldown) {

    static TpaSettings parse(ConfigReader r) {
        return new TpaSettings(
            r.duration("expire-after", Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(60)),
            r.duration("warmup", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(3)),
            r.duration("request-cooldown", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(5)));
    }
}
