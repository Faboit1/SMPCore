package net.siftvanilla.siftcore.feature.combat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.storage.Database;

/**
 * The {@code kills} table: one row per player kill, counted or not, with the reason when not. Written behind (a
 * kill is never waited on) and read for the repeated-pair cache at startup and the staff kill history.
 */
final class KillLog {

    /** One logged kill. {@code reason} is null for counted kills. */
    record Row(long id, UUID killer, UUID victim, long at, boolean counted, String reason) {
    }

    /** The last counted kill of a pair. */
    record CountedPair(UUID killer, UUID victim, long at) {
    }

    private final Database database;

    KillLog(Database database) {
        this.database = database;
    }

    /** Queues a row; the write never blocks the caller. */
    CompletableFuture<Void> record(UUID killer, UUID victim, long at, boolean counted, String reason) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kills (killer, victim, ts, counted, reason) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, killer.toString());
                ps.setString(2, victim.toString());
                ps.setLong(3, at);
                ps.setInt(4, counted ? 1 : 0);
                if (reason == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, reason);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The newest counted kill of every pair since {@code since} (epoch millis). */
    CompletableFuture<List<CountedPair>> countedSince(long since) {
        return this.database.read(c -> {
            List<CountedPair> pairs = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT killer, victim, MAX(ts) FROM kills WHERE counted = 1 AND ts >= ? GROUP BY killer, victim")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pairs.add(new CountedPair(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getLong(3)));
                    }
                }
            }
            return pairs;
        });
    }

    /** Kills where the player was the killer or the victim, newest first. */
    CompletableFuture<List<Row>> involving(UUID player, int limit, int offset) {
        return this.database.read(c -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, killer, victim, ts, counted, reason FROM kills "
                + "WHERE killer = ? OR victim = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                ps.setInt(3, limit);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getLong(1), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                            rs.getLong(4), rs.getInt(5) != 0, rs.getString(6)));
                    }
                }
            }
            return rows;
        });
    }

    /** Number of rows (self-test). */
    CompletableFuture<Long> count() {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM kills"); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }
}
