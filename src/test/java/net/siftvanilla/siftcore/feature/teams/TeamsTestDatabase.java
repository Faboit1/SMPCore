package net.siftvanilla.siftcore.feature.teams;

import java.nio.file.Path;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;

/** A fully migrated SQLite database in a temporary folder, for the teams tests. */
final class TeamsTestDatabase {

    private TeamsTestDatabase() {
    }

    static JdbcDatabase open(Path dir) throws Exception {
        Logger logger = Logger.getLogger("teams-test");
        JdbcDatabase database = new JdbcDatabase(new SqliteSource(dir.resolve("teams-test.db"), 2), logger);
        ClassLoader loader = TeamsTestDatabase.class.getClassLoader();
        new Migrations(database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        return database;
    }
}
