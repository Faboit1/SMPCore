package net.siftvanilla.siftcore.economy;

import java.nio.file.Path;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;

/** A migrated SQLite database in a temp folder, for tests. */
final class TestDatabase {

    private TestDatabase() {
    }

    static JdbcDatabase open(Path dir) throws Exception {
        Logger logger = Logger.getLogger("siftcore-test");
        JdbcDatabase database = new JdbcDatabase(new SqliteSource(dir.resolve("test.db"), 2), logger);
        ClassLoader loader = TestDatabase.class.getClassLoader();
        new Migrations(database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        return database;
    }
}
