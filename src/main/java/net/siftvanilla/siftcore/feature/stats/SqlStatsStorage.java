package net.siftvanilla.siftcore.feature.stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.Dialect;

/**
 * Stats in the {@code stats} table (migration V007). Writes are one atomic upsert per player that applies a
 * {@link StatsDelta} to whatever the row holds (or to zeros for a new row), so no read is needed first:
 * <pre>
 *   best_streak = MIN(MAX(best_streak * keepBest, (streak + carryAdd) * carry, peak), INT_MAX)
 *   streak      = MIN(streak * keepStreak + addStreak, INT_MAX)
 *   kills       = MIN(kills * keep + add, max)          (and the same for every other counter)
 * </pre>
 * {@code best_streak} is assigned first because MySQL evaluates assignments left to right with the new values;
 * SQLite always uses the old row, so the order works for both. Values are capped at the column's range
 * ({@link Counter#max()}) like {@link StatsDelta#applyTo} caps them in memory, so a write can never overflow a
 * 32-bit MySQL column; the arithmetic itself runs on 64-bit integers in both databases.
 */
final class SqlStatsStorage implements StatsStorage {

    private static final Counter[] COUNTERS = Counter.values();
    private static final String SELECT = "SELECT kills, deaths, streak, best_streak, mobs_killed, blocks_mined, money_earned, "
        + "playtime_seconds FROM stats WHERE uuid = ?";

    private final Database database;
    private final String upsert;

    SqlStatsStorage(Database database) {
        this.database = database;
        this.upsert = upsertSql(database.dialect());
    }

    /** The upsert for one player; see the class comment for the parameters. */
    static String upsertSql(Dialect dialect) {
        String max = dialect == Dialect.SQLITE ? "MAX" : "GREATEST";
        String min = dialect == Dialect.SQLITE ? "MIN" : "LEAST";
        StringBuilder set = new StringBuilder();
        set.append("best_streak = ").append(min).append('(').append(max).append("(best_streak * ?, (streak + ?) * ?, ?), ")
            .append(Counter.INT_MAX).append("), streak = ").append(min).append("(streak * ? + ?, ").append(Counter.INT_MAX).append(')');
        for (Counter counter : COUNTERS) {
            set.append(", ").append(counter.column()).append(" = ").append(min).append('(').append(counter.column())
                .append(" * ? + ?, ").append(counter.max()).append(')');
        }
        String insert = "INSERT INTO stats (uuid, kills, deaths, streak, best_streak, mobs_killed, blocks_mined, money_earned, "
            + "playtime_seconds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return switch (dialect) {
            case SQLITE -> insert + " ON CONFLICT(uuid) DO UPDATE SET " + set;
            case MYSQL -> insert + " ON DUPLICATE KEY UPDATE " + set;
        };
    }

    @Override
    public CompletableFuture<StatsSnapshot> load(UUID player) {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return StatsSnapshot.ZERO;
                    }
                    return new StatsSnapshot(positive(rs, 1), positive(rs, 2), positive(rs, 3), positive(rs, 4),
                        positive(rs, 5), positive(rs, 6), positive(rs, 7), positive(rs, 8));
                }
            }
        });
    }

    private static long positive(ResultSet rs, int column) throws SQLException {
        return Math.max(0, rs.getLong(column));
    }

    @Override
    public CompletableFuture<Map<UUID, String>> write(List<PendingWrite> writes) {
        if (writes.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return this.database.write(c -> {
            Map<UUID, String> refused = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(this.upsert)) {
                for (PendingWrite write : writes) {
                    // Each player in its own savepoint: a refused row rolls back alone and the others still commit.
                    Savepoint savepoint = c.setSavepoint();
                    try {
                        bind(ps, write);
                        ps.executeUpdate();
                        c.releaseSavepoint(savepoint);
                    } catch (SQLException e) {
                        c.rollback(savepoint);
                        refused.put(write.player(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                }
            }
            if (refused.size() == writes.size()) {
                throw new SQLException("No stats row could be stored: " + refused.values().iterator().next());
            }
            return Map.copyOf(refused);
        });
    }

    private static void bind(PreparedStatement ps, PendingWrite write) throws SQLException {
        StatsDelta delta = write.delta();
        StatsSnapshot inserted = delta.applyTo(StatsSnapshot.ZERO);
        StreakChange streak = delta.streak();
        int i = 1;
        ps.setString(i++, write.player().toString());
        ps.setLong(i++, inserted.kills());
        ps.setLong(i++, inserted.deaths());
        ps.setLong(i++, inserted.streak());
        ps.setLong(i++, inserted.bestStreak());
        ps.setLong(i++, inserted.mobs());
        ps.setLong(i++, inserted.blocks());
        ps.setLong(i++, inserted.earned());
        ps.setLong(i++, inserted.playtime());
        ps.setLong(i++, streak.keepBest() ? 1 : 0);
        ps.setLong(i++, streak.carryAdd());
        ps.setLong(i++, streak.carry() ? 1 : 0);
        ps.setLong(i++, streak.peak());
        ps.setLong(i++, streak.keepStreak() ? 1 : 0);
        ps.setLong(i++, streak.addStreak());
        for (Counter counter : COUNTERS) {
            ps.setLong(i++, delta.keeps(counter) ? 1 : 0);
            ps.setLong(i++, delta.added(counter));
        }
    }

    @Override
    public CompletableFuture<Map<Board, List<Leaderboard.Row>>> top(int limit, long kdrMinKills) {
        return this.database.read(c -> {
            Map<Board, List<Leaderboard.Row>> result = new EnumMap<>(Board.class);
            for (Board board : Board.values()) {
                if (board == Board.KDR) {
                    result.put(board, kdr(c, limit, kdrMinKills));
                } else if (board.fromStats()) {
                    result.put(board, column(c, board.column(), limit));
                }
            }
            return result;
        });
    }

    private static List<Leaderboard.Row> column(Connection c, String column, int limit) throws SQLException {
        // column comes from the Board enum, never from input.
        String sql = "SELECT uuid, " + column + " FROM stats WHERE " + column + " > 0 ORDER BY " + column + " DESC, uuid ASC LIMIT ?";
        List<Leaderboard.Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = uuid(rs.getString(1));
                    if (uuid != null) {
                        rows.add(new Leaderboard.Row(uuid, rs.getLong(2), 0));
                    }
                }
            }
        }
        return rows;
    }

    private static List<Leaderboard.Row> kdr(Connection c, int limit, long minKills) throws SQLException {
        // 1.0E0 is a double literal in both SQLite and MySQL, so the division is not rounded to a few decimals.
        String sql = "SELECT uuid, kills, deaths FROM stats WHERE kills >= ? AND kills > 0 "
            + "ORDER BY kills * 1.0E0 / (CASE WHEN deaths > 1 THEN deaths ELSE 1 END) DESC, kills DESC, uuid ASC LIMIT ?";
        List<Leaderboard.Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, minKills);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = uuid(rs.getString(1));
                    if (uuid != null) {
                        rows.add(new Leaderboard.Row(uuid, rs.getLong(2), rs.getLong(3)));
                    }
                }
            }
        }
        return rows;
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
