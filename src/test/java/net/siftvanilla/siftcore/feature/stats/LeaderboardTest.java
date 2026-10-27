package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class LeaderboardTest {

    private static final Function<UUID, String> NAMES = uuid -> "p" + uuid.getLeastSignificantBits();

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private static List<Integer> ranks(Leaderboard board) {
        return board.entries().stream().map(Leaderboard.Entry::rank).toList();
    }

    private static List<Long> values(Leaderboard board) {
        return board.entries().stream().map(Leaderboard.Entry::value).toList();
    }

    @Test
    void sortsBestFirstWhateverTheInputOrder() {
        List<Leaderboard.Row> rows = new ArrayList<>(List.of(
            new Leaderboard.Row(id(1), 5, 0), new Leaderboard.Row(id(2), 50, 0), new Leaderboard.Row(id(3), 20, 0)));
        Collections.shuffle(rows, new Random(3));
        Leaderboard board = Leaderboard.build(Board.KILLS, rows, 100, NAMES, 1);
        assertEquals(List.of(50L, 20L, 5L), values(board));
        assertEquals(List.of(1, 2, 3), ranks(board));
        assertEquals("p2", board.at(1).orElseThrow().name());
        assertEquals(1, board.rankOf(id(2)));
        assertEquals(3, board.rankOf(id(1)));
        assertEquals(0, board.rankOf(id(9)));
        assertTrue(board.at(4).isEmpty());
        assertTrue(board.at(0).isEmpty());
    }

    @Test
    void tiesShareARankAndTheNextRankSkips() {
        List<Leaderboard.Row> rows = List.of(
            new Leaderboard.Row(id(4), 10, 0), new Leaderboard.Row(id(1), 30, 0), new Leaderboard.Row(id(3), 10, 0),
            new Leaderboard.Row(id(2), 30, 0), new Leaderboard.Row(id(5), 9, 0));
        Leaderboard board = Leaderboard.build(Board.MOBS, rows, 100, NAMES, 1);
        assertEquals(List.of(30L, 30L, 10L, 10L, 9L), values(board));
        assertEquals(List.of(1, 1, 3, 3, 5), ranks(board));
        // Equal values are ordered by UUID so the order never flickers between refreshes.
        assertEquals(id(1), board.entries().get(0).uuid());
        assertEquals(id(2), board.entries().get(1).uuid());
        assertEquals(id(3), board.entries().get(2).uuid());
        assertEquals(3, board.rankOf(id(4)));
    }

    @Test
    void keepsOnlyTheBestAndDropsEmptyAndDuplicateRows() {
        List<Leaderboard.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            rows.add(new Leaderboard.Row(id(i), i, 0));
        }
        rows.add(new Leaderboard.Row(id(500), 0, 0));
        rows.add(new Leaderboard.Row(id(150), 1, 0));
        Leaderboard board = Leaderboard.build(Board.BLOCKS, rows, 100, NAMES, 1);
        assertEquals(100, board.size());
        assertEquals(150L, board.at(1).orElseThrow().value());
        assertEquals(51L, board.at(100).orElseThrow().value());
        assertEquals(0, board.rankOf(id(500)));
        assertEquals(1, board.rankOf(id(150)));
    }

    @Test
    void kdrRanksByExactRatioThenKills() {
        List<Leaderboard.Row> rows = List.of(
            new Leaderboard.Row(id(1), 30, 10),   // 3.00
            new Leaderboard.Row(id(2), 90, 30),   // 3.00, more kills
            new Leaderboard.Row(id(3), 50, 0),    // 50.00 (no deaths counts as one)
            new Leaderboard.Row(id(4), 2001, 1000), // 2.001
            new Leaderboard.Row(id(5), 2000, 1000)); // 2.000
        Leaderboard board = Leaderboard.build(Board.KDR, rows, 100, NAMES, 1);
        assertEquals(List.of(id(3), id(2), id(1), id(4), id(5)), board.entries().stream().map(Leaderboard.Entry::uuid).toList());
        // Equal ratios share the rank even though more kills is shown first.
        assertEquals(List.of(1, 2, 2, 4, 5), ranks(board));
        assertEquals(30, board.entryOf(id(2)).orElseThrow().secondary());
    }

    @Test
    void pagesAreClampedAndCoverEveryEntry() {
        List<Leaderboard.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 23; i++) {
            rows.add(new Leaderboard.Row(id(i), 100 - i, 0));
        }
        Leaderboard board = Leaderboard.build(Board.KILLS, rows, 100, NAMES, 1);
        assertEquals(3, board.pages(10));
        assertEquals(10, board.page(1, 10).size());
        assertEquals(3, board.page(3, 10).size());
        assertEquals(board.page(3, 10), board.page(99, 10));
        assertEquals(board.page(1, 10), board.page(-4, 10));
        assertEquals(1, Leaderboard.empty(Board.KILLS).pages(10));
        assertTrue(Leaderboard.empty(Board.KILLS).page(1, 10).isEmpty());
    }

    @Test
    void ranksAreConsistentWithValuesForRandomBoards() {
        Random random = new Random(17);
        for (int trial = 0; trial < 500; trial++) {
            List<Leaderboard.Row> rows = new ArrayList<>();
            int count = random.nextInt(60);
            for (int i = 0; i < count; i++) {
                rows.add(new Leaderboard.Row(UUID.randomUUID(), 1 + random.nextInt(8), 0));
            }
            Leaderboard board = Leaderboard.build(Board.PLAYTIME, rows, 40, NAMES, 1);
            List<Leaderboard.Entry> entries = board.entries();
            for (int i = 0; i < entries.size(); i++) {
                Leaderboard.Entry entry = entries.get(i);
                long better = entries.stream().filter(e -> e.value() > entry.value()).count();
                assertEquals(better + 1, entry.rank(), "rank is one more than the number of better entries");
                if (i > 0) {
                    assertTrue(entries.get(i - 1).value() >= entry.value());
                }
            }
        }
    }
}
