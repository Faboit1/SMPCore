package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The spawner tables, the write-behind ordering and the storage transactions against a real SQLite database. */
class SpawnerStoreTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private SpawnerStore store;
    private Ledger ledger;

    @BeforeEach
    void open() throws Exception {
        Logger logger = Logger.getLogger("spawners-test");
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("spawners.db"), 2), logger);
        ClassLoader loader = SpawnerStoreTest.class.getClassLoader();
        new Migrations(this.database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.store = new SpawnerStore(this.database, logger);
        this.ledger = new Ledger(this.database, logger, Long.MAX_VALUE / 4);
        this.ledger.load();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private ManagedSpawner placed(long id, int x) throws Exception {
        ManagedSpawner spawner = new ManagedSpawner(id, new SpawnerPos("world", x, 64, 0), OWNER, "zombie", 1, 0, 123L);
        this.database.write(SpawnerStore.insert(spawner)).get();
        return spawner;
    }

    @Test
    void roundTrip() throws Exception {
        ManagedSpawner spawner = placed(1, 10);
        this.database.write(SpawnerStore.updateStack(1, 7)).get();
        this.database.write(SpawnerStore.persist(new SpawnerStore.Snapshot(1, 250,
            Map.of("minecraft:rotten_flesh", 40L, "minecraft:iron_ingot", 2L)))).get();
        List<SpawnerStore.Row> rows = this.store.loadAll();
        assertEquals(1, rows.size());
        SpawnerStore.Row row = rows.getFirst();
        assertEquals(spawner.pos, row.pos());
        assertEquals(OWNER, row.owner());
        assertEquals("zombie", row.mob());
        assertEquals(7, row.stack());
        assertEquals(250, row.xp());
        assertEquals(123L, row.created());
        assertEquals(Map.of("minecraft:rotten_flesh", 40L, "minecraft:iron_ingot", 2L), row.items());

        this.database.write(SpawnerStore.persist(new SpawnerStore.Snapshot(1, 0, Map.of("minecraft:iron_ingot", 1L)))).get();
        assertEquals(Map.of("minecraft:iron_ingot", 1L), this.store.loadAll().getFirst().items(), "a snapshot replaces every row");

        this.database.write(SpawnerStore.delete(1)).get();
        assertEquals(List.of(), this.store.loadAll());
        assertEquals(0L, this.store.count().get());
    }

    @Test
    void positionsAreUniqueAndStackUpdatesNeedARow() throws Exception {
        placed(1, 10);
        assertThrows(ExecutionException.class, () -> placed(2, 10));
        assertThrows(ExecutionException.class, () -> this.database.write(SpawnerStore.updateStack(99, 3)).get());
    }

    @Test
    void orphanItemRowsAreRemoved() throws Exception {
        placed(1, 10);
        this.database.write(SpawnerStore.persist(List.of(
            new SpawnerStore.Snapshot(1, 0, Map.of("minecraft:bone", 3L)),
            new SpawnerStore.Snapshot(2, 0, Map.of("minecraft:bone", 9L))))).get();
        assertEquals(1, this.store.removeOrphanItems());
        assertEquals(Map.of("minecraft:bone", 3L), this.store.loadAll().getFirst().items());
    }

    @Test
    void writeBehindWritesOnlyChangedSpawnersInOrder() throws Exception {
        ManagedSpawner spawner = placed(1, 10);
        ManagedSpawner other = placed(2, 20);
        WriteBehind writeBehind = new WriteBehind(this.ledger, this.store, Logger.getLogger("spawners-test"));
        assertEquals(0, writeBehind.flush(List.of(spawner, other)));
        this.ledger.locked(() -> {
            spawner.storage.add("minecraft:rotten_flesh", 12);
            spawner.xp(30);
            spawner.dirty(true);
            return null;
        });
        assertEquals(1, writeBehind.pending(List.of(spawner, other)));
        assertEquals(1, writeBehind.flush(List.of(spawner, other)));
        assertEquals(0, writeBehind.pending(List.of(spawner, other)));
        // A storage transaction queued after the flush wins, because both go through the ordered writer.
        LedgerTx tx = LedgerTx.builder().silent()
            .apply(() -> spawner.storage.take("minecraft:rotten_flesh", 12), () -> spawner.storage.add("minecraft:rotten_flesh", 12))
            .write(SpawnerStore.persist(new SpawnerStore.Snapshot(1, 30, Map.of())))
            .build();
        TransactionResult result = this.ledger.executeDomain(tx);
        assertTrue(result.success());
        result.committed().get();
        this.database.flush();
        SpawnerStore.Row row = this.store.loadAll().stream().filter(r -> r.id() == 1).findFirst().orElseThrow();
        assertEquals(Map.of(), row.items());
        assertEquals(30, row.xp());
        assertEquals(1, writeBehind.written());
    }

    @Test
    void aSaleTransactionStoresMoneyAndStorageTogether() throws Exception {
        ManagedSpawner spawner = placed(1, 10);
        this.ledger.locked(() -> {
            spawner.storage.add("minecraft:bone", 64);
            return null;
        });
        LedgerTx tx = LedgerTx.builder().actor(OWNER)
            .source(OWNER, Currency.MONEY, 384, SpawnerService.SELL_KIND, "spawner:1")
            .check(() -> spawner.storage.amount("minecraft:bone") >= 64 ? null : "changed")
            .apply(() -> spawner.storage.take("minecraft:bone", 64), () -> spawner.storage.add("minecraft:bone", 64))
            .write(SpawnerStore.persist(new SpawnerStore.Snapshot(1, 0, Map.of())))
            .build();
        TransactionResult result = this.ledger.execute(tx);
        assertTrue(result.success());
        result.committed().get();
        assertEquals(384, this.ledger.balance(OWNER, Currency.MONEY));
        assertEquals(0, spawner.storage.used());
        assertEquals(Map.of(), this.store.loadAll().getFirst().items());
        // The same sale again fails its check and changes nothing.
        LedgerTx again = LedgerTx.builder().actor(OWNER)
            .source(OWNER, Currency.MONEY, 384, SpawnerService.SELL_KIND, "spawner:1")
            .check(() -> spawner.storage.amount("minecraft:bone") >= 64 ? null : "changed")
            .apply(() -> spawner.storage.take("minecraft:bone", 64), () -> spawner.storage.add("minecraft:bone", 64))
            .build();
        TransactionResult second = this.ledger.execute(again);
        assertEquals("changed", second.reason());
        assertEquals(384, this.ledger.balance(OWNER, Currency.MONEY));
        assertTrue(this.ledger.audit().get().healthy());
    }
}
