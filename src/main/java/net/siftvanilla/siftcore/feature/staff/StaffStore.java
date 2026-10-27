package net.siftvanilla.siftcore.feature.staff;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.economy.IdSequence;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Every row of the staff tables (V090): punishments, reports, vanish and freeze state. Writes go through the
 * ordered database writer; counts used by the self-test are queued as writes too, so they see every change queued
 * before them.
 */
final class StaffStore {

    /** A frozen player and who froze them. */
    record Freeze(UUID player, String staff, String staffName, long since) {
    }

    private static final String PUNISHMENT_COLUMNS =
        "id, type, target, target_name, staff, staff_name, reason, created, expires, revoked, revoked_by";
    private static final String REPORT_COLUMNS =
        "id, reporter, reporter_name, target, target_name, reason, created, state, closed_by, closed_at";

    private final Database database;
    private final Logger logger;
    private volatile IdSequence punishmentIds;
    private volatile IdSequence reportIds;

    StaffStore(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** Seeds the id sequences; call once from enable (blocks on storage). */
    void open() throws Exception {
        this.punishmentIds = IdSequence.forTable(this.database, "staff_punishments");
        this.reportIds = IdSequence.forTable(this.database, "staff_reports");
    }

    long nextPunishmentId() {
        return this.punishmentIds.next();
    }

    long nextReportId() {
        return this.reportIds.next();
    }

    // ------------------------------------------------------------------ punishments

    /** Bans and mutes in force at {@code now}. */
    CompletableFuture<List<Punishment>> activePunishments(long now) {
        return this.database.read(c -> {
            List<Punishment> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + PUNISHMENT_COLUMNS + " FROM staff_punishments "
                + "WHERE type IN ('BAN', 'MUTE') AND revoked IS NULL AND (expires IS NULL OR expires > ?)")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Punishment punishment = punishment(rs);
                        if (punishment != null) {
                            rows.add(punishment);
                        }
                    }
                }
            }
            return rows;
        });
    }

    CompletableFuture<Void> insert(Punishment punishment, boolean notified) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO staff_punishments (" + PUNISHMENT_COLUMNS
                + ", notified) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, punishment.id());
                ps.setString(2, punishment.type().id());
                ps.setString(3, punishment.target().toString());
                ps.setString(4, truncate(punishment.targetName(), 16));
                ps.setString(5, truncate(punishment.staff(), 36));
                ps.setString(6, truncate(punishment.staffName(), 32));
                ps.setString(7, truncate(punishment.reason(), 255));
                ps.setLong(8, punishment.created());
                if (!punishment.type().lasting() || punishment.permanent()) {
                    ps.setNull(9, Types.BIGINT);
                } else {
                    ps.setLong(9, punishment.expires());
                }
                ps.setNull(10, Types.BIGINT);
                ps.setNull(11, Types.VARCHAR);
                ps.setInt(12, notified ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Marks a ban or mute as lifted. Completes with false when it was already lifted. */
    CompletableFuture<Boolean> lift(long id, long when, String by) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE staff_punishments SET revoked = ?, revoked_by = ? WHERE id = ? AND revoked IS NULL")) {
                ps.setLong(1, when);
                ps.setString(2, truncate(by, 32));
                ps.setLong(3, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * A player's punishments, newest first. Queued behind pending writes (it runs on the writer), so a punishment
     * given a moment ago is always in it.
     */
    CompletableFuture<List<Punishment>> history(UUID target, int limit) {
        return this.database.write(c -> {
            List<Punishment> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + PUNISHMENT_COLUMNS
                + " FROM staff_punishments WHERE target = ? ORDER BY created DESC, id DESC LIMIT ?")) {
                ps.setString(1, target.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Punishment punishment = punishment(rs);
                        if (punishment != null) {
                            rows.add(punishment);
                        }
                    }
                }
            }
            return rows;
        });
    }

    /** Warnings the player has not seen yet, oldest first. */
    CompletableFuture<List<Punishment>> unseenWarnings(UUID target) {
        return this.database.read(c -> {
            List<Punishment> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + PUNISHMENT_COLUMNS
                + " FROM staff_punishments WHERE target = ? AND type = 'WARN' AND notified = 0 ORDER BY created ASC")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Punishment punishment = punishment(rs);
                        if (punishment != null) {
                            rows.add(punishment);
                        }
                    }
                }
            }
            return rows;
        });
    }

    CompletableFuture<Void> markNotified(List<Long> ids) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE staff_punishments SET notified = 1 WHERE id = ?")) {
                for (long id : ids) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Bans or mutes in force at {@code now}, counted after every write queued so far. */
    CompletableFuture<Integer> countActive(PunishmentType type, long now) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(DISTINCT target) FROM staff_punishments "
                + "WHERE type = ? AND revoked IS NULL AND (expires IS NULL OR expires > ?)")) {
                ps.setString(1, type.id());
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private Punishment punishment(ResultSet rs) throws SQLException {
        long id = rs.getLong(1);
        PunishmentType type = PunishmentType.parse(rs.getString(2));
        if (type == null) {
            this.logger.warning("Punishment " + id + " has an unknown type '" + rs.getString(2) + "' and was skipped");
            return null;
        }
        try {
            UUID target = UUID.fromString(rs.getString(3));
            long expires = rs.getLong(9);
            boolean permanent = rs.wasNull();
            long revoked = rs.getLong(10);
            if (rs.wasNull()) {
                revoked = Punishment.NONE;
            }
            long end = type.lasting() ? (permanent ? Punishment.PERMANENT : Math.max(1L, expires)) : Punishment.NONE;
            return new Punishment(id, type, target, rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getLong(8), end, revoked, rs.getString(11));
        } catch (IllegalArgumentException e) {
            this.logger.log(Level.WARNING, "Punishment " + id + " has unreadable data and was skipped", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ reports

    CompletableFuture<List<Report>> openReports() {
        return this.database.read(c -> {
            List<Report> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + REPORT_COLUMNS
                + " FROM staff_reports WHERE state = 'OPEN' ORDER BY id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Report report = report(rs);
                        if (report != null) {
                            rows.add(report);
                        }
                    }
                }
            }
            return rows;
        });
    }

    CompletableFuture<Void> insert(Report report) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO staff_reports (" + REPORT_COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, report.id());
                ps.setString(2, report.reporter().toString());
                ps.setString(3, truncate(report.reporterName(), 16));
                ps.setString(4, report.target().toString());
                ps.setString(5, truncate(report.targetName(), 16));
                ps.setString(6, truncate(report.reason(), 255));
                ps.setLong(7, report.created());
                ps.setString(8, report.state().name());
                ps.setNull(9, Types.VARCHAR);
                ps.setNull(10, Types.BIGINT);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Closes an open report. Completes with false when it was not open any more. */
    CompletableFuture<Boolean> close(Report closed) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE staff_reports SET state = ?, closed_by = ?, closed_at = ? WHERE id = ? AND state = 'OPEN'")) {
                ps.setString(1, closed.state().name());
                ps.setString(2, truncate(closed.closedBy(), 32));
                ps.setLong(3, closed.closedAt());
                ps.setLong(4, closed.id());
                return ps.executeUpdate() == 1;
            }
        });
    }

    CompletableFuture<Integer> countOpenReports() {
        return count("SELECT COUNT(*) FROM staff_reports WHERE state = 'OPEN'");
    }

    private Report report(ResultSet rs) throws SQLException {
        long id = rs.getLong(1);
        ReportState state = ReportState.parse(rs.getString(8));
        try {
            if (state == null) {
                throw new IllegalArgumentException("unknown state " + rs.getString(8));
            }
            long closedAt = rs.getLong(10);
            return new Report(id, UUID.fromString(rs.getString(2)), rs.getString(3), UUID.fromString(rs.getString(4)),
                rs.getString(5), rs.getString(6), rs.getLong(7), state, rs.getString(9), rs.wasNull() ? 0L : closedAt);
        } catch (IllegalArgumentException e) {
            this.logger.log(Level.WARNING, "Report " + id + " has unreadable data and was skipped", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ vanish

    CompletableFuture<Set<UUID>> vanished() {
        return this.database.read(c -> {
            Set<UUID> result = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM staff_vanish");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        result.add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException e) {
                        this.logger.warning("Skipped a vanish row with a bad uuid '" + rs.getString(1) + "'");
                    }
                }
            }
            return result;
        });
    }

    CompletableFuture<Void> vanish(UUID player, long since) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(this.database.dialect().replaceUpsert("staff_vanish",
                new String[] {"uuid"}, new String[] {"since"}))) {
                ps.setString(1, player.toString());
                ps.setLong(2, since);
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Void> unvanish(UUID player) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM staff_vanish WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Integer> countVanished() {
        return count("SELECT COUNT(*) FROM staff_vanish");
    }

    // ------------------------------------------------------------------ freeze

    CompletableFuture<Map<UUID, Freeze>> frozen() {
        return this.database.read(c -> {
            Map<UUID, Freeze> result = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, staff, staff_name, since FROM staff_freeze");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID player = UUID.fromString(rs.getString(1));
                        result.put(player, new Freeze(player, rs.getString(2), rs.getString(3), rs.getLong(4)));
                    } catch (IllegalArgumentException e) {
                        this.logger.warning("Skipped a freeze row with a bad uuid '" + rs.getString(1) + "'");
                    }
                }
            }
            return result;
        });
    }

    CompletableFuture<Void> freeze(Freeze freeze) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(this.database.dialect().replaceUpsert("staff_freeze",
                new String[] {"uuid"}, new String[] {"staff", "staff_name", "since"}))) {
                ps.setString(1, freeze.player().toString());
                ps.setString(2, truncate(freeze.staff(), 36));
                ps.setString(3, truncate(freeze.staffName(), 32));
                ps.setLong(4, freeze.since());
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Void> unfreeze(UUID player) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM staff_freeze WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Integer> countFrozen() {
        return count("SELECT COUNT(*) FROM staff_freeze");
    }

    // ------------------------------------------------------------------ lookups

    /** Players whose last address has this hash, most recently seen first. */
    CompletableFuture<List<PlayerDirectory.Known>> sharingAddress(String ipHash, int limit) {
        return this.database.read(c -> {
            List<PlayerDirectory.Known> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, first_join, last_seen, ip_hash FROM players "
                + "WHERE ip_hash = ? ORDER BY last_seen DESC LIMIT ?")) {
                ps.setString(1, ipHash);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            rows.add(new PlayerDirectory.Known(UUID.fromString(rs.getString(1)), rs.getString(2),
                                rs.getLong(3), rs.getLong(4), rs.getString(5)));
                        } catch (IllegalArgumentException e) {
                            this.logger.warning("Skipped a player row with a bad uuid '" + rs.getString(1) + "'");
                        }
                    }
                }
            }
            return rows;
        });
    }

    // ------------------------------------------------------------------ helpers

    /** A count run as a queued write, so it sees every write queued before it. */
    private CompletableFuture<Integer> count(String sql) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
