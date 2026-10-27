package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
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

/**
 * Stored spawner XP that left its spawner waits in the XP box until it is paid out, stored with the change that moved
 * it: a breaker who leaves before the pickup is handed over keeps the XP (it used to be destroyed with the spawner).
 */
class XpBoxTest {

    private static final UUID BREAKER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Ledger ledger;
    private final Logger logger = Logger.getLogger("xp-box-test");

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("xp.db"), 2), this.logger);
        ClassLoader loader = XpBoxTest.class.getClassLoader();
        new Migrations(this.database, this.logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, this.logger, Long.MAX_VALUE / 4);
        this.ledger.load();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private XpBox box() throws Exception {
        XpBox box = new XpBox(this.ledger, this.database, this.logger);
        box.load();
        return box;
    }

    /** A pickup-like transaction: some domain change, plus the spawner's XP into the breaker's box. */
    private TransactionResult pickUp(XpBox box, UUID breaker, long xp) {
        LedgerTx.Builder tx = LedgerTx.builder().actor(breaker).silent().note("pick up");
        box.credit(tx, breaker, xp);
        return this.ledger.executeDomain(tx.build());
    }

    @Test
    void xpCreditedWithAPickupSurvivesARestart() throws Exception {
        XpBox box = box();
        TransactionResult result = pickUp(box, BREAKER, 1_500);
        assertTrue(result.success());
        result.committed().get(10, TimeUnit.SECONDS);
        assertEquals(1_500, box.waiting(BREAKER));
        pickUp(box, BREAKER, 500).committed().get(10, TimeUnit.SECONDS);
        assertEquals(2_000, box.waiting(BREAKER));

        XpBox reloaded = box();
        assertEquals(2_000, reloaded.waiting(BREAKER), "the breaker left before the payout: the XP waits for their next join");
        assertEquals(0, reloaded.waiting(OTHER));
        assertEquals(1, reloaded.players());
        assertEquals(2_000, reloaded.total());
    }

    @Test
    void takingPaysOutEverythingOnceAndStoresIt() throws Exception {
        XpBox box = box();
        pickUp(box, BREAKER, 700).committed().get(10, TimeUnit.SECONDS);
        XpBox.Taken taken = box.take(BREAKER);
        assertTrue(taken.result().success());
        assertEquals(700, taken.xp());
        taken.result().committed().get(10, TimeUnit.SECONDS);
        assertEquals(0, box.waiting(BREAKER));

        XpBox.Taken again = box.take(BREAKER);
        assertFalse(again.result().success(), "nothing is paid out twice");
        assertEquals(0, again.xp());
        assertEquals(0, box().waiting(BREAKER));
    }

    @Test
    void xpThatCouldNotBePaidOutGoesBack() throws Exception {
        XpBox box = box();
        pickUp(box, BREAKER, 300).committed().get(10, TimeUnit.SECONDS);
        XpBox.Taken taken = box.take(BREAKER);
        taken.result().committed().get(10, TimeUnit.SECONDS);
        // The player left before the payout ran (or the server stopped): it is credited back.
        TransactionResult back = box.credit(BREAKER, taken.xp(), "test");
        back.committed().get(10, TimeUnit.SECONDS);
        assertEquals(300, box.waiting(BREAKER));
        assertEquals(300, box().waiting(BREAKER));
    }

    @Test
    void aFailedTransactionTakesItsXpBackInMemoryAndStorage() throws Exception {
        XpBox box = box();
        pickUp(box, BREAKER, 100).committed().get(10, TimeUnit.SECONDS);
        LedgerTx.Builder tx = LedgerTx.builder().actor(BREAKER).silent().note("failing pickup");
        box.credit(tx, BREAKER, 900);
        tx.write(c -> {
            throw new SQLException("disk on fire");
        });
        TransactionResult failed = this.ledger.executeDomain(tx.build());
        assertTrue(failed.success(), "applied in memory first");
        ExecutionException error = assertThrows(ExecutionException.class, () -> failed.committed().get(10, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof SQLException);
        assertEquals(100, box.waiting(BREAKER), "reverted in memory");
        assertEquals(100, box().waiting(BREAKER), "and never stored");
    }

    @Test
    void memoryAndStorageAgreeUnderConcurrentPickupsAndPayouts() throws Exception {
        XpBox box = box();
        AtomicLong paid = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<CompletableFuture<Void>> work = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                boolean payout = i % 3 == 0;
                work.add(CompletableFuture.runAsync(() -> {
                    if (payout) {
                        XpBox.Taken taken = box.take(BREAKER);
                        if (taken.result().success()) {
                            taken.result().committed().join();
                            paid.addAndGet(taken.xp());
                        }
                    } else {
                        pickUp(box, BREAKER, 10).committed().join();
                    }
                }, pool));
            }
            CompletableFuture.allOf(work.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        long credited = 10L * (200 - 67);
        assertEquals(credited, paid.get() + box.waiting(BREAKER), "every bit of XP is either paid or still waiting");
        assertEquals(box.waiting(BREAKER), box().waiting(BREAKER), "storage matches memory");
    }

    @Test
    void nothingIsCreditedForNoXp() throws Exception {
        XpBox box = box();
        LedgerTx.Builder tx = LedgerTx.builder().actor(BREAKER).silent().note("pick up a spawner without xp");
        tx.write(c -> null);
        box.credit(tx, BREAKER, 0);
        this.ledger.executeDomain(tx.build()).committed().get(10, TimeUnit.SECONDS);
        assertEquals(null, box.credit(BREAKER, 0, "nothing"));
        assertEquals(0, box.players());
        assertEquals(0, box().players());
    }
}
