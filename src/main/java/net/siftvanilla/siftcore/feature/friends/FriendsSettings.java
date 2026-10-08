package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/**
 * Parsed {@code features/friends.yml}. Read through the holder every time a value is needed, so {@code /sift reload}
 * applies at once.
 *
 * @param defaultLimit       friends a player may have without a rank node
 * @param hardCap            no limit (rank, unlimited node or staff add) goes above this
 * @param favourites         favourites per player (0 turns favourites off)
 * @param expireAfter        how long a request stays open
 * @param denyMemory         how long a denied sender's new requests stay hidden
 * @param maxOutgoing        open requests one player may have sent
 * @param maxIncoming        visible open requests one player may receive; more are hidden
 * @param perMinute          requests per minute per player and per address
 * @param perDay             requests per player in 24 hours
 * @param minAccountAge      how long after their first join a player may send requests
 * @param blockWhileVanished vanished players can't send or accept requests
 * @param summaryDelay       wait after join before the login summary
 * @param joinDelay          wait after join before friends are told, and the window join alerts are batched in
 * @param relogGrace         a player who left less than this ago rejoins without join alerts
 * @param leaveDelay         wait before a leave alert, dropped if the player rejoins
 * @param startupQuiet       no join alerts this long after the plugin starts
 * @param requestBatch       further request alerts within this window are summed into one line
 * @param pageSize           friends per page of the list dialog
 * @param suggestions        "people you may know" buttons (0 turns them off)
 * @param sneakClick         sneak and right-click a player to open their card
 * @param sneakClickCooldown time between two cards opened by sneak-clicking
 * @param memoryGrace        how long a player's friends stay in memory after they leave
 * @param antiFarmRemember   how long removed friendships are remembered for anti-farm checks
 * @param logKeep            how long history rows are kept
 */
public record FriendsSettings(
    int defaultLimit,
    int hardCap,
    int favourites,
    Duration expireAfter,
    Duration denyMemory,
    int maxOutgoing,
    int maxIncoming,
    int perMinute,
    int perDay,
    Duration minAccountAge,
    boolean blockWhileVanished,
    Duration summaryDelay,
    Duration joinDelay,
    Duration relogGrace,
    Duration leaveDelay,
    Duration startupQuiet,
    Duration requestBatch,
    int pageSize,
    int suggestions,
    boolean sneakClick,
    Duration sneakClickCooldown,
    Duration memoryGrace,
    Duration antiFarmRemember,
    Duration logKeep) {

    public static FriendsSettings parse(ConfigReader r) {
        ConfigReader limits = r.section("limits");
        ConfigReader requests = r.section("requests");
        ConfigReader presence = r.section("presence");
        ConfigReader list = r.section("list");
        ConfigReader profile = r.section("profile");
        ConfigReader memory = r.section("memory");
        ConfigReader antiFarm = r.section("anti-farm");
        ConfigReader log = r.section("log");
        int hardCap = limits.integer("hard-cap", 1, 100_000, 500);
        int defaultLimit = limits.integer("default", 1, 100_000, 50);
        if (defaultLimit > hardCap) {
            limits.problem("default", "must not be above hard-cap (" + hardCap + ")");
            defaultLimit = Math.min(50, hardCap);
        }
        Duration remember = antiFarm.duration("remember", Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(7));
        Duration keep = log.duration("keep", Duration.ofDays(1), Duration.ofDays(3650), Duration.ofDays(90));
        if (keep.compareTo(remember) < 0) {
            log.problem("keep", "must be at least anti-farm.remember, because removals are remembered from the history");
            keep = remember;
        }
        return new FriendsSettings(
            defaultLimit,
            hardCap,
            limits.integer("favourites", 0, 1_000, 10),
            requests.duration("expire-after", Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(7)),
            requests.duration("deny-memory", Duration.ZERO, Duration.ofDays(30), Duration.ofDays(7)),
            requests.integer("max-outgoing", 1, 1_000, 20),
            requests.integer("max-incoming", 1, 1_000, 50),
            requests.integer("per-minute", 1, 1_000, 5),
            requests.integer("per-day", 1, 10_000, 30),
            requests.duration("min-account-age", Duration.ZERO, Duration.ofDays(30), Duration.ofMinutes(10)),
            requests.bool("block-while-vanished", true),
            presence.duration("summary-delay", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(3)),
            presence.duration("join-delay", Duration.ofMillis(50), Duration.ofMinutes(1), Duration.ofSeconds(3)),
            presence.duration("relog-grace", Duration.ZERO, Duration.ofHours(1), Duration.ofMinutes(2)),
            presence.duration("leave-delay", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(30)),
            presence.duration("startup-quiet", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(60)),
            presence.duration("request-batch", Duration.ZERO, Duration.ofMinutes(5), Duration.ofSeconds(10)),
            list.integer("page-size", 4, 30, 16),
            list.integer("suggestions", 0, 12, 6),
            profile.bool("sneak-click", true),
            profile.duration("sneak-click-cooldown", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(1)),
            memory.duration("grace", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(60)),
            remember,
            keep);
    }

    /** The limit for a player whose rank grants {@code rankLimit} (0 when none is known), within the hard cap. */
    public int limit(int rankLimit) {
        return FriendRules.effectiveLimit(rankLimit, this.defaultLimit, this.hardCap);
    }

    /**
     * Whether favourites exist at all ({@code limits.favourites} above 0). When they are off, stored favourite flags
     * are ignored everywhere (list order, alerts, sounds) and nothing offers to set one.
     */
    public boolean favouritesOn() {
        return this.favourites > 0;
    }

    /** The rules the write units and the decision table use. */
    public FriendRules rules() {
        return new FriendRules(this.expireAfter.toMillis(), this.denyMemory.toMillis(), this.maxOutgoing, this.maxIncoming,
            this.perDay, this.defaultLimit, this.hardCap, this.favourites);
    }
}
