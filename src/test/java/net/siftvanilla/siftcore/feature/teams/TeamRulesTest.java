package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The role permission matrix and member limits. */
class TeamRulesTest {

    private static final TeamRole OWNER = TeamRole.OWNER;
    private static final TeamRole ADMIN = TeamRole.ADMIN;
    private static final TeamRole MEMBER = TeamRole.MEMBER;

    @Test
    void roleOrderAndStoredIds() {
        assertTrue(OWNER.outranks(ADMIN));
        assertTrue(ADMIN.outranks(MEMBER));
        assertFalse(ADMIN.outranks(ADMIN));
        assertTrue(ADMIN.atLeast(ADMIN));
        assertFalse(MEMBER.atLeast(ADMIN));
        assertEquals("owner", OWNER.id());
        assertEquals(ADMIN, TeamRole.byId(" Admin "));
        assertEquals(MEMBER, TeamRole.byId("superuser"), "unknown roles must never grant power");
        assertEquals(MEMBER, TeamRole.byId(null));
    }

    @Test
    void invitesAreForAdminsAndTheOwner() {
        assertNull(TeamRules.invite(OWNER));
        assertNull(TeamRules.invite(ADMIN));
        assertEquals(TeamProblem.ADMINS_ONLY, TeamRules.invite(MEMBER));
        assertEquals(TeamProblem.ADMINS_ONLY, TeamRules.invite(null));
    }

    @Test
    void kickNeedsAHigherRank() {
        assertNull(TeamRules.kick(OWNER, ADMIN));
        assertNull(TeamRules.kick(OWNER, MEMBER));
        assertNull(TeamRules.kick(ADMIN, MEMBER));
        assertEquals(TeamProblem.RANK_TOO_HIGH, TeamRules.kick(ADMIN, ADMIN));
        assertEquals(TeamProblem.RANK_TOO_HIGH, TeamRules.kick(ADMIN, OWNER));
        assertEquals(TeamProblem.RANK_TOO_HIGH, TeamRules.kick(OWNER, OWNER));
        assertEquals(TeamProblem.ADMINS_ONLY, TeamRules.kick(MEMBER, MEMBER));
        assertEquals(TeamProblem.TARGET_NOT_MEMBER, TeamRules.kick(OWNER, null));
    }

    @Test
    void onlyTheOwnerPromotesAndDemotes() {
        assertNull(TeamRules.promote(OWNER, MEMBER));
        assertEquals(TeamProblem.ALREADY_ADMIN, TeamRules.promote(OWNER, ADMIN));
        assertEquals(TeamProblem.RANK_TOO_HIGH, TeamRules.promote(OWNER, OWNER));
        assertEquals(TeamProblem.OWNER_ONLY, TeamRules.promote(ADMIN, MEMBER));
        assertEquals(TeamProblem.OWNER_ONLY, TeamRules.promote(MEMBER, MEMBER));
        assertEquals(TeamProblem.TARGET_NOT_MEMBER, TeamRules.promote(OWNER, null));

        assertNull(TeamRules.demote(OWNER, ADMIN));
        assertEquals(TeamProblem.NOT_ADMIN, TeamRules.demote(OWNER, MEMBER));
        assertEquals(TeamProblem.RANK_TOO_HIGH, TeamRules.demote(OWNER, OWNER));
        assertEquals(TeamProblem.OWNER_ONLY, TeamRules.demote(ADMIN, ADMIN));
        assertEquals(TeamProblem.TARGET_NOT_MEMBER, TeamRules.demote(OWNER, null));
    }

    @Test
    void transferIsOwnerOnlyToAnotherMember() {
        assertNull(TeamRules.transfer(OWNER, MEMBER, false));
        assertNull(TeamRules.transfer(OWNER, ADMIN, false));
        assertEquals(TeamProblem.NOT_YOURSELF, TeamRules.transfer(OWNER, OWNER, true));
        assertEquals(TeamProblem.TARGET_NOT_MEMBER, TeamRules.transfer(OWNER, null, false));
        assertEquals(TeamProblem.OWNER_ONLY, TeamRules.transfer(ADMIN, MEMBER, false));
        assertEquals(TeamProblem.OWNER_ONLY, TeamRules.transfer(MEMBER, ADMIN, false));
    }

    @Test
    void disbandHomeFriendlyFireAndLeave() {
        assertNull(TeamRules.disband(OWNER));
        assertEquals(TeamProblem.OWNER_ONLY, TeamRules.disband(ADMIN));
        assertEquals(TeamProblem.OWNER_ONLY, TeamRules.disband(MEMBER));

        assertNull(TeamRules.setHome(OWNER));
        assertNull(TeamRules.setHome(ADMIN));
        assertEquals(TeamProblem.ADMINS_ONLY, TeamRules.setHome(MEMBER));

        assertNull(TeamRules.setHome(OWNER, false, false));
        assertEquals(TeamProblem.HOME_IN_SPAWN, TeamRules.setHome(OWNER, false, true), "the /sethome spawn rule applies");
        assertEquals(TeamProblem.HOME_WORLD_DISABLED, TeamRules.setHome(ADMIN, true, false), "and the disabled worlds");
        assertEquals(TeamProblem.HOME_WORLD_DISABLED, TeamRules.setHome(ADMIN, true, true));
        assertEquals(TeamProblem.ADMINS_ONLY, TeamRules.setHome(MEMBER, true, true), "the role is told first");

        assertNull(TeamRules.useHome(false, false));
        assertEquals(TeamProblem.HOME_AT_SPAWN, TeamRules.useHome(false, true), "a home set at spawn before the rule can't be used");
        assertEquals(TeamProblem.HOME_WORLD_DISABLED, TeamRules.useHome(true, false), "nor one in a disabled world");
        assertEquals(TeamProblem.HOME_WORLD_DISABLED, TeamRules.useHome(true, true));

        assertNull(TeamRules.friendlyFire(OWNER));
        assertNull(TeamRules.friendlyFire(ADMIN));
        assertEquals(TeamProblem.ADMINS_ONLY, TeamRules.friendlyFire(MEMBER));

        assertEquals(TeamProblem.OWNER_CANT_LEAVE, TeamRules.leave(OWNER));
        assertNull(TeamRules.leave(ADMIN));
        assertNull(TeamRules.leave(MEMBER));
    }

    @Test
    void memberLimitIsTheOwnersRankButNeverBelowTheDefault() {
        assertEquals(5, TeamRules.memberLimit(0, 5), "no rank limit known: the default");
        assertEquals(5, TeamRules.memberLimit(3, 5), "a rank limit below the default does not shrink the team");
        assertEquals(10, TeamRules.memberLimit(10, 5));
        assertEquals(TeamRules.UNLIMITED, TeamRules.memberLimit(TeamRules.UNLIMITED, 5));
        assertEquals(1, TeamRules.memberLimit(0, 0), "a team always fits its owner");
    }

    @Test
    void roomForOneMore() {
        assertTrue(TeamRules.hasRoom(4, 5));
        assertFalse(TeamRules.hasRoom(5, 5));
        assertFalse(TeamRules.hasRoom(7, 5), "a team over its limit (rank expired) takes nobody new");
        assertTrue(TeamRules.hasRoom(1_000_000, TeamRules.UNLIMITED));
    }
}
