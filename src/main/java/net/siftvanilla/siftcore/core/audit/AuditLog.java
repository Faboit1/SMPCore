package net.siftvanilla.siftcore.core.audit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.storage.Database;

/** Append-only record of staff actions and sensitive events (admin money changes, crate rewards, punishments). */
public final class AuditLog {

    /** One audit row. */
    public record Entry(long id, long timestamp, String actor, String action, String target, String details) {
    }

    private final Database database;

    public AuditLog(Database database) {
        this.database = database;
    }

    /**
     * Records an action.
     *
     * @param actor   a player UUID, {@code console} or {@code system}
     * @param action  short machine id, e.g. {@code eco.give}
     * @param target  what it affected (UUID, listing id, ...), may be null
     * @param details free text, truncated to 1024 characters
     */
    public CompletableFuture<Void> record(String actor, String action, String target, String details) {
        long now = System.currentTimeMillis();
        String safeDetails = details == null ? null : details.length() > 1024 ? details.substring(0, 1024) : details;
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log (ts, actor, action, target, details) VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, now);
                ps.setString(2, actor);
                ps.setString(3, action);
                if (target == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, target.length() > 64 ? target.substring(0, 64) : target);
                }
                if (safeDetails == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, safeDetails);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Newest entries first, optionally filtered by action prefix and/or target. */
    public CompletableFuture<List<Entry>> recent(String actionPrefix, String target, int limit) {
        return this.database.read(c -> {
            StringBuilder sql = new StringBuilder("SELECT id, ts, actor, action, target, details FROM audit_log WHERE 1 = 1");
            if (actionPrefix != null) {
                sql.append(" AND action LIKE ?");
            }
            if (target != null) {
                sql.append(" AND target = ?");
            }
            sql.append(" ORDER BY id DESC LIMIT ?");
            List<Entry> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int i = 1;
                if (actionPrefix != null) {
                    ps.setString(i++, actionPrefix.replace("%", "") + "%");
                }
                if (target != null) {
                    ps.setString(i++, target);
                }
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
                    }
                }
            }
            return rows;
        });
    }
}
