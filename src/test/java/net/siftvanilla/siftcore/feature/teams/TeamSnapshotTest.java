package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The team snapshot's invariants and ownership transfer rules. */
class TeamSnapshotTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    private Team team() {
        return Team.create(7, "Testers", this.owner, 100, false)
            .withMember(new TeamMember(this.admin, TeamRole.ADMIN, 200))
            .withMember(new TeamMember(this.member, TeamRole.MEMBER, 300));
    }

    @Test
    void theOwnerMustBeAMemberWithTheOwnerRole() {
        assertThrows(IllegalArgumentException.class, () -> new Team(1, "x", this.owner, 0, false, null, Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new Team(1, "x", this.owner, 0, false, null,
            Map.of(this.owner, new TeamMember(this.owner, TeamRole.ADMIN, 0)), 0));
        assertThrows(IllegalArgumentException.class, () -> new Team(1, "x", this.owner, 0, false, null,
            Map.of(this.owner, new TeamMember(this.owner, TeamRole.OWNER, 0),
                this.admin, new TeamMember(this.admin, TeamRole.OWNER, 0)), 0), "two owners");
    }

    @Test
    void transferMakesTheOldOwnerAnAdmin() {
        Team after = team().withOwnerRankLimit(12).withOwner(this.member, 0);
        assertEquals(this.member, after.owner());
        assertEquals(TeamRole.OWNER, after.role(this.member));
        assertEquals(TeamRole.ADMIN, after.role(this.owner));
        assertEquals(TeamRole.ADMIN, after.role(this.admin));
        assertEquals(0, after.ownerRankLimit(), "the old owner's rank limit does not carry over");
        assertEquals(3, after.size());
    }

    @Test
    void transferNeedsAnotherMember() {
        Team team = team();
        assertThrows(IllegalArgumentException.class, () -> team.withOwner(this.owner, 0));
        assertThrows(IllegalArgumentException.class, () -> team.withOwner(UUID.randomUUID(), 0));
    }

    @Test
    void theOwnerCannotBeRemovedOrRoleChanged() {
        Team team = team();
        assertThrows(IllegalArgumentException.class, () -> team.withoutMember(this.owner));
        assertThrows(IllegalArgumentException.class, () -> team.withRole(this.owner, TeamRole.MEMBER));
        assertThrows(IllegalArgumentException.class, () -> team.withRole(this.member, TeamRole.OWNER));
        assertThrows(IllegalArgumentException.class, () -> team.withMember(new TeamMember(UUID.randomUUID(), TeamRole.OWNER, 0)));
    }

    @Test
    void changesBuildNewSnapshots() {
        Team team = team();
        Team promoted = team.withRole(this.member, TeamRole.ADMIN);
        assertEquals(TeamRole.MEMBER, team.role(this.member), "the old snapshot is unchanged");
        assertEquals(TeamRole.ADMIN, promoted.role(this.member));
        Team smaller = team.withoutMember(this.member);
        assertFalse(smaller.isMember(this.member));
        assertNull(smaller.role(this.member));
        assertTrue(team.isMember(this.member));
        assertThrows(UnsupportedOperationException.class, () -> team.memberIds().clear());
    }

    @Test
    void membersAreListedOwnerFirstThenAdminsThenBySeniority() {
        UUID early = UUID.randomUUID();
        Team team = team().withMember(new TeamMember(early, TeamRole.MEMBER, 250));
        List<UUID> order = team.sortedMembers().stream().map(TeamMember::uuid).toList();
        assertEquals(List.of(this.owner, this.admin, early, this.member), order);
    }

    @Test
    void homeAndFlags() {
        Team team = team().withHome(new TeamHome("world", 10.7, 64, -3.2, 90f, 0f)).withFriendlyFire(true).withName("Renamed");
        assertEquals(10, team.home().blockX());
        assertEquals(-4, team.home().blockZ());
        assertTrue(team.friendlyFire());
        assertEquals("Renamed", team.name());
        assertNull(team.withHome(null).home());
        assertThrows(IllegalArgumentException.class, () -> new TeamHome("world", Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeamHome(" ", 0, 0, 0, 0, 0));
    }
}
