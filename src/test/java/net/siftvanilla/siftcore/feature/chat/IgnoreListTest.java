package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IgnoreListTest {

    private static final Logger LOGGER = Logger.getLogger("siftcore-test");
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();
    private static final UUID KAI = UUID.randomUUID();

    @TempDir
    Path dir;
    private JdbcDatabase database;

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = IgnoreListTest.class.getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private IgnoreList reloaded() throws Exception {
        this.database.flush();
        IgnoreList list = new IgnoreList(this.database, LOGGER);
        list.load();
        return list;
    }

    @Test
    void ignoringIsOneWay() throws Exception {
        IgnoreList list = new IgnoreList(this.database, LOGGER);
        list.load();
        assertEquals(IgnoreList.Change.ADDED, list.add(ALEX, SAM, 10));
        assertTrue(list.ignores(ALEX, SAM));
        assertFalse(list.ignores(SAM, ALEX));
        assertEquals(Set.of(SAM), list.ignored(ALEX));
        assertEquals(Set.of(), list.ignored(SAM));
    }

    @Test
    void changesSurviveARestart() throws Exception {
        IgnoreList list = new IgnoreList(this.database, LOGGER);
        list.load();
        list.add(ALEX, SAM, 10);
        list.add(ALEX, KAI, 10);
        list.add(SAM, KAI, 10);
        list.remove(ALEX, SAM);
        IgnoreList again = reloaded();
        assertEquals(Set.of(KAI), again.ignored(ALEX));
        assertEquals(Set.of(KAI), again.ignored(SAM));
        assertEquals(2, again.total());
        assertEquals(2, again.countRows().get());
    }

    @Test
    void repeatedChangesAreHarmless() throws Exception {
        IgnoreList list = new IgnoreList(this.database, LOGGER);
        list.load();
        assertEquals(IgnoreList.Change.ADDED, list.add(ALEX, SAM, 10));
        assertEquals(IgnoreList.Change.UNCHANGED, list.add(ALEX, SAM, 10));
        assertEquals(IgnoreList.Change.REMOVED, list.remove(ALEX, SAM));
        assertEquals(IgnoreList.Change.UNCHANGED, list.remove(ALEX, SAM));
        assertEquals(IgnoreList.Change.UNCHANGED, list.remove(KAI, SAM), "a player without a list");
        assertEquals(0, reloaded().total());
    }

    @Test
    void theLimitIsKept() throws Exception {
        IgnoreList list = new IgnoreList(this.database, LOGGER);
        list.load();
        assertEquals(IgnoreList.Change.ADDED, list.add(ALEX, SAM, 1));
        assertEquals(IgnoreList.Change.FULL, list.add(ALEX, KAI, 1));
        assertEquals(1, list.count(ALEX));
        assertEquals(Set.of(SAM), reloaded().ignored(ALEX));
    }

    @Test
    void snapshotsDontChangeUnderneath() throws Exception {
        IgnoreList list = new IgnoreList(this.database, LOGGER);
        list.load();
        list.add(ALEX, SAM, 10);
        Set<UUID> snapshot = list.ignored(ALEX);
        list.add(ALEX, KAI, 10);
        assertEquals(Set.of(SAM), snapshot);
        assertEquals(Set.of(SAM, KAI), list.ignored(ALEX));
    }

    @Test
    void concurrentChangesAreNotLost() throws Exception {
        IgnoreList list = new IgnoreList(this.database, LOGGER);
        list.load();
        Thread[] threads = new Thread[8];
        UUID[] targets = new UUID[threads.length * 10];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = UUID.randomUUID();
        }
        for (int t = 0; t < threads.length; t++) {
            int from = t * 10;
            threads[t] = new Thread(() -> {
                for (int i = from; i < from + 10; i++) {
                    list.add(ALEX, targets[i], 1000);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(targets.length, list.count(ALEX));
        assertEquals(targets.length, reloaded().count(ALEX));
    }
}
