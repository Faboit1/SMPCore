package net.siftvanilla.siftcore.feature.bounties;

import java.time.Duration;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Parsed {@code features/bounties.yml}.
 *
 * @param taxPercent part of a claimed bounty destroyed as tax (0, the shipped value, means no tax and no tax text)
 * @param listSize   how many of the biggest bounties the list dialog and {@code /bounties} show
 */
record BountiesSettings(
    long minimum,
    long confirmAbove,
    Duration placeCooldown,
    boolean announcePlacements,
    long announceAbove,
    boolean notifyTarget,
    boolean remindOnJoin,
    int taxPercent,
    boolean announceClaims,
    boolean notifySponsors,
    Duration expireAfter,
    Duration expiryCheck,
    int listSize) {

    /** The highest claim tax; more would leave killers next to nothing. */
    static final int MAX_TAX_PERCENT = 90;
    /** The most bounties the list shows (it scrolls, so a long list is fine; the rest are smaller). */
    static final int MAX_LIST_SIZE = 100;

    static BountiesSettings parse(ConfigReader r, MoneyFormat money) {
        ConfigReader place = r.section("place");
        ConfigReader claim = r.section("claim");
        ConfigReader expiry = r.section("expiry");
        return new BountiesSettings(
            place.money("minimum", money, false, 1_000),
            place.money("confirm-above", money, true, 100_000),
            place.duration("cooldown", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(5)),
            place.bool("announce", true),
            place.money("announce-above", money, true, 50_000),
            place.bool("notify-target", true),
            place.bool("remind-on-join", true),
            claim.integer("tax-percent", 0, MAX_TAX_PERCENT, 0),
            claim.bool("announce", true),
            claim.bool("notify-sponsors", true),
            expiry.duration("after", Duration.ofHours(1), Duration.ofDays(365), Duration.ofDays(14)),
            expiry.duration("check-every", Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMinutes(5)),
            r.integer("list-size", 1, MAX_LIST_SIZE, 50));
    }
}
