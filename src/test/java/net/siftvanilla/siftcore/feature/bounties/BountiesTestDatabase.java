package net.siftvanilla.siftcore.feature.bounties;

import java.nio.file.Path;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;

/** A migrated SQLite database in a temp folder, for the bounty tests. */
final class BountiesTestDatabase {

    private BountiesTestDatabase() {
    }

    static JdbcDatabase open(Path dir) throws Exception {
        Logger logger = Logger.getLogger("siftcore-bounties-test");
        JdbcDatabase database = new JdbcDatabase(new SqliteSource(dir.resolve("bounties.db"), 2), logger);
        ClassLoader loader = BountiesTestDatabase.class.getClassLoader();
        new Migrations(database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        return database;
    }
}
