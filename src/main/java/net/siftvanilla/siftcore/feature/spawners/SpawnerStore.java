package net.siftvanilla.siftcore.feature.spawners;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.SqlWork;

/**
 * The {@code spawners} and {@code spawner_items} tables. A spawner row is written when it is placed, its stack when
 * it changes, and its storage (XP and item rows) as an absolute snapshot: by the transactions that take items out
 * (in the same database unit as the money or claim box rows) and by the write-behind flush for loot. Snapshots are
 * always taken and queued under the economy lock, so the ordered writer applies them in the order they happened.
 */
final class SpawnerStore {

    /** One spawner as stored. */
    record Row(long id, SpawnerPos pos, UUID owner, String mob, int stack, long xp, long created, Map<String, Long> items) {
    }

    /** A spawner's storage at one moment. */
    record Snapshot(long id, long xp, Map<String, Long> items) {
    }

    private final Database database;
    private final Logger logger;

    SpawnerStore(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    Database database() {
        return this.database;
    }

    /** Deletes item rows whose spawner no longer exists (left behind by a failed write); returns how many. */
    int removeOrphanItems() throws Exception {
        return this.database.write(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("DELETE FROM spawner_items WHERE spawner_id NOT IN (SELECT id FROM spawners)");
            }
        }).get();
    }

    /** Every spawner with its storage. Blocking; call at startup only. */
    List<Row> loadAll() throws Exception {
        return this.database.read(c -> {
            Map<Long, Map<String, Long>> items = new HashMap<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT spawner_id, item_type, amount FROM spawner_items WHERE amount > 0")) {
                while (rs.next()) {
                    items.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                }
            }
            List<Row> rows = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, world, x, y, z, owner, mob, stack, xp, created FROM spawners ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    UUID owner;
                    try {
                        owner = UUID.fromString(rs.getString(6));
                    } catch (IllegalArgumentException e) {
                        this.logger.severe("Spawner " + id + " has an unreadable owner and was skipped (it stays in the database)");
                        continue;
                    }
                    rows.add(new Row(id, new SpawnerPos(rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)), owner,
                        rs.getString(7), rs.getInt(8), rs.getLong(9), rs.getLong(10), items.getOrDefault(id, Map.of())));
                }
            }
            return rows;
        }).get();
    }

    /** Inserts a new spawner (part of the placing transaction). */
    static SqlWork<Void> insert(ManagedSpawner spawner) {
        long id = spawner.id;
        SpawnerPos pos = spawner.pos;
        String owner = spawner.owner.toString();
        String mob = spawner.mob;
        int stack = spawner.stack();
        long created = spawner.created;
        return c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO spawners (id, world, x, y, z, owner, mob, stack, xp, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)")) {
                ps.setLong(1, id);
                ps.setString(2, pos.world());
                ps.setInt(3, pos.x());
                ps.setInt(4, pos.y());
                ps.setInt(5, pos.z());
                ps.setString(6, owner);
                ps.setString(7, mob);
                ps.setInt(8, stack);
                ps.setLong(9, created);
                ps.executeUpdate();
            }
            return null;
        };
    }

    /** Sets a spawner's stack to {@code stack} (part of the stacking transaction). */
    static SqlWork<Void> updateStack(long id, int stack) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE spawners SET stack = ? WHERE id = ?")) {
                ps.setInt(1, stack);
                ps.setLong(2, id);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("Spawner " + id + " is not stored");
                }
            }
            return null;
        };
    }

    /** Deletes a spawner and its storage (part of the pickup transaction). */
    static SqlWork<Void> delete(long id) {
        return c -> {
            try (PreparedStatement items = c.prepareStatement("DELETE FROM spawner_items WHERE spawner_id = ?");
                 PreparedStatement spawner = c.prepareStatement("DELETE FROM spawners WHERE id = ?")) {
                items.setLong(1, id);
                items.executeUpdate();
                spawner.setLong(1, id);
                spawner.executeUpdate();
            }
            return null;
        };
    }

    /** Writes storage snapshots: each spawner's XP, and exactly its item rows. */
    static SqlWork<Void> persist(List<Snapshot> snapshots) {
        List<Snapshot> copy = List.copyOf(snapshots);
        return c -> {
            write(c, copy);
            return null;
        };
    }

    static SqlWork<Void> persist(Snapshot snapshot) {
        return persist(List.of(snapshot));
    }

    private static void write(Connection c, List<Snapshot> snapshots) throws SQLException {
        try (PreparedStatement xp = c.prepareStatement("UPDATE spawners SET xp = ? WHERE id = ?");
             PreparedStatement clear = c.prepareStatement("DELETE FROM spawner_items WHERE spawner_id = ?");
             PreparedStatement insert = c.prepareStatement("INSERT INTO spawner_items (spawner_id, item_type, amount) VALUES (?, ?, ?)")) {
            for (Snapshot snapshot : snapshots) {
                xp.setLong(1, snapshot.xp());
                xp.setLong(2, snapshot.id());
                xp.addBatch();
                clear.setLong(1, snapshot.id());
                clear.addBatch();
                for (Map.Entry<String, Long> item : snapshot.items().entrySet()) {
                    if (item.getValue() > 0) {
                        insert.setLong(1, snapshot.id());
                        insert.setString(2, item.getKey());
                        insert.setLong(3, item.getValue());
                        insert.addBatch();
                    }
                }
            }
            xp.executeBatch();
            clear.executeBatch();
            insert.executeBatch();
        }
    }

    /** Queues a write outside any transaction. Call under the economy lock when it carries a storage snapshot. */
    CompletableFuture<Void> write(SqlWork<Void> work) {
        return this.database.write(work);
    }

    /** Number of stored spawner rows, for the self-test. */
    CompletableFuture<Long> count() {
        return this.database.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM spawners")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }
}
