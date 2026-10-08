package net.siftvanilla.siftcore.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

    private static final Map<String, String> FILES = Map.of(
        "db/migrations/V001.sql", "-- base\nCREATE TABLE a (id INTEGER NOT NULL PRIMARY KEY);\n",
        "db/migrations/V020.sql", "-- orders range, shipped in a later release\nCREATE TABLE b (id INTEGER NOT NULL PRIMARY KEY);\n",
        "db/migrations/V035.sql", "-- teams range\nCREATE TABLE c (id INTEGER NOT NULL PRIMARY KEY);\n");

    private static Function<String, InputStream> resources(String... present) {
        List<String> allowed = List.of(present);
        return name -> allowed.contains(name) ? new ByteArrayInputStream(FILES.get(name).getBytes(StandardCharsets.UTF_8)) : null;
    }

    private static List<Integer> applied(JdbcDatabase database) throws Exception {
        return database.read(c -> {
            List<Integer> versions = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT version FROM schema_version ORDER BY version")) {
                while (rs.next()) {
                    versions.add(rs.getInt(1));
                }
            }
            return versions;
        }).get();
    }

    @Test
    void appliesALowerNumberedMigrationShippedAfterAHigherOne(@TempDir Path dir) throws Exception {
        Logger logger = Logger.getLogger("siftcore-test");
        JdbcDatabase database = new JdbcDatabase(new SqliteSource(dir.resolve("m.db"), 2), logger);
        try {
            Function<String, InputStream> first = resources("db/migrations/V001.sql", "db/migrations/V035.sql");
            assertEquals(35, new Migrations(database, logger, first, Migrations.discover(first)).migrate());
            assertEquals(List.of(1, 35), applied(database));

            Function<String, InputStream> later = resources("db/migrations/V001.sql", "db/migrations/V020.sql", "db/migrations/V035.sql");
            assertEquals(35, new Migrations(database, logger, later, Migrations.discover(later)).migrate());
            assertEquals(List.of(1, 20, 35), applied(database));
            assertTrue(database.read(c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM b")) {
                    return rs.next();
                }
            }).get(), "V020's table exists");

            // Running again applies nothing new.
            assertEquals(35, new Migrations(database, logger, later, Migrations.discover(later)).migrate());
            assertEquals(List.of(1, 20, 35), applied(database));
        } finally {
            database.close();
        }
    }
}
