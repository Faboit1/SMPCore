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
