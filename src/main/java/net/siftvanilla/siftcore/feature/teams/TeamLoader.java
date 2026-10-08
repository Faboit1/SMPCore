package net.siftvanilla.siftcore.feature.teams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns stored rows into team snapshots at startup. The service keeps the tables consistent, so on a healthy
 * database this changes nothing; if rows were edited by hand or a write was lost it repairs the data instead of
 * refusing to start: memberships of missing teams and empty teams are removed, a team whose owner row is missing
 * gets the longest-standing remaining member (admins first) as owner, and roles are normalized.
 */
public final class TeamLoader {

    /** A fix to write back. */
    public sealed interface Repair {

        record DeleteMember(UUID player, long team) implements Repair {
        }

        record DeleteTeam(long team) implements Repair {
        }

        record SetOwner(long team, UUID owner) implements Repair {
        }

        record SetRole(long team, UUID player, TeamRole role) implements Repair {
        }
    }

    /** The teams, the repairs that make storage match them, and a note per repair for the log. */
    public record Result(List<Team> teams, List<Repair> repairs, List<String> notes) {
    }

    private static final Comparator<TeamStore.MemberRow> SENIORITY = Comparator
        .comparingLong(TeamStore.MemberRow::joined)
        .thenComparing(row -> row.uuid().toString());

    private TeamLoader() {
    }

    public static Result assemble(List<TeamStore.TeamRow> rows, List<TeamStore.MemberRow> memberRows) {
        Map<Long, TeamStore.TeamRow> teamsById = new LinkedHashMap<>();
        for (TeamStore.TeamRow row : rows) {
            teamsById.put(row.id(), row);
        }
        List<Repair> repairs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<Long, List<TeamStore.MemberRow>> grouped = new HashMap<>();
        for (TeamStore.MemberRow member : memberRows) {
            if (!teamsById.containsKey(member.team())) {
                repairs.add(new Repair.DeleteMember(member.uuid(), member.team()));
                notes.add("removed the membership of " + member.uuid() + " in team " + member.team() + ", which does not exist");
                continue;
            }
            grouped.computeIfAbsent(member.team(), k -> new ArrayList<>()).add(member);
        }
        List<Team> teams = new ArrayList<>(teamsById.size());
        for (TeamStore.TeamRow row : teamsById.values()) {
            List<TeamStore.MemberRow> members = grouped.getOrDefault(row.id(), List.of());
            if (members.isEmpty()) {
                repairs.add(new Repair.DeleteTeam(row.id()));
                notes.add("removed team " + row.id() + " (" + row.name() + ") because it has no members");
                continue;
            }
            UUID owner = row.owner();
            boolean ownerPresent = members.stream().anyMatch(m -> m.uuid().equals(row.owner()));
            if (!ownerPresent) {
                owner = successor(members);
                repairs.add(new Repair.SetOwner(row.id(), owner));
                notes.add("team " + row.id() + " (" + row.name() + ") had no owner membership; " + owner + " is the owner now");
            }
            Map<UUID, TeamMember> built = new HashMap<>();
            for (TeamStore.MemberRow member : members) {
                TeamRole stored = TeamRole.byId(member.role());
                TeamRole role = member.uuid().equals(owner) ? TeamRole.OWNER
                    : stored == TeamRole.OWNER ? TeamRole.ADMIN : stored;
                if (!role.id().equals(member.role())) {
                    repairs.add(new Repair.SetRole(row.id(), member.uuid(), role));
                    notes.add("set the role of " + member.uuid() + " in team " + row.id() + " from '" + member.role() + "' to " + role.id());
                }
                built.put(member.uuid(), new TeamMember(member.uuid(), role, member.joined()));
            }
            teams.add(new Team(row.id(), row.name(), owner, row.created(), row.friendlyFire(), row.home(), built, row.rankLimit()));
        }
        return new Result(List.copyOf(teams), List.copyOf(repairs), List.copyOf(notes));
    }

    /** The member who should own a team whose owner is gone: a stored owner, else the senior admin, else the senior member. */
    private static UUID successor(List<TeamStore.MemberRow> members) {
        for (TeamRole role : List.of(TeamRole.OWNER, TeamRole.ADMIN, TeamRole.MEMBER)) {
            UUID best = members.stream()
                .filter(m -> TeamRole.byId(m.role()) == role)
                .min(SENIORITY)
                .map(TeamStore.MemberRow::uuid)
                .orElse(null);
            if (best != null) {
                return best;
            }
        }
        return members.stream().min(SENIORITY).orElseThrow().uuid();
    }
}
