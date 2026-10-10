package net.siftvanilla.siftcore.feature.boosters;

import java.time.Duration;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.ServerBoosters;

/**
 * Parsed {@code features/boosters.yml}.
 *
 * @param maxPercent  the largest percent a sell booster may raise prices by (the shop prices itself against it)
 * @param minDuration the shortest booster
 * @param maxDuration the longest booster
 * @param queueLimit  the most boosters that may wait at once for staff to add another (store boosters always queue)
 * @param shown       waiting boosters listed by {@code /booster}
 * @param announce    which chat announcements are sent
 * @param bar         the boss bar
 */
record BoostersSettings(int maxPercent, Duration minDuration, Duration maxDuration, int queueLimit, int shown, Announce announce,
                        Bar bar) {

    /** The highest {@code sell.max-percent} the config accepts. */
    static final int PERCENT_CAP = ServerBoosters.PERCENT_CAP;

    /** A booster the web store sells (docs/monetization.md). */
    record StorePackage(String name, int percent, Duration length) {
    }

    /** The booster packages of docs/monetization.md, which the self-test checks against the config. */
    static final List<StorePackage> STORE_PACKAGES = List.of(
        new StorePackage("Sell Frenzy", 15, Duration.ofMinutes(30)),
        new StorePackage("Sell Frenzy XL", 15, Duration.ofHours(2)),
        new StorePackage("the Tycoon thank-you booster", 10, Duration.ofMinutes(30)),
        new StorePackage("the community goal weekend", 10, Duration.ofHours(48)));

    /** Chat announcements to everyone online. */
    record Announce(boolean started, boolean queued, boolean ended) {
    }

    /** The boss bar shown while a booster runs. */
    record Bar(boolean enabled, BossBar.Color color, BossBar.Overlay overlay) {
    }

    /** Why a booster can't be started with these settings, or null: {@code bad_percent}, {@code bad_duration}. */
    String problem(int percent, Duration duration) {
        if (percent < 1 || percent > this.maxPercent) {
            return "bad_percent";
        }
        if (duration == null || duration.compareTo(this.minDuration) < 0 || duration.compareTo(this.maxDuration) > 0) {
            return "bad_duration";
        }
        return null;
    }

    /**
     * Why one of the store's booster packages would not arrive, or would pay less than was bought, with these settings;
     * null when every package arrives and pays in full.
     */
    String packagesProblem() {
        for (StorePackage pack : STORE_PACKAGES) {
            String problem = BoosterService.storeProblem(pack.percent(), pack.length());
            if (problem != null) {
                return pack.name() + " (+" + pack.percent() + "% for " + pack.length() + ") can't be delivered: " + problem;
            }
            if (pack.percent() > this.maxPercent) {
                return pack.name() + " is sold as +" + pack.percent() + "% but pays only +" + this.maxPercent
                    + "% (sell.max-percent in boosters.yml)";
            }
        }
        return null;
    }

    static BoostersSettings parse(ConfigReader r) {
        ConfigReader sell = r.section("sell");
        int maxPercent = sell.integer("max-percent", 1, PERCENT_CAP, 25);
        Duration min = sell.duration("min-duration", Duration.ofMinutes(1), Duration.ofDays(30), Duration.ofMinutes(1));
        Duration max = sell.duration("max-duration", Duration.ofMinutes(1), Duration.ofDays(30), Duration.ofDays(3));
        if (max.compareTo(min) < 0) {
            sell.problem("max-duration", "must not be shorter than min-duration");
            max = min;
        }
        ConfigReader queue = r.section("queue");
        ConfigReader announce = r.section("announce");
        ConfigReader bar = r.section("bar");
        BossBar.Color color = bar.enumValue("color", BossBar.Color.class, BossBar.Color.GREEN);
        BossBar.Overlay overlay = bar.enumValue("style", BossBar.Overlay.class, BossBar.Overlay.PROGRESS);
        return new BoostersSettings(maxPercent, min, max,
            queue.integer("staff-limit", 1, 1_000, 20),
            queue.integer("shown", 1, 20, 5),
            new Announce(announce.bool("started", true), announce.bool("queued", true), announce.bool("ended", true)),
            new Bar(bar.bool("enabled", true), color, overlay));
    }
}
