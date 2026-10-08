package net.siftvanilla.siftcore.feature.teams;

/** Why a team action was refused. Each one maps to exactly one message. */
public enum TeamProblem {
    /** The actor is not in a team. */
    NOT_IN_TEAM,
    /** The actor is already in a team. */
    ALREADY_IN_TEAM,
    /** Only the owner may do this. */
    OWNER_ONLY,
    /** Only the owner and admins may do this. */
    ADMINS_ONLY,
    /** The target is the actor. */
    NOT_YOURSELF,
    /** The target is not a member of the actor's team. */
    TARGET_NOT_MEMBER,
    /** The target is in another team. */
    TARGET_IN_TEAM,
    /** The target is already in the actor's team. */
    TARGET_ALREADY_MEMBER,
    /** The target's role is equal to or higher than the actor's. */
    RANK_TOO_HIGH,
    /** Promoting someone who is already an admin. */
    ALREADY_ADMIN,
    /** Demoting someone who is not an admin. */
    NOT_ADMIN,
    /** The owner tried to leave. */
    OWNER_CANT_LEAVE,
    /** The actor's team has no room for another member. */
    TEAM_FULL,
    /** The team the player tries to join has no room. */
    JOIN_TEAM_FULL,
    /** The team no longer exists. */
    TEAM_GONE,
    /** There is no invite from that team. */
    NO_INVITE,
    /** The invite ran out. */
    INVITE_EXPIRED,
    /** The target already has an open invite from this team. */
    ALREADY_INVITED,
    /** The team has too many open invites. */
    TOO_MANY_INVITES,
    /** The team has no home. */
    NO_HOME,
    /** The team home's world is not loaded. */
    HOME_WORLD_MISSING,
    NAME_TOO_SHORT,
    NAME_TOO_LONG,
    NAME_CHARACTERS,
    NAME_BLOCKED,
    NAME_TAKEN,
    /** The new name is the current name. */
    NAME_UNCHANGED,
    NOT_ENOUGH_MONEY,
    /** The cost to start a team changed after the player saw it (a reload); nothing was charged. */
    COST_CHANGED,
    ECONOMY_UNAVAILABLE,
    /** Another plugin cancelled the action. */
    CANCELLED,
    /** Nothing to change (for example friendly fire is already in that state). */
    UNCHANGED,
    /** An unexpected failure; nothing changed. */
    FAILED
}
