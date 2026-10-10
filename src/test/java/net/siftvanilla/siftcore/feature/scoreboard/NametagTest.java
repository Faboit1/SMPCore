package net.siftvanilla.siftcore.feature.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** The nametag team model and the team diff that keeps every board in line with it. */
class NametagTest {

    private static final NametagModel.Key LEGEND = new NametagModel.Key(0, "Legend");
    private static final NametagModel.Key ELITE = new NametagModel.Key(1, "Elite");
    private static final NametagModel.Key NONE = new NametagModel.Key(99, "");

    @Test
    void playersAreGroupedByRankAndHigherRanksSortFirst() {
        NametagModel model = new NametagModel();
        model.set("Zed", NONE);
        model.set("Alex", ELITE);
        model.set("Blake", LEGEND);
        model.set("Casey", ELITE);
        List<String> names = new ArrayList<>(model.teams().keySet());
        assertEquals(3, names.size());
        assertEquals(Set.of("Blake"), model.teams().get(names.get(0)).members(), "the highest rank's team sorts first");
        assertEquals(Set.of("Alex", "Casey"), model.teams().get(names.get(1)).members());
        assertEquals(Set.of("Zed"), model.teams().get(names.get(2)).members());
        for (String name : names) {
            assertTrue(name.startsWith(NametagModel.PREFIX) && name.length() <= 16, name);
        }
    }

    @Test
    void theVersionOnlyMovesOnRealChanges() {
        NametagModel model = new NametagModel();
        long start = model.version();
        assertTrue(model.set("Alex", ELITE));
        assertFalse(model.set("Alex", ELITE), "setting the same team again is no change");
        assertEquals(start + 1, model.version());
        assertTrue(model.set("Alex", LEGEND));
        assertTrue(model.remove("Alex"));
        assertFalse(model.remove("Alex"));
        assertEquals(start + 3, model.version());
        assertTrue(model.teams().isEmpty());
        model.touch();
        assertEquals(start + 4, model.version());
    }

    @Test
    void teamNamesAreStableAndDistinct() {
        NametagModel model = new NametagModel();
        String legend = model.name(LEGEND);
        assertEquals(legend, model.name(new NametagModel.Key(0, "Legend")));
        assertNotEquals(legend, model.name(new NametagModel.Key(0, "Founder")), "two labels at the same place get two teams");
        assertNotEquals(legend, model.name(ELITE));
    }

    // ------------------------------------------------------------------ the diff

    private static TeamDiff.View view(String prefix, String... members) {
        return new TeamDiff.View(Component.text(prefix), Set.of(members));
    }

    /** Applies ops to a copy of a board the way Boards does, to check the result. */
    private static Map<String, Set<String>> applied(Map<String, TeamDiff.View> start, List<TeamDiff.Op> ops) {
        Map<String, Set<String>> board = new HashMap<>();
        start.forEach((team, view) -> board.put(team, new java.util.TreeSet<>(view.members())));
        for (TeamDiff.Op op : ops) {
            switch (op) {
                case TeamDiff.Remove remove -> board.remove(remove.team());
                case TeamDiff.Leave leave -> board.get(leave.team()).removeAll(leave.players());
                case TeamDiff.Create create -> board.put(create.team(), new java.util.TreeSet<>());
                case TeamDiff.Prefix prefix -> assertTrue(board.containsKey(prefix.team()));
                case TeamDiff.Join join -> {
                    // A player is in one team per board: joining moves them.
                    board.values().forEach(members -> members.removeAll(join.players()));
                    board.get(join.team()).addAll(join.players());
                }
            }
        }
        return board;
    }

    private static Map<String, Set<String>> members(Map<String, TeamDiff.View> views) {
        Map<String, Set<String>> result = new HashMap<>();
        views.forEach((team, view) -> result.put(team, view.members()));
        return result;
    }

    @Test
    void equalBoardsNeedNothing() {
        Map<String, TeamDiff.View> board = Map.of("t1", view("Elite ", "Alex"));
        assertTrue(TeamDiff.between(board, Map.of("t1", view("Elite ", "Alex"))).isEmpty());
    }

    @Test
    void aNewBoardGetsEveryTeamAndMember() {
        Map<String, TeamDiff.View> wanted = Map.of("t1", view("Elite ", "Alex", "Casey"), "t2", view("", "Zed"));
        List<TeamDiff.Op> ops = TeamDiff.between(Map.of(), wanted);
        assertEquals(List.of(new TeamDiff.Create("t1", Component.text("Elite ")), new TeamDiff.Create("t2", Component.text("")),
            new TeamDiff.Join("t1", List.of("Alex", "Casey")), new TeamDiff.Join("t2", List.of("Zed"))), ops);
        assertEquals(members(wanted), applied(Map.of(), ops));
    }

    @Test
    void aRankChangeMovesThePlayerAndEmptiesAreRemoved() {
        Map<String, TeamDiff.View> current = Map.of("t1", view("Elite ", "Alex"), "t2", view("", "Zed", "Alex2"));
        Map<String, TeamDiff.View> wanted = Map.of("t0", view("Legend ", "Alex"), "t2", view("", "Zed", "Alex2"));
        List<TeamDiff.Op> ops = TeamDiff.between(current, wanted);
        assertEquals(new TeamDiff.Remove("t1"), ops.getFirst(), "removed teams go first");
        assertTrue(ops.contains(new TeamDiff.Create("t0", Component.text("Legend "))));
        assertTrue(ops.contains(new TeamDiff.Join("t0", List.of("Alex"))));
        assertFalse(ops.stream().anyMatch(op -> op.team().equals("t2")), "the unchanged team is left alone");
        assertEquals(members(wanted), applied(current, ops));
    }

    @Test
    void joinsAndLeavesWithinATeamAndPrefixChanges() {
        Map<String, TeamDiff.View> current = Map.of("t1", view("Elite ", "Alex", "Blake"));
        Map<String, TeamDiff.View> wanted = Map.of("t1", view("Elite+ ", "Blake", "Casey"));
        List<TeamDiff.Op> ops = TeamDiff.between(current, wanted);
        assertEquals(List.of(new TeamDiff.Leave("t1", List.of("Alex")), new TeamDiff.Prefix("t1", Component.text("Elite+ ")),
            new TeamDiff.Join("t1", List.of("Casey"))), ops);
        assertEquals(members(wanted), applied(current, ops));
    }

    @Test
    void aPlayerMovingBetweenExistingTeamsLeavesBeforeJoining() {
        Map<String, TeamDiff.View> current = Map.of("a", view("A ", "Alex"), "b", view("B ", "Blake"));
        Map<String, TeamDiff.View> wanted = Map.of("a", view("A ", "Blake"), "b", view("B ", "Alex"));
        List<TeamDiff.Op> ops = TeamDiff.between(current, wanted);
        int lastLeave = -1;
        int firstJoin = Integer.MAX_VALUE;
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) instanceof TeamDiff.Leave) {
                lastLeave = i;
            }
            if (ops.get(i) instanceof TeamDiff.Join) {
                firstJoin = Math.min(firstJoin, i);
            }
        }
        assertTrue(lastLeave < firstJoin, ops.toString());
        assertEquals(members(wanted), applied(current, ops));
    }

    @Test
    void everythingGoesWhenNametagsAreTurnedOff() {
        Map<String, TeamDiff.View> current = Map.of("a", view("A ", "Alex"), "b", view("", "Blake"));
        List<TeamDiff.Op> ops = TeamDiff.between(current, Map.of());
        assertEquals(List.of(new TeamDiff.Remove("a"), new TeamDiff.Remove("b")), ops);
    }
}
