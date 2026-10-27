package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Team leaderboards: sums over members, order, ties, and unranked teams. */
class TeamTopTest {

    private final Map<UUID, Long> kills = new HashMap<>();
    private final Map<UUID, Long> money = new HashMap<>();

    private Team team(long id, String name, long... memberKills) {
        UUID owner = UUID.randomUUID();
        Team team = Team.create(id, name, owner, 0, false);
        this.kills.put(owner, memberKills[0]);
        this.money.put(owner, memberKills[0] * 1000);
        for (int i = 1; i < memberKills.length; i++) {
            UUID member = UUID.randomUUID();
            team = team.withMember(new TeamMember(member, TeamRole.MEMBER, i));
            this.kills.put(member, memberKills[i]);
            this.money.put(member, memberKills[i] * 1000);
        }
        return team;
    }

    @Test
    void boardsRankBySumThenName() {
        Team alpha = team(1, "Alpha", 5, 5);
        Team bravo = team(2, "bravo", 3, 7);
        Team charlie = team(3, "Charlie", 20);
        Team empty = team(4, "Empty", 0, 0);
        TeamTop top = new TeamTop();
        top.refresh(List.of(alpha, bravo, charlie, empty), m -> this.kills.getOrDefault(m, 0L), m -> this.money.getOrDefault(m, 0L), 10, 42);
        List<TeamTop.Entry> board = top.top(TeamTop.Board.KILLS);
        assertEquals(List.of("Charlie", "Alpha", "bravo"), board.stream().map(TeamTop.Entry::name).toList(),
            "ties are broken by name, ignoring case");
        assertEquals(List.of(1, 2, 3), board.stream().map(TeamTop.Entry::rank).toList());
        assertEquals(10, top.entry(TeamTop.Board.KILLS, 1).orElseThrow().value());
        assertEquals(0, top.rank(TeamTop.Board.KILLS, 4), "teams with nothing are not ranked");
        assertEquals(20_000, top.top(TeamTop.Board.MONEY).getFirst().value());
        assertEquals(42, top.refreshedAt());
    }

    @Test
    void displaySizeLimitsTheListButNotTheRanks() {
        TeamTop top = new TeamTop();
        top.refresh(List.of(team(1, "A", 3), team(2, "B", 2), team(3, "C", 1)), m -> this.kills.getOrDefault(m, 0L),
            m -> 0L, 2, 0);
        assertEquals(2, top.top(TeamTop.Board.KILLS).size());
        assertEquals(3, top.rank(TeamTop.Board.KILLS, 3));
        assertTrue(top.top(TeamTop.Board.MONEY).isEmpty());
    }

    @Test
    void totalsSaturateAndIgnoreNegatives() {
        Team team = team(1, "Rich", 1, 1, 1);
        assertEquals(Long.MAX_VALUE, TeamTop.total(team, m -> Long.MAX_VALUE / 2));
        assertEquals(0, TeamTop.total(team, m -> -5));
    }
}
