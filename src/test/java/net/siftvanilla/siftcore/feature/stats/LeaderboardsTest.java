package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import org.junit.jupiter.api.Test;

class LeaderboardsTest {

    private static Logger quiet() {
        Logger logger = Logger.getLogger("stats-boards-test");
        logger.setLevel(Level.OFF);
        return logger;
    }

    @Test
    void onlyPlayersAreListedAndBoardsStillFillUp() throws Exception {
        FakeStorage storage = new FakeStorage();
        StatsStore store = new StatsStore(storage, quiet(), System::currentTimeMillis, Duration.ofMinutes(5));
        Set<UUID> players = new HashSet<>();
        List<UUID> ordered = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID player = new UUID(7, i);
            players.add(player);
            ordered.add(player);
            store.add(player, StatsRecorder.Stat.KILLS, 100 - i);
        }
        UUID npc = new UUID(8, 1);
        store.add(npc, StatsRecorder.Stat.KILLS, 1_000);
        UUID bank = new UUID(8, 2);
        List<EconomyApi.TopEntry> money = List.of(
            new EconomyApi.TopEntry(1, bank, "bank", 9_000),
            new EconomyApi.TopEntry(2, ordered.get(3), "p3", 500),
            new EconomyApi.TopEntry(3, ordered.get(5), "p5", 400));
        Leaderboards boards = new Leaderboards(store, storage, limit -> money, uuid -> "p" + uuid.getLeastSignificantBits(),
            players::contains, () -> 10, () -> 0, quiet(), System::currentTimeMillis);

        assertTrue(boards.refresh().get(10, TimeUnit.SECONDS));
        Leaderboard kills = boards.board(Board.KILLS);
        assertEquals(10, kills.size(), "the board is full although a stronger row was not a player");
        assertEquals(0, kills.rankOf(npc));
        assertEquals(ordered.get(0), kills.at(1).orElseThrow().uuid());
        assertEquals(10, kills.rankOf(ordered.get(9)));
        assertEquals(0, kills.rankOf(ordered.get(10)), "11th player is past the board size");
        Leaderboard balance = boards.board(Board.MONEY);
        assertEquals(List.of(ordered.get(3), ordered.get(5)), balance.entries().stream().map(Leaderboard.Entry::uuid).toList());
        assertEquals("p3", balance.at(1).orElseThrow().name(), "names come from the economy's board");
        assertEquals(1, balance.rankOf(ordered.get(3)));
        assertFalse(storage.hasRow(npc) && boards.board(Board.KILLS).entryOf(npc).isPresent());
        assertEquals(1_000, storage.row(npc).kills(), "rows of non-players are still stored, just not listed");
    }

    @Test
    void aRefreshStoresPendingStatsFirst() throws Exception {
        FakeStorage storage = new FakeStorage();
        StatsStore store = new StatsStore(storage, quiet(), System::currentTimeMillis, Duration.ofMinutes(5));
        Leaderboards boards = new Leaderboards(store, storage, limit -> List.of(), uuid -> "x", uuid -> true, () -> 10, () -> 0,
            quiet(), System::currentTimeMillis);
        UUID player = new UUID(9, 1);
        store.add(player, StatsRecorder.Stat.BLOCKS_MINED, 42);
        assertEquals(0, boards.refreshedAt());
        assertTrue(boards.refresh().get(10, TimeUnit.SECONDS));
        assertEquals(42, storage.row(player).blocks());
        assertEquals(42, boards.board(Board.BLOCKS).at(1).orElseThrow().value());
        assertTrue(boards.refreshedAt() > 0);
        assertEquals(0, store.unsaved());
    }
}
