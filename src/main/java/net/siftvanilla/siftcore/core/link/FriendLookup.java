package net.siftvanilla.siftcore.core.link;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Friendships. Implemented by the friends feature; used by TPA (auto-accept from friends), combat (no kill credit or
 * bounty claims between friends), chat and the scoreboard. Thread-safe and cheap: answers from memory, exact for
 * online players.
 */
public interface FriendLookup {

    FriendLookup NONE = new FriendLookup() {
        @Override
        public boolean friends(UUID a, UUID b) {
            return false;
        }

        @Override
        public Set<UUID> friendsOf(UUID player) {
            return Set.of();
        }
    };

    /** True when the two players are friends. */
    boolean friends(UUID a, UUID b);

    /** An immutable snapshot of the player's friends (empty when unknown). */
    Set<UUID> friendsOf(UUID player);

    /**
     * True when the two players are friends now or stopped being friends within {@code window} (the friends feature
     * remembers removals for a limited time, 7 days by default). Use it for anti-farm rules, so removing a friend,
     * killing them and adding them back gives no credit.
     */
    default boolean recentlyFriends(UUID a, UUID b, Duration window) {
        return friends(a, b);
    }

    /**
     * True when a plain teleport request from {@code requester} to {@code target} should be accepted without asking,
     * because they are friends and the target chose to auto-accept requests from (favourite) friends. Call it on the
     * requester's thread, for plain {@code /tpa} only (never {@code /tpahere}).
     */
    default boolean autoAcceptTeleport(UUID target, UUID requester) {
        return false;
    }
}
