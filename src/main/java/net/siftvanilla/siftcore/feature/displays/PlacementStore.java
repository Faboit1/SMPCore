package net.siftvanilla.siftcore.feature.displays;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.storage.Database;

/**
 * The {@code displays} table: positions set in-game. Reads run on the read pool, writes on the ordered writer;
 * nothing here blocks a world thread. Writes never throw for expected outcomes (a name that is already taken, a
 * row that is already gone), so they never count as failed writes.
 */
final class PlacementStore {

    private static final String[] KEY = {"id"};
    private static final String[] VALUES = {"template", "world", "x", "y", "z", "yaw", "placed_by", "placed_at"};

    private final Database database;

    PlacementStore(Database database) {
        this.database = database;
    }

    CompletableFuture<Map<String, Placement>> loadAll() {
        return this.database.read(c -> {
            Map<String, Placement> rows = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, template, world, x, y, z, yaw, placed_by, placed_at FROM displays ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DisplayPosition position = new DisplayPosition(rs.getString(3), rs.getDouble(4), rs.getDouble(5),
                        rs.getDouble(6), (float) rs.getDouble(7));
                    rows.put(rs.getString(1), new Placement(rs.getString(1), rs.getString(2), position, rs.getString(8), rs.getLong(9)));
                }
            }
            return rows;
        });
    }

    /** Adds a new row; completes with false (and changes nothing) when the name is already taken. */
    CompletableFuture<Boolean> insert(Placement placement) {
        return this.database.write(c -> {
            try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM displays WHERE id = ?")) {
                check.setString(1, placement.id());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO displays (id, template, world, x, y, z, yaw, placed_by, placed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, placement.id());
                bindValues(ps, 2, placement);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** Inserts or replaces the row. */
    CompletableFuture<Void> save(Placement placement) {
        String sql = this.database.dialect().replaceUpsert("displays", KEY, VALUES);
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, placement.id());
                bindValues(ps, 2, placement);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Deletes the row; completes with whether there was one. */
    CompletableFuture<Boolean> delete(String id) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM displays WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static void bindValues(PreparedStatement ps, int first, Placement placement) throws java.sql.SQLException {
        int i = first;
        if (placement.template() == null) {
            ps.setNull(i++, Types.VARCHAR);
        } else {
            ps.setString(i++, placement.template());
        }
        DisplayPosition position = placement.position();
        ps.setString(i++, position.world());
        ps.setDouble(i++, position.x());
        ps.setDouble(i++, position.y());
        ps.setDouble(i++, position.z());
        ps.setDouble(i++, position.yaw());
        if (placement.placedBy() == null) {
            ps.setNull(i++, Types.VARCHAR);
        } else {
            ps.setString(i++, placement.placedBy());
        }
        ps.setLong(i, placement.placedAt());
    }
}
