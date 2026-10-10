package net.siftvanilla.siftcore.feature.teams;

/**
 * Who may do what inside a team, and how big a team may get. Pure rules: every method returns the reason an action
 * is refused, or null when it is allowed. The service evaluates them again under the economy lock right before it
 * changes anything, so a rule can never be bypassed by acting on stale state.
 * <ul>
 *   <li>Everyone: leave (not the owner), use the team home, team chat, see the team.</li>
 *   <li>Admins: invite, kick members, set the home, toggle friendly fire.</li>
 *   <li>Owner: everything, plus kick admins, promote and demote, transfer ownership and disband.</li>
 * </ul>
 */
public final class TeamRules {

    /** A member limit meaning "no limit" (matches {@code Limits.UNLIMITED}). */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private TeamRules() {
    }

    public static TeamProblem invite(TeamRole actor) {
        return atLeastAdmin(actor);
    }

    public static TeamProblem kick(TeamRole actor, TeamRole target) {
        TeamProblem problem = atLeastAdmin(actor);
        if (problem != null) {
            return problem;
        }
        if (target == null) {
            return TeamProblem.TARGET_NOT_MEMBER;
        }
        return actor.outranks(target) ? null : TeamProblem.RANK_TOO_HIGH;
    }

    public static TeamProblem promote(TeamRole actor, TeamRole target) {
        if (actor != TeamRole.OWNER) {
            return TeamProblem.OWNER_ONLY;
        }
        if (target == null) {
            return TeamProblem.TARGET_NOT_MEMBER;
        }
        return switch (target) {
            case MEMBER -> null;
            case ADMIN -> TeamProblem.ALREADY_ADMIN;
            case OWNER -> TeamProblem.RANK_TOO_HIGH;
        };
    }

    public static TeamProblem demote(TeamRole actor, TeamRole target) {
        if (actor != TeamRole.OWNER) {
            return TeamProblem.OWNER_ONLY;
        }
        if (target == null) {
            return TeamProblem.TARGET_NOT_MEMBER;
        }
        return switch (target) {
            case ADMIN -> null;
            case MEMBER -> TeamProblem.NOT_ADMIN;
            case OWNER -> TeamProblem.RANK_TOO_HIGH;
        };
    }

    /**
     * Handing the team to another member. The old owner becomes an admin.
     *
     * @param target the target's role in the actor's team, or null when the target is not a member
     * @param self   whether the target is the actor
     */
    public static TeamProblem transfer(TeamRole actor, TeamRole target, boolean self) {
        if (actor != TeamRole.OWNER) {
            return TeamProblem.OWNER_ONLY;
        }
        if (self) {
            return TeamProblem.NOT_YOURSELF;
        }
        return target == null ? TeamProblem.TARGET_NOT_MEMBER : null;
    }

    public static TeamProblem disband(TeamRole actor) {
        return actor == TeamRole.OWNER ? null : TeamProblem.OWNER_ONLY;
    }

    public static TeamProblem setHome(TeamRole actor) {
        return atLeastAdmin(actor);
    }

    /**
     * Setting the home at a spot: admins and the owner, and only where {@code /sethome} would allow a home (not in a
     * world where homes are turned off, not inside the protected spawn area), so members never get a team home where
     * their own homes are refused.
     */
    public static TeamProblem setHome(TeamRole actor, boolean worldDisabled, boolean inSpawn) {
        TeamProblem problem = setHome(actor);
        if (problem != null) {
            return problem;
        }
        if (worldDisabled) {
            return TeamProblem.HOME_WORLD_DISABLED;
        }
        return inSpawn ? TeamProblem.HOME_IN_SPAWN : null;
    }

    /**
     * Using the team home: refused in a world where homes are turned off, and when the home lies inside the protected
     * spawn area (a home set there before the rule, or before the spawn area grew), so a team home never goes where
     * {@code /sethome} refuses a home.
     */
    public static TeamProblem useHome(boolean worldDisabled, boolean inSpawn) {
        if (worldDisabled) {
            return TeamProblem.HOME_WORLD_DISABLED;
        }
        return inSpawn ? TeamProblem.HOME_AT_SPAWN : null;
    }

    public static TeamProblem friendlyFire(TeamRole actor) {
        return atLeastAdmin(actor);
    }

    public static TeamProblem leave(TeamRole actor) {
        return actor == TeamRole.OWNER ? TeamProblem.OWNER_CANT_LEAVE : null;
    }

    private static TeamProblem atLeastAdmin(TeamRole actor) {
        return actor != null && actor.atLeast(TeamRole.ADMIN) ? null : TeamProblem.ADMINS_ONLY;
    }

    /**
     * The member limit of a team: the owner's rank limit ({@code siftcore.teams.size.<n>}, 0 when unknown or none)
     * but never below the configured default.
     */
    public static int memberLimit(int ownerRankLimit, int defaultLimit) {
        if (ownerRankLimit == UNLIMITED || defaultLimit == UNLIMITED) {
            return UNLIMITED;
        }
        return Math.max(Math.max(1, defaultLimit), ownerRankLimit);
    }

    /** Whether one more member fits. */
    public static boolean hasRoom(int members, int limit) {
        return limit == UNLIMITED || members < limit;
    }
}
