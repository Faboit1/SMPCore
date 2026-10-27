package net.siftvanilla.siftcore.feature.teams;

import java.util.Locale;

/** A member's role. Higher ranks can do everything lower ranks can. */
public enum TeamRole {
    MEMBER(0),
    ADMIN(1),
    OWNER(2);

    private final int rank;

    TeamRole(int rank) {
        this.rank = rank;
    }

    /** The id stored in the database ({@code owner}, {@code admin}, {@code member}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean atLeast(TeamRole other) {
        return this.rank >= other.rank;
    }

    public boolean outranks(TeamRole other) {
        return this.rank > other.rank;
    }

    /** Parses a stored id; unknown values read as {@link #MEMBER} so a bad row never grants power. */
    public static TeamRole byId(String id) {
        if (id != null) {
            for (TeamRole role : values()) {
                if (role.id().equalsIgnoreCase(id.trim())) {
                    return role;
                }
            }
        }
        return MEMBER;
    }
}
