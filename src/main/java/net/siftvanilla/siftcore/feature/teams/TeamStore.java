package net.siftvanilla.siftcore.feature.teams;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.SqlWork;

/**
 * The SQL of the teams feature ({@code teams} and {@code team_members}, V005 + V035). Builds {@link SqlWork} units
 * that the service queues while it holds the economy lock, so the database sees changes in the same order as
 * memory. Team creation's insert runs inside the creation's ledger transaction.
 */
public final class TeamStore {

    /** A {@code teams} row. */
    public record TeamRow(long id, String name, UUID owner, long created, boolean friendlyFire, TeamHome home, int rankLimit) {
    }

    /** A {@code team_members} row. */
    public record MemberRow(UUID uuid, long team, String role, long joined) {
    }

    /** Everything stored, plus a note for each row that could not be read. */
    public record Rows(List<TeamRow> teams, List<MemberRow> members, List<String> unreadable) {
    }

    private final Database database;
    private final String upsertMember;

    public TeamStore(Database database) {
        this.database = database;
        this.upsertMember = database.dialect().replaceUpsert("team_members", new String[] {"uuid"},
            new String[] {"team_id", "role", "joined"});
    }

    /** Reads every team and member (startup). Rows that cannot be parsed are skipped and reported. */
    public CompletableFuture<Rows> load() {
        return this.database.read(c -> {
            List<String> skipped = new ArrayList<>();
            List<TeamRow> teams = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name, owner, created, "
                + "friendly_fire, home_world, home_x, home_y, home_z, home_yaw, home_pitch, size_limit FROM teams")) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    try {
                        teams.add(new TeamRow(id, rs.getString(2), UUID.fromString(rs.getString(3)), rs.getLong(4),
                            rs.getInt(5) != 0, home(rs), rs.getInt(12)));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        skipped.add("team " + id + ": " + e.getMessage());
                    }
                }
            }
            List<MemberRow> members = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, team_id, role, joined FROM team_members")) {
                while (rs.next()) {
                    String uuid = rs.getString(1);
                    try {
                        members.add(new MemberRow(UUID.fromString(uuid), rs.getLong(2), rs.getString(3), rs.getLong(4)));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        skipped.add("member " + uuid + ": " + e.getMessage());
                    }
                }
            }
            return new Rows(teams, members, skipped);
        });
    }

    private static TeamHome home(ResultSet rs) throws SQLException {
        String world = rs.getString(6);
        if (world == null) {
            return null;
        }
        double x = rs.getDouble(7);
        double y = rs.getDouble(8);
        double z = rs.getDouble(9);
        float yaw = (float) rs.getDouble(10);
        float pitch = (float) rs.getDouble(11);
        try {
            return new TeamHome(world, x, y, z, yaw, pitch);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ write units

    /** Inserts a new team with all its members. */
    public SqlWork<Void> insertTeam(Team team) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO teams (id, name, name_lower, owner, created, friendly_fire, "
                + "home_world, home_x, home_y, home_z, home_yaw, home_pitch, size_limit) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, team.id());
                ps.setString(2, team.name());
                ps.setString(3, team.name().toLowerCase(Locale.ROOT));
                ps.setString(4, team.owner().toString());
                ps.setLong(5, team.created());
                ps.setInt(6, team.friendlyFire() ? 1 : 0);
                setHome(ps, 7, team.home());
                ps.setInt(13, team.ownerRankLimit());
                ps.executeUpdate();
            }
            for (TeamMember member : team.members().values()) {
                writeMember(c, team.id(), member);
            }
            return null;
        };
    }

    /** Adds a member (replacing any stale row of that player). */
    public SqlWork<Void> putMember(long team, TeamMember member) {
        return c -> {
            writeMember(c, team, member);
            return null;
        };
    }

    private void writeMember(Connection c, long team, TeamMember member) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.upsertMember)) {
            ps.setString(1, member.uuid().toString());
            ps.setLong(2, team);
            ps.setString(3, member.role().id());
            ps.setLong(4, member.joined());
            ps.executeUpdate();
        }
    }

    public SqlWork<Void> deleteMember(long team, UUID player) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM team_members WHERE uuid = ? AND team_id = ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, team);
                ps.executeUpdate();
            }
            return null;
        };
    }

    public SqlWork<Void> updateRole(long team, UUID player, TeamRole role) {
        return c -> {
            setRole(c, team, player, role);
            return null;
        };
    }

    private static void setRole(Connection c, long team, UUID player, TeamRole role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE team_members SET role = ? WHERE uuid = ? AND team_id = ?")) {
            ps.setString(1, role.id());
            ps.setString(2, player.toString());
            ps.setLong(3, team);
            ps.executeUpdate();
        }
    }

    /** Stores a new owner: the teams row and both members' roles, as one unit. */
    public SqlWork<Void> transfer(Team after, UUID previousOwner) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE teams SET owner = ?, size_limit = ? WHERE id = ?")) {
                ps.setString(1, after.owner().toString());
                ps.setInt(2, after.ownerRankLimit());
                ps.setLong(3, after.id());
                ps.executeUpdate();
            }
            setRole(c, after.id(), previousOwner, after.role(previousOwner));
            setRole(c, after.id(), after.owner(), TeamRole.OWNER);
            return null;
        };
    }

    /** Removes a team and every membership in it, as one unit. */
    public SqlWork<Void> deleteTeam(long team) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM team_members WHERE team_id = ?")) {
                ps.setLong(1, team);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM teams WHERE id = ?")) {
                ps.setLong(1, team);
                ps.executeUpdate();
            }
            return null;
        };
    }

    public SqlWork<Void> updateHome(long team, TeamHome home) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE teams SET home_world = ?, home_x = ?, home_y = ?, home_z = ?, "
                + "home_yaw = ?, home_pitch = ? WHERE id = ?")) {
                setHome(ps, 1, home);
                ps.setLong(7, team);
                ps.executeUpdate();
            }
            return null;
        };
    }

    public SqlWork<Void> updateFriendlyFire(long team, boolean on) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE teams SET friendly_fire = ? WHERE id = ?")) {
                ps.setInt(1, on ? 1 : 0);
                ps.setLong(2, team);
                ps.executeUpdate();
            }
            return null;
        };
    }

    public SqlWork<Void> updateName(long team, String name) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE teams SET name = ?, name_lower = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setString(2, name.toLowerCase(Locale.ROOT));
                ps.setLong(3, team);
                ps.executeUpdate();
            }
            return null;
        };
    }

    public SqlWork<Void> updateRankLimit(long team, int limit) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE teams SET size_limit = ? WHERE id = ?")) {
                ps.setInt(1, limit);
                ps.setLong(2, team);
                ps.executeUpdate();
            }
            return null;
        };
    }

    private static void setHome(PreparedStatement ps, int first, TeamHome home) throws SQLException {
        if (home == null) {
            ps.setNull(first, Types.VARCHAR);
            for (int i = 1; i <= 5; i++) {
                ps.setNull(first + i, Types.DOUBLE);
            }
            return;
        }
        ps.setString(first, home.world());
        ps.setDouble(first + 1, home.x());
        ps.setDouble(first + 2, home.y());
        ps.setDouble(first + 3, home.z());
        ps.setDouble(first + 4, home.yaw());
        ps.setDouble(first + 5, home.pitch());
    }

    // ------------------------------------------------------------------ repairs and checks

    /** Applies the repairs the loader decided on, as one unit. */
    public SqlWork<Void> repair(List<TeamLoader.Repair> repairs) {
        return c -> {
            for (TeamLoader.Repair repair : repairs) {
                switch (repair) {
                    case TeamLoader.Repair.DeleteMember r -> {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM team_members WHERE uuid = ? AND team_id = ?")) {
                            ps.setString(1, r.player().toString());
                            ps.setLong(2, r.team());
                            ps.executeUpdate();
                        }
                    }
                    case TeamLoader.Repair.DeleteTeam r -> {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM teams WHERE id = ?")) {
                            ps.setLong(1, r.team());
                            ps.executeUpdate();
                        }
                    }
                    case TeamLoader.Repair.SetOwner r -> {
                        try (PreparedStatement ps = c.prepareStatement("UPDATE teams SET owner = ? WHERE id = ?")) {
                            ps.setString(1, r.owner().toString());
                            ps.setLong(2, r.team());
                            ps.executeUpdate();
                        }
                    }
                    case TeamLoader.Repair.SetRole r -> setRole(c, r.team(), r.player(), r.role());
                }
            }
            return null;
        };
    }

    /**
     * The highest team id ever used: the largest stored team, or the largest id in a team creation's ledger rows,
     * so a disbanded team's id is never handed out again and ledger references stay unambiguous.
     */
    public CompletableFuture<Long> highestId(String createKind) {
        return this.database.read(c -> {
            long highest = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM teams")) {
                if (rs.next()) {
                    highest = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ref FROM ledger WHERE kind = ? AND ref IS NOT NULL")) {
                ps.setString(1, createKind);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            highest = Math.max(highest, Long.parseLong(rs.getString(1).trim()));
                        } catch (NumberFormatException ignored) {
                            // Not a team id; written by something else under the same kind.
                        }
                    }
                }
            }
            return highest;
        });
    }

    /** Row counts of {@code teams} and {@code team_members}, read after every queued write is committed. */
    public CompletableFuture<long[]> counts() {
        return this.database.write(c -> null).thenCompose(ignored -> this.database.read(c -> {
            long[] counts = new long[2];
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM teams")) {
                    counts[0] = rs.next() ? rs.getLong(1) : 0;
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM team_members")) {
                    counts[1] = rs.next() ? rs.getLong(1) : 0;
                }
            }
            return counts;
        }));
    }

    /** Players per query of {@link #settingRows} (well below every database's parameter limit). */
    static final int SETTING_BATCH = 200;

    /**
     * The stored rows of one player setting for many players at once ({@code settings} table, player to value; players
     * without a row are left out), read in the writer's order so a change queued before is seen. For offline members,
     * whose settings are not in memory.
     */
    public CompletableFuture<Map<UUID, String>> settingRows(String setting, Collection<UUID> players) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(players));
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return this.database.write(c -> {
            Map<UUID, String> rows = new HashMap<>();
            for (int from = 0; from < ids.size(); from += SETTING_BATCH) {
                List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + SETTING_BATCH));
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, value FROM settings WHERE setting = ? AND uuid IN ("
                    + "?, ".repeat(batch.size() - 1) + "?)")) {
                    ps.setString(1, setting);
                    for (int i = 0; i < batch.size(); i++) {
                        ps.setString(i + 2, batch.get(i).toString());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                rows.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                            } catch (IllegalArgumentException ignored) {
                                // Not a player row; the settings table is keyed by uuid text.
                            }
                        }
                    }
                }
            }
            return rows;
        });
    }

    /** Queues a write. */
    public CompletableFuture<Void> write(SqlWork<Void> work) {
        return this.database.write(work);
    }
}
