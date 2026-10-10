package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.Dialect;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real SQL against a real SQLite file, migrated with the bundled migrations. */
class SqlStatsStorageTest {

    @TempDir
    Path folder;

    private JdbcDatabase database;
    private SqlStatsStorage storage;
    private Logger logger;

    @BeforeEach
    void open() throws Exception {
        this.logger = Logger.getLogger("stats-sql-test");
        this.logger.setLevel(Level.OFF);
        this.database = new JdbcDatabase(new SqliteSource(this.folder.resolve("stats.db"), 2), this.logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, this.logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.storage = new SqlStatsStorage(this.database);
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private StatsSnapshot load(UUID player) throws Exception {
        return this.storage.load(player).get(10, TimeUnit.SECONDS);
    }

    private void write(UUID player, StatsDelta delta) throws Exception {
        this.storage.write(List.of(new StatsStorage.PendingWrite(player, delta))).get(10, TimeUnit.SECONDS);
    }

    @Test
    void unknownPlayersLoadAsZero() throws Exception {
        assertEquals(StatsSnapshot.ZERO, load(UUID.randomUUID()));
    }

    @Test
    void firstWriteInsertsAndLaterWritesIncrement() throws Exception {
        UUID player = UUID.randomUUID();
        write(player, StatsDelta.kill().then(StatsDelta.kill()).then(StatsDelta.add(Counter.EARNED, 1_500)));
        assertEquals(new StatsSnapshot(2, 0, 2, 2, 0, 0, 1_500, 0), load(player));
        write(player, StatsDelta.death().then(StatsDelta.add(Counter.PLAYTIME, 60)).then(StatsDelta.add(Counter.BLOCKS, 3)));
        assertEquals(new StatsSnapshot(2, 1, 0, 2, 0, 3, 1_500, 60), load(player));
        write(player, StatsDelta.kill());
        assertEquals(new StatsSnapshot(3, 1, 1, 2, 0, 3, 1_500, 60), load(player));
    }

    @Test
    void staffSetAndResetWorkWithoutReadingFirst() throws Exception {
        UUID player = UUID.randomUUID();
        write(player, StatsDelta.add(Counter.MOBS, 40).then(StatsDelta.kill()));
        write(player, StatsDelta.set(Counter.MOBS, 7).then(StatsDelta.add(Counter.MOBS, 1)));
        assertEquals(8, load(player).mobs());
        assertEquals(1, load(player).kills());
        write(player, StatsDelta.reset());
        assertEquals(StatsSnapshot.ZERO, load(player));
    }

    /** Whatever the delta, the SQL upsert gives exactly what {@link StatsDelta#applyTo} computes in memory. */
    @Test
    void sqlMatchesTheInMemoryAlgebra() throws Exception {
        Random random = new Random(77);
        Map<UUID, StatsSnapshot> expected = new HashMap<>();
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            players.add(UUID.randomUUID());
        }
        for (int round = 0; round < 60; round++) {
            List<StatsStorage.PendingWrite> batch = new ArrayList<>();
            for (UUID player : players) {
                if (random.nextBoolean()) {
                    continue;
                }
                StatsDelta delta = StatsDelta.NONE;
                int events = 1 + random.nextInt(12);
                for (int e = 0; e < events; e++) {
                    delta = delta.then(StatsDeltaTest.randomEvent(random));
                }
                if (delta.isEmpty()) {
                    continue;
                }
                batch.add(new StatsStorage.PendingWrite(player, delta));
                expected.put(player, delta.applyTo(expected.getOrDefault(player, StatsSnapshot.ZERO)));
            }
            this.storage.write(batch).get(10, TimeUnit.SECONDS);
        }
        for (UUID player : players) {
            assertEquals(expected.getOrDefault(player, StatsSnapshot.ZERO), load(player));
        }
    }

    @Test
    void leaderboardQueriesRankTheRightColumns() throws Exception {
        UUID a = new UUID(1, 1);
        UUID b = new UUID(1, 2);
        UUID c = new UUID(1, 3);
        write(a, StatsDelta.set(Counter.KILLS, 30).then(StatsDelta.set(Counter.DEATHS, 10)).then(StatsDelta.add(Counter.PLAYTIME, 500)));
        write(b, StatsDelta.set(Counter.KILLS, 90).then(StatsDelta.set(Counter.DEATHS, 30)).then(StatsDelta.add(Counter.EARNED, 900)));
        write(c, StatsDelta.set(Counter.KILLS, 10).then(StatsDelta.add(Counter.BLOCKS, 5)));
        write(c, StatsDelta.kill().then(StatsDelta.kill()).then(StatsDelta.kill()));
        Map<Board, List<Leaderboard.Row>> top = this.storage.top(100, 25).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(b, a, c), top.get(Board.KILLS).stream().map(Leaderboard.Row::uuid).toList());
        assertEquals(List.of(90L, 30L, 13L), top.get(Board.KILLS).stream().map(Leaderboard.Row::value).toList());
        assertEquals(List.of(b, a), top.get(Board.DEATHS).stream().map(Leaderboard.Row::uuid).toList());
        // KDR: a and b are both 3.00; c (13 kills) is below the 25 kill minimum.
        assertEquals(List.of(b, a), top.get(Board.KDR).stream().map(Leaderboard.Row::uuid).toList());
        assertEquals(30, top.get(Board.KDR).getFirst().secondary());
        assertEquals(List.of(c), top.get(Board.STREAK).stream().map(Leaderboard.Row::uuid).toList());
        assertEquals(3, top.get(Board.STREAK).getFirst().value());
        assertEquals(List.of(a), top.get(Board.PLAYTIME).stream().map(Leaderboard.Row::uuid).toList());
        assertEquals(List.of(c), top.get(Board.BLOCKS).stream().map(Leaderboard.Row::uuid).toList());
        assertEquals(List.of(b), top.get(Board.EARNED).stream().map(Leaderboard.Row::uuid).toList());
        assertTrue(top.get(Board.MOBS).isEmpty());
        assertFalse(top.containsKey(Board.MONEY));
        // The limit applies.
        assertEquals(1, this.storage.top(1, 0).get(10, TimeUnit.SECONDS).get(Board.KILLS).size());
        // A threshold of 0 still leaves out players without kills.
        assertEquals(3, this.storage.top(100, 0).get(10, TimeUnit.SECONDS).get(Board.KDR).size());
    }

    @Test
    void storeAndLeaderboardsEndToEnd() throws Exception {
        StatsStore store = new StatsStore(this.storage, this.logger, System::currentTimeMillis, Duration.ofMinutes(5));
        Leaderboards boards = new Leaderboards(store, this.storage, limit -> List.of(), uuid -> uuid.toString().substring(0, 4),
            uuid -> true, () -> 100, () -> 1, this.logger, System::currentTimeMillis);
        UUID online = new UUID(2, 1);
        UUID offline = new UUID(2, 2);
        store.load(online).get(10, TimeUnit.SECONDS);
        store.join(online);
        for (int i = 0; i < 5; i++) {
            store.kill(online, offline);
        }
        store.add(offline, net.siftvanilla.siftcore.core.link.StatsRecorder.Stat.MONEY_EARNED, 250);
        assertTrue(boards.refresh().get(10, TimeUnit.SECONDS));
        assertEquals(1, boards.board(Board.KILLS).rankOf(online));
        assertEquals(5, boards.board(Board.KILLS).at(1).orElseThrow().value());
        assertEquals(1, boards.board(Board.DEATHS).rankOf(offline));
        assertEquals(1, boards.board(Board.EARNED).rankOf(offline));
        assertEquals(1, boards.board(Board.STREAK).rankOf(online));
        assertTrue(boards.refreshedAt() > 0);
        // Saving again changes nothing: the refresh already stored everything once.
        store.save().get(10, TimeUnit.SECONDS);
        assertEquals(5, load(online).kills());
        assertEquals(5, load(offline).deaths());
        store.close(Duration.ofSeconds(5));
        assertEquals(new StatsSnapshot(5, 0, 5, 5, 0, 0, 0, 0), load(online));
    }

    @Test
    void upsertSqlPerDialect() {
        String sqlite = SqlStatsStorage.upsertSql(Dialect.SQLITE);
        String mysql = SqlStatsStorage.upsertSql(Dialect.MYSQL);
        assertTrue(sqlite.contains("ON CONFLICT(uuid) DO UPDATE SET best_streak = MIN(MAX("), sqlite);
        assertTrue(mysql.contains("ON DUPLICATE KEY UPDATE best_streak = LEAST(GREATEST("), mysql);
        assertTrue(mysql.contains("kills = LEAST(kills * ? + ?, 2147483647)"), mysql);
        assertTrue(mysql.contains("money_earned = LEAST(money_earned * ? + ?, 1000000000000000000)"), mysql);
        // best_streak must be assigned before streak (MySQL assigns left to right).
        assertTrue(mysql.indexOf("best_streak =") < mysql.indexOf(", streak ="));
        // 1 uuid + 8 inserted values + 4 best + 2 streak + 2 per counter.
        assertEquals(1 + 8 + 4 + 2 + 2 * Counter.values().length, sqlite.chars().filter(ch -> ch == '?').count());
        assertEquals(sqlite.chars().filter(ch -> ch == '?').count(), mysql.chars().filter(ch -> ch == '?').count());
    }

    @Test
    void valuesAreCappedAtTheColumnRangeExactlyLikeInMemory() throws Exception {
        UUID player = UUID.randomUUID();
        StatsDelta nearTop = StatsDelta.set(Counter.KILLS, Counter.INT_MAX - 2).then(StatsDelta.set(Counter.BLOCKS, Counter.BIG_MAX - 1));
        StatsDelta over = StatsDelta.add(Counter.KILLS, 1_000).then(StatsDelta.add(Counter.BLOCKS, 5))
            .then(StatsDelta.add(Counter.DEATHS, Counter.INT_MAX)).then(StatsDelta.add(Counter.DEATHS, Counter.INT_MAX));
        write(player, nearTop);
        write(player, over);
        StatsSnapshot expected = over.applyTo(nearTop.applyTo(StatsSnapshot.ZERO));
        assertEquals(Counter.INT_MAX, expected.kills());
        assertEquals(Counter.INT_MAX, expected.deaths());
        assertEquals(Counter.BIG_MAX, expected.blocks());
        assertEquals(expected, load(player));
        // Streaks are capped the same way.
        StatsDelta streaks = StatsDelta.NONE;
        for (int i = 0; i < 3; i++) {
            streaks = streaks.then(StatsDelta.kill());
        }
        write(player, streaks);
        assertEquals(streaks.applyTo(expected), load(player));
    }

    @Test
    void aRowTheDatabaseRefusesIsReportedAndTheOthersAreStored() throws Exception {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        // Stands in for any row the database cannot take.
        this.database.write(c -> {
            try (java.sql.Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TRIGGER refuse_insert BEFORE INSERT ON stats WHEN NEW.uuid = '" + bad
                    + "' BEGIN SELECT RAISE(ABORT, 'refused for the test'); END");
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        Map<UUID, String> refused = this.storage.write(List.of(
            new StatsStorage.PendingWrite(bad, StatsDelta.kill()),
            new StatsStorage.PendingWrite(good, StatsDelta.add(Counter.MOBS, 4)))).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(bad), List.copyOf(refused.keySet()));
        assertTrue(refused.get(bad).contains("refused for the test"), refused.toString());
        assertEquals(4, load(good).mobs());
        assertEquals(StatsSnapshot.ZERO, load(bad));
        // When every row is refused, nothing was stored and the write fails as a whole.
        var allRefused = this.storage.write(List.of(new StatsStorage.PendingWrite(bad, StatsDelta.kill())));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> allRefused.get(10, TimeUnit.SECONDS));
    }

    @Test
    void negativeValuesEditedIntoTheTableLoadAsZero() throws Exception {
        UUID player = UUID.randomUUID();
        write(player, StatsDelta.add(Counter.KILLS, 3));
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE stats SET deaths = -4 WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        assertEquals(new StatsSnapshot(3, 0, 0, 0, 0, 0, 0, 0), load(player));
        int rows = this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM stats"); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }).get(10, TimeUnit.SECONDS);
        assertEquals(1, rows);
    }
}
