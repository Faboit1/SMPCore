package net.siftvanilla.siftcore.feature.economy;

import java.time.Duration;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/** Parsed {@code features/economy.yml}. */
public record EconomySettings(
    long payMinimum,
    long payConfirmAbove,
    Duration payCooldown,
    boolean payOfflineTargets,
    boolean dailyLimitEnabled,
    long dailyLimitBase,
    long dailyLimitPerHour,
    long dailyLimitMaximum,
    Duration topRefresh,
    int topSize,
    int pageSize) {

    public static EconomySettings parse(ConfigReader r, MoneyFormat money) {
        ConfigReader pay = r.section("pay");
        ConfigReader limit = pay.section("daily-limit");
        ConfigReader top = r.section("baltop");
        return new EconomySettings(
            pay.money("minimum", money, false, 1),
            pay.money("confirm-above", money, true, 100_000),
            pay.duration("cooldown", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(2)),
            pay.bool("allow-offline-targets", true),
            limit.bool("enabled", true),
            limit.money("base", money, true, 250_000),
            limit.money("per-hour-played", money, true, 50_000),
            limit.money("maximum", money, false, 100_000_000),
            top.duration("refresh", Duration.ofSeconds(10), Duration.ofHours(1), Duration.ofSeconds(60)),
            top.integer("size", 10, 1000, 100),
            r.integer("page-size", 5, 20, 10));
    }
}
