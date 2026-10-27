package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Assembling stored rows into teams, and repairing rows that cannot be right. */
class TeamLoaderTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    private static TeamStore.TeamRow row(long id, UUID owner) {
        return new TeamStore.TeamRow(id, "Team" + id, owner, 1_000, false, null, 0);
    }

    @Test
    void healthyRowsNeedNoRepair() {
        TeamLoader.Result result = TeamLoader.assemble(List.of(row(1, this.owner)), List.of(
            new TeamStore.MemberRow(this.owner, 1, "owner", 1),
            new TeamStore.MemberRow(this.admin, 1, "admin", 2),
            new TeamStore.MemberRow(this.member, 1, "member", 3)));
        assertTrue(result.repairs().isEmpty());
        assertEquals(1, result.teams().size());
        Team team = result.teams().getFirst();
        assertEquals(this.owner, team.owner());
        assertEquals(TeamRole.ADMIN, team.role(this.admin));
        assertEquals(3, team.size());
    }

    @Test
    void membershipsOfMissingTeamsAndEmptyTeamsAreRemoved() {
        TeamLoader.Result result = TeamLoader.assemble(List.of(row(1, this.owner), row(2, this.admin)), List.of(
            new TeamStore.MemberRow(this.owner, 1, "owner", 1),
            new TeamStore.MemberRow(this.member, 9, "member", 3)));
        assertEquals(List.of(1L), result.teams().stream().map(Team::id).toList());
        assertTrue(result.repairs().contains(new TeamLoader.Repair.DeleteMember(this.member, 9)));
        assertTrue(result.repairs().contains(new TeamLoader.Repair.DeleteTeam(2)));
        assertEquals(2, result.notes().size());
    }

    @Test
    void aTeamWithoutItsOwnerGoesToTheSeniorAdmin() {
        UUID juniorAdmin = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        TeamLoader.Result result = TeamLoader.assemble(List.of(row(1, gone)), List.of(
            new TeamStore.MemberRow(this.member, 1, "member", 1),
            new TeamStore.MemberRow(juniorAdmin, 1, "admin", 5),
            new TeamStore.MemberRow(this.admin, 1, "admin", 3)));
        Team team = result.teams().getFirst();
        assertEquals(this.admin, team.owner(), "the longest-standing admin takes over");
        assertTrue(result.repairs().contains(new TeamLoader.Repair.SetOwner(1, this.admin)));
        assertTrue(result.repairs().contains(new TeamLoader.Repair.SetRole(1, this.admin, TeamRole.OWNER)));
        assertEquals(TeamRole.ADMIN, team.role(juniorAdmin));
    }

    @Test
    void withoutAdminsTheSeniorMemberTakesOver() {
        UUID gone = UUID.randomUUID();
        UUID junior = UUID.randomUUID();
        TeamLoader.Result result = TeamLoader.assemble(List.of(row(1, gone)), List.of(
            new TeamStore.MemberRow(junior, 1, "member", 9),
            new TeamStore.MemberRow(this.member, 1, "member", 2)));
        assertEquals(this.member, result.teams().getFirst().owner());
    }

    @Test
    void extraOwnersAndUnknownRolesAreNormalized() {
        TeamLoader.Result result = TeamLoader.assemble(List.of(row(1, this.owner)), List.of(
            new TeamStore.MemberRow(this.owner, 1, "owner", 1),
            new TeamStore.MemberRow(this.admin, 1, "owner", 2),
            new TeamStore.MemberRow(this.member, 1, "boss", 3)));
        Team team = result.teams().getFirst();
        assertEquals(TeamRole.ADMIN, team.role(this.admin), "a second owner becomes an admin");
        assertEquals(TeamRole.MEMBER, team.role(this.member), "an unknown role reads as member");
        assertTrue(result.repairs().contains(new TeamLoader.Repair.SetRole(1, this.admin, TeamRole.ADMIN)));
        assertTrue(result.repairs().contains(new TeamLoader.Repair.SetRole(1, this.member, TeamRole.MEMBER)));
        assertEquals(2, result.repairs().size());
    }
}
