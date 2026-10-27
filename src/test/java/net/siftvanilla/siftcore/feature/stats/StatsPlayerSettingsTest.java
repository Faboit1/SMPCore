package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import org.junit.jupiter.api.Test;

/** Climb alerts, hiding from the leaderboards and who sees a balance in the stats. */
class StatsPlayerSettingsTest {

    private static final UUID A = new UUID(5, 1);
    private static final UUID B = new UUID(5, 2);
    private static final UUID C = new UUID(5, 3);

    private static Logger quiet() {
        Logger logger = Logger.getLogger("stats-settings-test");
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static Leaderboard board(Board board, long builtAt, Object... uuidValuePairs) {
        List<Leaderboard.Row> rows = new ArrayList<>();
        for (int i = 0; i < uuidValuePairs.length; i += 2) {
            rows.add(new Leaderboard.Row((UUID) uuidValuePairs[i], ((Number) uuidValuePairs[i + 1]).longValue(), 0));
        }
        return Leaderboard.build(board, rows, 100, uuid -> "p", builtAt);
    }

    private static Map<Board, Leaderboard> boards(long builtAt, Map<Board, Leaderboard> some) {
        Map<Board, Leaderboard> all = new EnumMap<>(Board.class);
        for (Board board : Board.values()) {
            all.put(board, some.getOrDefault(board, builtAt == 0 ? Leaderboard.empty(board) : board(board, builtAt)));
        }
        return all;
    }

    @Test
    void theSettingSitsInCombatAndStatsDeclaresWhatItReads() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, new Relations());
        StatsFeature.registerSettings(settings);
        assertEquals(List.of("quiet-in-combat", "leaderboard-rank-alerts"),
            settings.registry().in(SettingCategories.COMBAT.id()).stream().map(Registry.Entry::id).toList());
        assertEquals(List.of("top-10", "all", "off"), StatsFeature.RANK_ALERTS.optionIds());
        assertEquals(RankAlerts.TOP_10, StatsFeature.RANK_ALERTS.defaultValue());
        assertTrue(settings.hasReader(SharedSettings.HIDE_FROM_LEADERBOARDS));
        assertTrue(settings.hasReader(SharedSettings.BALANCE_PRIVACY));
        Registry.Entry<?> hide = settings.registry().entry(SharedSettings.HIDE_FROM_LEADERBOARDS.id());
        assertTrue(hide.offered(), "offered once stats reads it");
    }

    @Test
    void climbsAreBetterPlacesOnlyAndFollowTheChoice() {
        assertTrue(Climbs.climbed(0, 5), "onto the board");
        assertTrue(Climbs.climbed(5, 4));
        assertFalse(Climbs.climbed(4, 4), "the same place");
        assertFalse(Climbs.climbed(4, 6), "dropping");
        assertFalse(Climbs.climbed(4, 0), "off the board");
        assertTrue(RankAlerts.TOP_10.tells(10) && !RankAlerts.TOP_10.tells(11));
        assertTrue(RankAlerts.ALL.tells(87));
        assertFalse(RankAlerts.OFF.tells(1));

        Map<Board, Leaderboard> before = boards(1_000, Map.of(
            Board.KILLS, board(Board.KILLS, 1_000, A, 10, B, 20, C, 30),
            Board.DEATHS, board(Board.DEATHS, 1_000, A, 1, B, 2)));
        Map<Board, Leaderboard> after = boards(2_000, Map.of(
            Board.KILLS, board(Board.KILLS, 2_000, A, 40, B, 20, C, 30),
            Board.DEATHS, board(Board.DEATHS, 2_000, A, 9, B, 2),
            Board.MOBS, board(Board.MOBS, 2_000, B, 3)));
        Map<UUID, RankAlerts> choices = Map.of(A, RankAlerts.TOP_10, B, RankAlerts.ALL, C, RankAlerts.ALL);
        List<Climbs.Climb> climbs = Climbs.of(before, after, List.of(A, B, C), choices::get);
        assertEquals(Set.of(new Climbs.Climb(A, Board.KILLS, 1), new Climbs.Climb(B, Board.MOBS, 1)), new HashSet<>(climbs),
            "A topped kills, B got onto mobs; B and C dropped on kills and nobody is told about deaths: " + climbs);

        assertEquals(List.of(), Climbs.of(before, after, List.of(A), uuid -> RankAlerts.OFF), "off");
        Map<Board, Leaderboard> empty = boards(0, Map.of());
        assertEquals(List.of(), Climbs.of(empty, after, List.of(A, B, C), choices::get), "the first build after a start tells nobody");
    }

    @Test
    void topTenOnlyTellsInsideTheTopTen() {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            players.add(new UUID(6, i));
        }
        UUID last = players.get(11);
        Map<Board, Leaderboard> before = boards(1, Map.of(Board.BLOCKS, blocks(1, players, -1, 0)));
        assertEquals(12, before.get(Board.BLOCKS).rankOf(last));

        Map<Board, Leaderboard> jump = boards(2, Map.of(Board.BLOCKS, blocks(2, players, 11, 95)));
        assertEquals(6, jump.get(Board.BLOCKS).rankOf(last), "a shared 6th place");
        assertEquals(List.of(new Climbs.Climb(last, Board.BLOCKS, 6)), Climbs.of(before, jump, players, uuid -> RankAlerts.TOP_10),
            "only the climber is told; the players it passed dropped");

        Map<Board, Leaderboard> step = boards(3, Map.of(Board.BLOCKS, blocks(3, players, 11, 90)));
        assertEquals(11, step.get(Board.BLOCKS).rankOf(last));
        assertEquals(List.of(), Climbs.of(before, step, players, uuid -> RankAlerts.TOP_10), "11th is outside the top 10");
        assertEquals(List.of(new Climbs.Climb(last, Board.BLOCKS, 11)), Climbs.of(before, step, players, uuid -> RankAlerts.ALL));
    }

    /** The blocks board of twelve players with 100 down to 89 blocks, player {@code changed} (or none) at {@code value}. */
    private static Leaderboard blocks(long builtAt, List<UUID> players, int changed, long value) {
        Object[] rows = new Object[players.size() * 2];
        for (int i = 0; i < players.size(); i++) {
            rows[i * 2] = players.get(i);
            rows[i * 2 + 1] = i == changed ? value : 100L - i;
        }
        return board(Board.BLOCKS, builtAt, rows);
    }

    @Test
    void hiddenPlayersFollowTheirChoiceTheServerAndTheirLoadedValue() {
        HiddenPlayers.Hidden none = HiddenPlayers.decide(Map.of(A, true, B, false), Map.of(), false, false);
        assertTrue(none.test().test(A), "a stored choice of an offline player");
        assertFalse(none.test().test(B));
        assertFalse(none.test().test(C), "nobody chose: the default");
        assertEquals(1, none.count());

        HiddenPlayers.Hidden online = HiddenPlayers.decide(Map.of(A, true), Map.of(A, false, C, true), false, false);
        assertFalse(online.test().test(A), "online players read their loaded value (permissions applied)");
        assertTrue(online.test().test(C));
        assertEquals(1, online.count());

        HiddenPlayers.Hidden locked = HiddenPlayers.decide(Map.of(A, true), Map.of(B, true), false, true);
        assertFalse(locked.test().test(A), "a lock or a hidden setting ignores stored choices");
        assertTrue(locked.test().test(B));

        HiddenPlayers.Hidden everyone = HiddenPlayers.decide(Map.of(A, false), Map.of(), true, false);
        assertFalse(everyone.test().test(A));
        assertTrue(everyone.test().test(C), "a server default of on hides everyone else");
        assertEquals(Integer.MAX_VALUE, everyone.count());
        assertEquals(10 + Leaderboards.EXTRA_ROWS + Leaderboards.MAX_HIDDEN_ROWS, Leaderboards.fetchSize(10, everyone), "capped");
        assertEquals(10 + Leaderboards.EXTRA_ROWS + 1, Leaderboards.fetchSize(10, none));
    }

    @Test
    void hiddenPlayersAreLeftOffEveryBoardAndTheSwapIsReported() throws Exception {
        FakeStorage storage = new FakeStorage();
        StatsStore store = new StatsStore(storage, quiet(), System::currentTimeMillis, Duration.ofMinutes(5));
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID player = new UUID(7, i);
            players.add(player);
            store.add(player, StatsRecorder.Stat.KILLS, 100 - i);
        }
        UUID staff = players.getFirst();
        List<EconomyApi.TopEntry> money = List.of(
            new EconomyApi.TopEntry(1, staff, "staff", 9_000),
            new EconomyApi.TopEntry(2, players.get(3), "p3", 500));
        AtomicReference<HiddenPlayers.Hidden> hiding = new AtomicReference<>(HiddenPlayers.Hidden.NONE);
        List<Integer> fetched = new ArrayList<>();
        Leaderboards boards = new Leaderboards(store, storage, limit -> {
            fetched.add(limit);
            return money;
        }, uuid -> "p", uuid -> true, () -> 10, () -> 0, quiet(), System::currentTimeMillis,
            () -> CompletableFuture.completedFuture(hiding.get()));
        List<Map<Board, Leaderboard>> swaps = new ArrayList<>();
        boards.onSwap((before, after) -> {
            swaps.add(before);
            swaps.add(after);
        });

        assertTrue(boards.refresh().get(10, TimeUnit.SECONDS));
        assertEquals(1, boards.board(Board.KILLS).rankOf(staff));
        assertEquals(1, boards.board(Board.MONEY).rankOf(staff));
        assertEquals(2, swaps.size());
        assertEquals(0, swaps.get(0).get(Board.KILLS).builtAt(), "the first swap replaces the empty boards");

        hiding.set(HiddenPlayers.decide(Map.of(staff, true), Map.of(), false, false));
        assertTrue(boards.refresh().get(10, TimeUnit.SECONDS));
        Leaderboard kills = boards.board(Board.KILLS);
        assertEquals(0, kills.rankOf(staff), "hidden from kills");
        assertEquals(10, kills.size(), "the board still fills up");
        assertEquals(1, kills.rankOf(players.get(1)), "everyone moves up a place");
        assertEquals(0, boards.board(Board.MONEY).rankOf(staff), "and from the money board");
        assertEquals(1, boards.board(Board.MONEY).rankOf(players.get(3)));
        assertEquals(10 + Leaderboards.EXTRA_ROWS + 1, fetched.getLast(), "one more row for the hidden player");
        assertEquals(4, swaps.size());
        List<Climbs.Climb> climbs = Climbs.of(swaps.get(2), swaps.get(3), players, uuid -> RankAlerts.ALL);
        assertTrue(climbs.contains(new Climbs.Climb(players.get(1), Board.KILLS, 1)), "a staff member hiding lifts the others: " + climbs);
    }

    /**
     * The economy's top list is a snapshot of {@code baltop.size} entries: asking for more rows returns no more, so
     * every hidden account among the richest leaves the money board one place short (a documented limit until the
     * economy keeps more entries or leaves hidden players out itself).
     */
    @Test
    void theMoneyBoardOnlyFillsToTheEconomysSnapshot() throws Exception {
        FakeStorage storage = new FakeStorage();
        StatsStore store = new StatsStore(storage, quiet(), System::currentTimeMillis, Duration.ofMinutes(5));
        int snapshotSize = 5;
        List<EconomyApi.TopEntry> snapshot = new ArrayList<>();
        for (int i = 0; i < snapshotSize; i++) {
            snapshot.add(new EconomyApi.TopEntry(i + 1, new UUID(8, i), "p" + i, 1_000 - i));
        }
        UUID richest = snapshot.getFirst().account();
        List<Integer> asked = new ArrayList<>();
        AtomicReference<HiddenPlayers.Hidden> hiding = new AtomicReference<>(HiddenPlayers.decide(Map.of(richest, true), Map.of(), false, false));
        Leaderboards boards = new Leaderboards(store, storage, limit -> {
            asked.add(limit);
            // What BalanceTop.top does: never more than the snapshot holds.
            return snapshot.subList(0, Math.min(limit, snapshot.size()));
        }, uuid -> "p", uuid -> true, () -> snapshotSize, () -> 0, quiet(), System::currentTimeMillis,
            () -> CompletableFuture.completedFuture(hiding.get()));

        assertTrue(boards.refresh().get(10, TimeUnit.SECONDS));
        Leaderboard money = boards.board(Board.MONEY);
        assertEquals(snapshotSize + Leaderboards.EXTRA_ROWS + 1, asked.getLast(), "more rows are asked for");
        assertEquals(0, money.rankOf(richest), "the hidden account is left off");
        assertEquals(snapshotSize - 1, money.size(), "but the snapshot has no more, so the board is one place short");
        assertEquals(1, money.rankOf(snapshot.get(1).account()));
    }

    @Test
    void aFailedHiddenReadKeepsThePreviousBoards() throws Exception {
        FakeStorage storage = new FakeStorage();
        StatsStore store = new StatsStore(storage, quiet(), System::currentTimeMillis, Duration.ofMinutes(5));
        store.add(A, StatsRecorder.Stat.KILLS, 3);
        AtomicReference<CompletableFuture<HiddenPlayers.Hidden>> next = new AtomicReference<>(
            CompletableFuture.completedFuture(HiddenPlayers.Hidden.NONE));
        Leaderboards boards = new Leaderboards(store, storage, limit -> List.of(), uuid -> "p", uuid -> true, () -> 10, () -> 0, quiet(),
            System::currentTimeMillis, next::get);
        assertTrue(boards.refresh().get(10, TimeUnit.SECONDS));
        long built = boards.refreshedAt();
        next.set(CompletableFuture.failedFuture(new java.sql.SQLException("down")));
        assertFalse(boards.refresh().get(10, TimeUnit.SECONDS), "never lists a hidden player because the read failed");
        assertEquals(built, boards.refreshedAt());
        assertEquals(1, boards.board(Board.KILLS).rankOf(A));
    }

    @Test
    void balanceVisibilityFollowsTheTargetsChoice() {
        Relations none = new Relations();
        assertTrue(StatsViews.balanceVisible(none, Audience.EVERYONE, A, B));
        assertFalse(StatsViews.balanceVisible(none, Audience.NOBODY, A, B));
        assertFalse(StatsViews.balanceVisible(none, Audience.FRIENDS, A, B), "no friends system");
        assertFalse(StatsViews.balanceVisible(none, null, A, B), "nothing read: hidden");
        Relations friends = new Relations();
        friends.bind(new FriendLookup() {
            @Override
            public boolean friends(UUID a, UUID b) {
                return Set.of(a, b).equals(Set.of(A, B));
            }

            @Override
            public Set<UUID> friendsOf(UUID player) {
                return Set.of();
            }
        }, TeamLookup.NONE, IgnoreLookup.NONE);
        assertTrue(StatsViews.balanceVisible(friends, Audience.FRIENDS, A, B));
        assertFalse(StatsViews.balanceVisible(friends, Audience.FRIENDS, A, C));
        assertEquals(Optional.of(Audience.NOBODY), SharedSettings.BALANCE_PRIVACY.decode("nobody"));
    }
}
