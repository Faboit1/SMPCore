package net.siftvanilla.siftcore.feature.boosters;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.SqlWork;

/** The {@code boosters} table: loading the boosters still in line and the SQL every change writes. */
final class BoosterStore {

    private static final String COLUMNS = "id, kind, percent, seconds, remaining, state, owner, source, ref, reason, actor, created, started, ended";
    private static final String INSERT = "INSERT INTO boosters (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE boosters SET state = ?, remaining = ?, started = ?, ended = ? WHERE id = ?";
    private static final String REMAINING = "UPDATE boosters SET remaining = ? WHERE id = ? AND state = 'active'";

    private final Database database;

    BoosterStore(Database database) {
        this.database = database;
    }

    /** Every booster that was running or waiting. Blocking; startup only. */
    List<Booster> loadOpen() throws Exception {
        return this.database.read(c -> {
            List<Booster> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM boosters WHERE state IN ('queued', 'active')")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Booster booster = read(rs);
                        if (booster != null) {
                            list.add(booster);
                        }
                    }
                }
            }
            return list;
        }).get();
    }

    private static Booster read(ResultSet rs) throws SQLException {
        int percent = rs.getInt(3);
        long seconds = rs.getLong(4);
        if (percent < 1 || seconds < 1) {
            return null;
        }
        String owner = rs.getString(7);
        UUID ownerId;
        try {
            ownerId = owner == null ? null : UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            ownerId = null;
        }
        return new Booster(rs.getLong(1), rs.getString(2), percent, seconds, rs.getLong(5), Booster.State.byId(rs.getString(6)),
            ownerId, Booster.Source.byId(rs.getString(8)), rs.getString(9), rs.getString(10), rs.getString(11), rs.getLong(12),
            rs.getLong(13), rs.getLong(14));
    }

    /** Writes a new booster row. */
    static SqlWork<Integer> insert(Booster booster) {
        return c -> insert(c, booster);
    }

    static int insert(Connection c, Booster booster) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setLong(1, booster.id());
            ps.setString(2, booster.kind());
            ps.setInt(3, booster.percent());
            ps.setLong(4, booster.seconds());
            ps.setLong(5, booster.remaining());
            ps.setString(6, booster.state().id());
            if (booster.owner() == null) {
                ps.setNull(7, Types.CHAR);
            } else {
                ps.setString(7, booster.owner().toString());
            }
            ps.setString(8, booster.source().id());
            ps.setString(9, booster.ref());
            ps.setString(10, cut(booster.reason(), 128));
            ps.setString(11, cut(booster.actor(), 36));
            ps.setLong(12, booster.created());
            ps.setLong(13, booster.started());
            ps.setLong(14, booster.ended());
            return ps.executeUpdate();
        }
    }

    /** Writes the state of boosters that changed (started, ended, stopped, revoked, back in line). */
    static void update(Connection c, List<Booster> changed) throws SQLException {
        if (changed.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(UPDATE)) {
            for (Booster booster : changed) {
                ps.setString(1, booster.state().id());
                ps.setLong(2, booster.remaining());
                ps.setLong(3, booster.started());
                ps.setLong(4, booster.ended());
                ps.setLong(5, booster.id());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Stores how much time the running booster has left. */
    static SqlWork<Integer> remaining(Booster active) {
        long id = active.id();
        long left = active.remaining();
        return c -> {
            try (PreparedStatement ps = c.prepareStatement(REMAINING)) {
                ps.setLong(1, left);
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        };
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
