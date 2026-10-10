package net.siftvanilla.siftcore.core.link;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.player.options.Audience;
import org.junit.jupiter.api.Test;

class RelationsTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();
    private static final UUID MATE = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static FriendLookup friends(boolean favourites) {
        return new FriendLookup() {
            @Override
            public boolean friends(UUID a, UUID b) {
                return (a.equals(OWNER) && b.equals(FRIEND)) || (a.equals(FRIEND) && b.equals(OWNER));
            }

            @Override
            public Set<UUID> friendsOf(UUID player) {
                return player.equals(OWNER) ? Set.of(FRIEND) : Set.of();
            }

            @Override
            public boolean favouritesEnabled() {
                return favourites;
            }
        };
    }

    private static final TeamLookup TEAMS = new TeamLookup() {
        @Override
        public Optional<Long> team(UUID player) {
            return player.equals(OWNER) || player.equals(MATE) ? Optional.of(7L) : Optional.empty();
        }

        @Override
        public Optional<String> teamName(UUID player) {
            return team(player).map(id -> "Seven");
        }

        @Override
        public boolean friendlyFire(long team) {
            return false;
        }

        @Override
        public Set<UUID> members(long team) {
            return Set.of(OWNER, MATE);
        }
    };

    @Test
    void theAudienceRule() {
        assertTrue(Relations.allows(Audience.EVERYONE, false, false));
        assertFalse(Relations.allows(Audience.NOBODY, true, true));
        assertTrue(Relations.allows(Audience.FRIENDS, true, false));
        assertFalse(Relations.allows(Audience.FRIENDS, false, true), "teammates are not friends");
        assertTrue(Relations.allows(Audience.FRIENDS_TEAM, false, true));
        assertTrue(Relations.allows(Audience.FRIENDS_TEAM, true, false));
        assertFalse(Relations.allows(Audience.FRIENDS_TEAM, false, false));
    }

    @Test
    void lookupsAreBoundLateAndReadAsAbsentUntilThen() {
        Relations relations = new Relations();
        assertFalse(relations.friendsAvailable());
        assertFalse(relations.favouritesAvailable());
        assertFalse(relations.allows(Audience.FRIENDS, OWNER, FRIEND), "no friends system yet");
        assertTrue(relations.allows(Audience.NOBODY, OWNER, OWNER), "a player always belongs to their own audience");
        IgnoreLookup ignores = (player, other) -> player.equals(OWNER) && other.equals(STRANGER);
        FriendLookup lookup = friends(true);
        relations.bind(lookup, TEAMS, ignores);
        assertSame(lookup, relations.friends());
        assertTrue(relations.friendsAvailable());
        assertTrue(relations.favouritesAvailable());
        assertTrue(relations.allows(Audience.FRIENDS, OWNER, FRIEND));
        assertFalse(relations.allows(Audience.FRIENDS, OWNER, MATE));
        assertTrue(relations.allows(Audience.FRIENDS_TEAM, OWNER, MATE));
        assertFalse(relations.allows(Audience.FRIENDS_TEAM, OWNER, STRANGER));
        assertTrue(relations.allows(Audience.EVERYONE, OWNER, STRANGER), "ignoring is checked separately");
        assertTrue(relations.ignores(OWNER, STRANGER));
        assertFalse(relations.ignores(STRANGER, OWNER));
        assertTrue(relations.sameTeam(OWNER, MATE));
        relations.bind(friends(false), TEAMS, ignores);
        assertFalse(relations.favouritesAvailable(), "favourites follow the friends feature's switch");
    }
}
