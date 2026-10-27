package net.siftvanilla.siftcore.feature.crates;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/** The crate log: one row per crate opened, written in the opening's transaction. */
final class CrateLog {

    /** One opening. */
    record Entry(long id, long timestamp, UUID player, String crate, String reward, String detail) {
    }

    private final Database database;

    CrateLog(Database database) {
        this.database = database;
    }

    /** Adds the log row of an opening to its transaction. */
    void add(LedgerTx.Builder tx, long timestamp, UUID player, String crate, String reward, String detail) {
        String safeDetail = detail == null ? null : detail.length() > 255 ? detail.substring(0, 255) : detail;
        String safeReward = reward.length() > 64 ? reward.substring(0, 64) : reward;
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO crate_log (ts, uuid, crate, reward, detail) VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, timestamp);
                ps.setString(2, player.toString());
                ps.setString(3, crate);
                ps.setString(4, safeReward);
                if (safeDetail == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, safeDetail);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** A player's latest openings, newest first. */
    CompletableFuture<List<Entry>> recent(UUID player, int limit, int offset) {
        return this.database.read(c -> {
            List<Entry> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, ts, uuid, crate, reward, detail FROM crate_log WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, player.toString());
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Entry(rs.getLong(1), rs.getLong(2), UUID.fromString(rs.getString(3)), rs.getString(4),
                            rs.getString(5), rs.getString(6)));
                    }
                }
            }
            return rows;
        });
    }

    /** How many crates a player opened in total. */
    CompletableFuture<Long> count(UUID player) {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM crate_log WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }
}
