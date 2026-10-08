package net.siftvanilla.siftcore.core.link;

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
}
