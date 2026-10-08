package net.siftvanilla.siftcore.feature.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecentPurchasesTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("recent-test");
    private JdbcDatabase database;
    private Ledger ledger;

    @BeforeEach
    void setUp() throws Exception {
        this.logger.setLevel(Level.OFF);
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), this.logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, this.logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, this.logger, 1_000_000_000_000_000L);
        this.ledger.load();
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    @Test
    void mergeKeepsTheNewestOfEachEntryAndAtMostFive() {
        List<RecentPurchases.Recent> stored = List.of(new RecentPurchases.Recent("blocks/stone", 64, 100),
            new RecentPurchases.Recent("ores/diamond", 1, 90), new RecentPurchases.Recent("food/bread", 16, 80),
            new RecentPurchases.Recent("blocks/dirt", 64, 70), new RecentPurchases.Recent("blocks/sand", 64, 60));
        List<RecentPurchases.Recent> merged = RecentPurchases.merge(stored, List.of(new RecentPurchases.Recent("ores/diamond", 32, 200),
            new RecentPurchases.Recent("misc/torch", 8, 150)));
        assertEquals(List.of("ores/diamond", "misc/torch", "blocks/stone", "food/bread", "blocks/dirt"),
            merged.stream().map(RecentPurchases.Recent::ref).toList());
        assertEquals(32, merged.getFirst().amount());
    }

    private boolean buy(RecentPurchases recent, String ref, int amount, boolean ok) throws Exception {
        LedgerTx.Builder tx = LedgerTx.builder().actor(PLAYER).source(PLAYER, Currency.MONEY, 1, "test", ref);
        if (!ok) {
            tx.check(() -> "refused");
        }
        recent.contribute(tx, PLAYER, ref, amount);
        var result = this.ledger.execute(tx.build());
        if (result.success()) {
            result.committed().get();
        }
        return result.success();
    }

    @Test
    void onlyStoredPurchasesAreRememberedAndTheyLoadBack() throws Exception {
        RecentPurchases recent = new RecentPurchases(this.database, this.logger);
        recent.load(PLAYER);
        assertTrue(buy(recent, "blocks/stone", 64, true));
        Thread.sleep(5);
        assertTrue(buy(recent, "ores/diamond", 3, true));
        assertFalse(buy(recent, "food/bread", 16, false));
        assertEquals(List.of("ores/diamond", "blocks/stone"), recent.of(PLAYER).stream().map(RecentPurchases.Recent::ref).toList());
        recent.forget(PLAYER);
        assertTrue(recent.of(PLAYER).isEmpty());
        recent.load(PLAYER);
        for (int i = 0; i < 200 && recent.of(PLAYER).size() < 2; i++) {
            Thread.sleep(10);
        }
        assertEquals(List.of("ores/diamond", "blocks/stone"), recent.of(PLAYER).stream().map(RecentPurchases.Recent::ref).toList());
        assertEquals(3, recent.of(PLAYER).getFirst().amount());
    }
}
