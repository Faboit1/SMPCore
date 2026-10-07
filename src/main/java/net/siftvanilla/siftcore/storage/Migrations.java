package net.siftvanilla.siftcore.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Versioned schema migrations. Each migration is a bundled SQL file {@code db/migrations/V###__name.sql} written with
 * {@link Dialect} tokens; statements are separated by a line ending in {@code ;}. Applied versions are recorded in
 * {@code schema_version}, and every migration runs in one transaction on the writer thread.
 */
public final class Migrations {

    /** One migration file. */
    public record Migration(int version, String name, String resource) {
    }

    private final Database database;
    private final Logger logger;
    private final Function<String, InputStream> resources;
    private final List<Migration> migrations;

    public Migrations(Database database, Logger logger, Function<String, InputStream> resources, List<Migration> migrations) {
        this.database = database;
        this.logger = logger;
        this.resources = resources;
        this.migrations = List.copyOf(migrations);
    }

    /**
     * Finds every bundled migration {@code db/migrations/V001.sql} to {@code V199.sql}. Numbers may have gaps (each
     * feature owns a range), so every number is probed. The first comment line is the migration's name.
     */
    public static List<Migration> discover(Function<String, InputStream> resources) throws IOException {
        List<Migration> found = new ArrayList<>();
        for (int version = 1; version <= 199; version++) {
            String resource = String.format(java.util.Locale.ROOT, "db/migrations/V%03d.sql", version);
            try (InputStream in = resources.apply(resource)) {
                if (in == null) {
                    continue;
                }
                String first = new String(in.readNBytes(256), StandardCharsets.UTF_8).lines().findFirst().orElse("");
                String name = first.startsWith("--") ? first.substring(2).strip() : "V" + version;
                if (name.length() > 120) {
                    name = name.substring(0, 120);
                }
                found.add(new Migration(version, name, resource));
            }
        }
        return found;
    }

    /** Applies every pending migration in order and completes with the resulting schema version. */
    public int migrate() throws Exception {
        Dialect dialect = this.database.dialect();
        this.database.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate(dialect.expand("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INTEGER NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, applied_at {bigint} NOT NULL){engine}"));
            }
            return null;
        }).get();
        int current = this.database.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }).get();
        for (Migration migration : this.migrations) {
            if (migration.version() <= current) {
                continue;
            }
            List<String> statements = load(migration, dialect);
            this.database.write(c -> {
                for (String sql : statements) {
                    try (Statement st = c.createStatement()) {
                        st.executeUpdate(sql);
                    } catch (SQLException e) {
                        throw new SQLException("Migration V" + migration.version() + " failed on: " + sql, e);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO schema_version (version, name, applied_at) VALUES (?, ?, ?)")) {
                    ps.setInt(1, migration.version());
                    ps.setString(2, migration.name());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return null;
            }).get();
            this.logger.info("Applied database migration V" + migration.version() + " (" + migration.name() + ")");
            current = migration.version();
        }
        return current;
    }

    private List<String> load(Migration migration, Dialect dialect) throws IOException {
        String text;
        try (InputStream in = this.resources.apply(migration.resource())) {
            if (in == null) {
                throw new IOException("Missing migration resource " + migration.resource());
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().strip();
                statements.add(dialect.expand(sql.substring(0, sql.length() - 1)));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(dialect.expand(current.toString().strip()));
        }
        return statements;
    }
}
