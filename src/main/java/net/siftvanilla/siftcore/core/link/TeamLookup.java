package net.siftvanilla.siftcore.core.link;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only view of teams. Implemented by the teams feature; thread-safe. */
public interface TeamLookup {

    TeamLookup NONE = new TeamLookup() {
        @Override
        public Optional<Long> team(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<String> teamName(UUID player) {
            return Optional.empty();
        }

        @Override
        public boolean friendlyFire(long team) {
            return true;
        }

        @Override
        public Set<UUID> members(long team) {
            return Set.of();
        }
    };

    /** The id of the player's team. */
    Optional<Long> team(UUID player);

    /** The display name of the player's team. */
    Optional<String> teamName(UUID player);

    /** Whether members of the team can hurt each other. */
    boolean friendlyFire(long team);

    Set<UUID> members(long team);

    default boolean sameTeam(UUID a, UUID b) {
        Optional<Long> team = team(a);
        return team.isPresent() && team.equals(team(b));
    }
}
