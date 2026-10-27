package net.siftvanilla.siftcore.feature.friends;

/**
 * The numbers the decision table and the write units work with: a snapshot of the config taken when an action
 * starts, so one action never mixes two configs. Pure; no Bukkit.
 *
 * @param expireMillis     how long a request stays open
 * @param denyMemoryMillis how long a deny keeps the sender's new requests hidden
 * @param maxOutgoing      open requests a player may have sent
 * @param maxIncoming      visible open requests a player may receive (more are hidden)
 * @param perDay           requests a player may send in 24 hours
 * @param defaultLimit     friends without a rank node
 * @param hardCap          the most friends anyone may have
 * @param favourites       favourites per player
 */
public record FriendRules(long expireMillis, long denyMemoryMillis, int maxOutgoing, int maxIncoming, int perDay,
                          int defaultLimit, int hardCap, int favourites) {

    /** A rank limit meaning "no limit" (matches {@code Limits.UNLIMITED}); still capped by the hard cap. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /** One day, the window of the daily request cap. */
    public static final long DAY_MILLIS = 86_400_000L;

    /**
     * The friend limit for a player whose rank grants {@code rankLimit} ({@code siftcore.friends.limit.<n>}, 0 when
     * none is known): the higher of that and the default, never above the hard cap.
     */
    public static int effectiveLimit(int rankLimit, int defaultLimit, int hardCap) {
        int cap = Math.max(1, hardCap);
        if (rankLimit == UNLIMITED) {
            return cap;
        }
        return Math.min(cap, Math.max(Math.max(1, defaultLimit), rankLimit));
    }

    /** The friend limit for a stored or freshly read rank limit. */
    public int limit(int rankLimit) {
        return effectiveLimit(rankLimit, this.defaultLimit, this.hardCap);
    }

    /** Requests created before this are expired. */
    public long expiredBefore(long now) {
        return now - this.expireMillis;
    }

    /** Denies decided at or after this are still remembered. */
    public long denyRememberedSince(long now) {
        return now - this.denyMemoryMillis;
    }
}
