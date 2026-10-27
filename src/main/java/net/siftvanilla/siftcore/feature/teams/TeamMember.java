package net.siftvanilla.siftcore.feature.teams;

import java.util.Objects;
import java.util.UUID;

/** One member of a team: who, their role, and when they joined (epoch millis). */
public record TeamMember(UUID uuid, TeamRole role, long joined) {

    public TeamMember {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(role);
    }

    public TeamMember withRole(TeamRole newRole) {
        return new TeamMember(this.uuid, newRole, this.joined);
    }
}
