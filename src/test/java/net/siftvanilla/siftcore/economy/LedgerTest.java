package net.siftvanilla.siftcore.economy;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerTest {

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Ledger ledger;

    @BeforeEach
    void setUp() throws Exception {
        this.database = TestDatabase.open(this.dir);
        this.ledger = new Ledger(this.database, Logger.getLogger("ledger-test"), 1_000_000_000_000L);
        this.ledger.load();
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    private static UUID player() {
        return UUID.randomUUID();
    }

    private TransactionResult mint(UUID account, long amount) {
        return this.ledger.execute(LedgerTx.builder().source(account, Currency.MONEY, amount, "test_mint", null).build());
    }

    @Test
    void sourceTransferSinkAndAuditStayConsistent() throws Exception {
        UUID a = player();
        UUID b = player();
        assertTrue(mint(a, 1_000).success());
        TransactionResult transfer = this.ledger.execute(LedgerTx.builder().transfer(a, b, Currency.MONEY, 400, "pay", null).build());
        assertTrue(transfer.success());
        transfer.committed().get(10, TimeUnit.SECONDS);
        TransactionResult sink = this.ledger.execute(LedgerTx.builder().sink(b, Currency.MONEY, 100, "shop_buy", null).build());
        sink.committed().get(10, TimeUnit.SECONDS);
        assertEquals(600, this.ledger.balance(a, Currency.MONEY));
        assertEquals(300, this.ledger.balance(b, Currency.MONEY));
        assertEquals(900, this.ledger.totalSupply(Currency.MONEY));
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), report.problems().toString());
        long[] money = report.perCurrency().get(Currency.MONEY);
        assertEquals(1_000, money[3], "sources");
        assertEquals(100, money[4], "sinks");
    }

    @Test
    void insufficientFundsChangesNothing() {
        UUID a = player();
        UUID b = player();
        mint(a, 50);
        TransactionResult result = this.ledger.execute(LedgerTx.builder().transfer(a, b, Currency.MONEY, 51, "pay", null).build());
        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, result.status());
        assertEquals(50, this.ledger.balance(a, Currency.MONEY));
        assertEquals(0, this.ledger.balance(b, Currency.MONEY));
    }

    @Test
    void multiPostingTransactionIsAllOrNothing() {
        UUID buyer = player();
        UUID seller = player();
        mint(buyer, 100);
        // Buyer pays 100 to the seller, seller pays a tax of 200 they don't have: nothing may apply.
        TransactionResult result = this.ledger.execute(LedgerTx.builder()
            .transfer(buyer, seller, Currency.MONEY, 100, "ah_sale", "1")
            .sink(seller, Currency.MONEY, 200, "ah_tax", "1")
            .build());
        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, result.status());
        assertEquals(100, this.ledger.balance(buyer, Currency.MONEY));
        assertEquals(0, this.ledger.balance(seller, Currency.MONEY));
    }

    @Test
    void failingCheckRejectsWithoutApplying() {
        UUID a = player();
        mint(a, 100);
        AtomicInteger applied = new AtomicInteger();
        TransactionResult result = this.ledger.execute(LedgerTx.builder()
            .sink(a, Currency.MONEY, 10, "shop_buy", null)
            .check(() -> "listing_gone")
            .apply(applied::incrementAndGet, applied::decrementAndGet)
            .build());
        assertEquals(TransactionStatus.REJECTED, result.status());
        assertEquals("listing_gone", result.reason());
        assertEquals(0, applied.get());
        assertEquals(100, this.ledger.balance(a, Currency.MONEY));
    }

    @Test
    void storageFailureRevertsMemoryAndDomainState() throws Exception {
        UUID a = player();
        mint(a, 100).committed().get(10, TimeUnit.SECONDS);
        AtomicInteger domain = new AtomicInteger();
        TransactionResult result = this.ledger.execute(LedgerTx.builder()
            .sink(a, Currency.MONEY, 40, "shop_buy", null)
            .apply(domain::incrementAndGet, domain::decrementAndGet)
            .write(c -> {
                throw new SQLException("disk on fire");
            })
            .build());
        assertTrue(result.success(), "applied in memory first");
        assertThrows(Exception.class, () -> result.committed().get(10, TimeUnit.SECONDS));
        assertEquals(100, this.ledger.balance(a, Currency.MONEY), "reverted after the failed commit");
        assertEquals(0, domain.get(), "domain change reverted");
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), report.problems().toString());
    }

    /** Holds the database writer until the returned latch is released, so later transactions queue behind it. */
    private java.util.concurrent.CountDownLatch holdWriter() {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        this.database.write(c -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        return gate;
    }

    private static LedgerTx failingSource(UUID account, long amount, AtomicInteger domain) {
        return LedgerTx.builder()
            .source(account, Currency.MONEY, amount, "ah_sale", "1")
            .apply(domain::incrementAndGet, domain::decrementAndGet)
            .write(c -> {
                throw new SQLException("lock timeout");
            })
            .build();
    }

    /**
     * Dupe audit R6: a sale fails to store after its proceeds were spent by a transaction that was not stored yet. The
     * spending transaction must fail and be reverted too, or the money it passed on would exist with nothing behind it.
     */
    @Test
    void aTransactionThatSpentMoneyFromAFailedOneIsRevertedToo() throws Exception {
        UUID seller = player();
        UUID third = player();
        AtomicInteger listing = new AtomicInteger();
        var gate = holdWriter();
        TransactionResult sale = this.ledger.execute(failingSource(seller, 10_000, listing));
        TransactionResult spend = this.ledger.execute(LedgerTx.builder().transfer(seller, third, Currency.MONEY, 739, "pay", null).build());
        assertTrue(sale.success() && spend.success(), "both apply in memory at once");
        gate.countDown();
        assertThrows(Exception.class, () -> sale.committed().get(10, TimeUnit.SECONDS));
        assertThrows(Exception.class, () -> spend.committed().get(10, TimeUnit.SECONDS), "the spend relied on the failed sale");
        this.database.flush();
        assertEquals(0, this.ledger.balance(seller, Currency.MONEY));
        assertEquals(0, this.ledger.balance(third, Currency.MONEY), "the money passed on is taken back");
        assertEquals(0, listing.get(), "the sale's domain change is reverted");
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), report.problems().toString());
        assertTrue(this.ledger.available(), "one storage failure does not make the economy read-only");
        assertEquals(1, this.ledger.storeFailures(), "the revert that follows from it is not a storage failure of its own");
        assertEquals(1, this.ledger.dependentReverts());
    }

    /** Only transactions that relied on the failed one go: a credit, or a debit the stored balance covers, still stores. */
    @Test
    void transactionsThatDidNotRelyOnAFailedOneStillStore() throws Exception {
        UUID a = player();
        UUID b = player();
        mint(a, 1_000).committed().get(10, TimeUnit.SECONDS);
        var gate = holdWriter();
        TransactionResult failed = this.ledger.execute(failingSource(a, 500, new AtomicInteger()));
        TransactionResult covered = this.ledger.execute(LedgerTx.builder().transfer(a, b, Currency.MONEY, 800, "pay", null).build());
        TransactionResult credit = this.ledger.execute(LedgerTx.builder().source(a, Currency.MONEY, 50, "test_mint", null).build());
        gate.countDown();
        assertThrows(Exception.class, () -> failed.committed().get(10, TimeUnit.SECONDS));
        covered.committed().get(10, TimeUnit.SECONDS);
        credit.committed().get(10, TimeUnit.SECONDS);
        this.database.flush();
        assertEquals(250, this.ledger.balance(a, Currency.MONEY), "1,000 - 800 + 50");
        assertEquals(800, this.ledger.balance(b, Currency.MONEY));
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), report.problems().toString());
        assertEquals(0, this.ledger.dependentReverts());
    }

    /**
     * A chain: the failed sale's money goes A -> B -> C before anything is stored, and every link is taken back, however
     * the reverts interleave on the callback threads. Many such chains in one run must leave memory and storage equal.
     */
    @Test
    void aChainOfSpendsIsTakenBackLinkByLink() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID a = player();
            UUID b = player();
            UUID c = player();
            var gate = holdWriter();
            TransactionResult failed = this.ledger.execute(failingSource(a, 300, new AtomicInteger()));
            TransactionResult first = this.ledger.execute(LedgerTx.builder().transfer(a, b, Currency.MONEY, 300, "pay", null).build());
            TransactionResult second = this.ledger.execute(LedgerTx.builder().transfer(b, c, Currency.MONEY, 200, "pay", null).build());
            assertTrue(failed.success() && first.success() && second.success());
            gate.countDown();
            for (TransactionResult result : List.of(failed, first, second)) {
                assertThrows(Exception.class, () -> result.committed().get(10, TimeUnit.SECONDS));
            }
            this.database.flush();
            assertEquals(0, this.ledger.balance(a, Currency.MONEY));
            assertEquals(0, this.ledger.balance(b, Currency.MONEY));
            assertEquals(0, this.ledger.balance(c, Currency.MONEY));
            assertTrue(this.ledger.available(), "only the root failure counts towards read-only");
            this.ledger.resume();
        }
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), report.problems().toString());
        assertEquals(40, this.ledger.dependentReverts(), "two links taken back per round");
    }

    /** An apply that throws (they must not) leaves nothing moved: postings and the applies before it are undone. */
    @Test
    void anApplyThatThrowsUndoesTheWholeTransaction() throws Exception {
        UUID a = player();
        UUID b = player();
        mint(a, 100).committed().get(10, TimeUnit.SECONDS);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger third = new AtomicInteger();
        TransactionResult result = this.ledger.execute(LedgerTx.builder()
            .transfer(a, b, Currency.MONEY, 40, "ah_sale", "1")
            .apply(first::incrementAndGet, first::decrementAndGet)
            .apply(() -> {
                throw new IllegalStateException("a bug in a domain change");
            }, () -> {
            })
            .apply(third::incrementAndGet, third::decrementAndGet)
            .build());
        assertFalse(result.success());
        assertEquals(TransactionStatus.REJECTED, result.status());
        assertEquals("apply_failed", result.reason());
        assertEquals(100, this.ledger.balance(a, Currency.MONEY));
        assertEquals(0, this.ledger.balance(b, Currency.MONEY));
        assertEquals(0, first.get(), "the apply before it was undone");
        assertEquals(0, third.get(), "the apply after it never ran");
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), report.problems().toString());
        assertTrue(this.ledger.execute(LedgerTx.builder().transfer(a, b, Currency.MONEY, 40, "pay", null).build()).success(),
            "the ledger goes on working");
    }

    @Test
    void afterCommitRunsOnlyForAStoredTransactionAndBeforeCommittedCompletes() throws Exception {
        UUID a = player();
        AtomicInteger stored = new AtomicInteger();
        TransactionResult ok = this.ledger.execute(LedgerTx.builder()
            .source(a, Currency.MONEY, 10, "test_mint", null)
            .afterCommit(stored::incrementAndGet)
            .afterCommit(() -> {
                throw new IllegalStateException("a failing callback is logged, not fatal");
            })
            .afterCommit(stored::incrementAndGet)
            .build());
        ok.committed().get(10, TimeUnit.SECONDS);
        assertEquals(2, stored.get(), "every callback ran, before committed() completed, despite the one that threw");

        AtomicInteger reverted = new AtomicInteger();
        TransactionResult failed = this.ledger.execute(LedgerTx.builder()
            .source(a, Currency.MONEY, 10, "test_mint", null)
            .afterCommit(reverted::incrementAndGet)
            .write(c -> {
                throw new SQLException("disk on fire");
            })
            .build());
        assertThrows(Exception.class, () -> failed.committed().get(10, TimeUnit.SECONDS));
        assertEquals(0, reverted.get(), "never for a reverted transaction");

        AtomicInteger rejected = new AtomicInteger();
        TransactionResult refused = this.ledger.execute(LedgerTx.builder()
            .source(a, Currency.MONEY, 10, "test_mint", null)
            .check(() -> "no")
            .afterCommit(rejected::incrementAndGet)
            .build());
        assertFalse(refused.success());
        this.database.flush();
        assertEquals(0, rejected.get(), "never for a refused transaction");
    }

    @Test
    void balanceLimitIsEnforced() {
        Ledger small = new Ledger(this.database, Logger.getLogger("ledger-test"), 1_000);
        UUID a = player();
        assertEquals(TransactionStatus.BALANCE_LIMIT,
            small.execute(LedgerTx.builder().source(a, Currency.MONEY, 1_001, "test_mint", null).build()).status());
        assertTrue(small.execute(LedgerTx.builder().source(a, Currency.MONEY, 1_000, "test_mint", null).build()).success());
    }

    @Test
    void transfersMustNetToZero() {
        UUID a = player();
        assertThrows(IllegalArgumentException.class, () -> LedgerTx.builder().transfer(a, a, Currency.MONEY, 5, "pay", null));
        assertThrows(IllegalArgumentException.class, () -> LedgerTx.builder().source(a, Currency.MONEY, 0, "x", null));
        assertThrows(IllegalStateException.class, () -> LedgerTx.builder().build());
    }

    @Test
    void currenciesAreIndependent() throws Exception {
        UUID a = player();
        this.ledger.execute(LedgerTx.builder().source(a, Currency.SHARDS, 30, "afk_reward", null).build()).committed().get(10, TimeUnit.SECONDS);
        assertEquals(30, this.ledger.balance(a, Currency.SHARDS));
        assertEquals(0, this.ledger.balance(a, Currency.MONEY));
        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS,
            this.ledger.execute(LedgerTx.builder().sink(a, Currency.MONEY, 1, "x", null).build()).status());
    }

    @Test
    void balancesSurviveReload() throws Exception {
        UUID a = player();
        UUID b = player();
        mint(a, 777);
        this.ledger.execute(LedgerTx.builder().transfer(a, b, Currency.MONEY, 77, "pay", null).build()).committed().get(10, TimeUnit.SECONDS);
        Ledger reloaded = new Ledger(this.database, Logger.getLogger("ledger-test"), 1_000_000_000_000L);
        reloaded.load();
        assertEquals(700, reloaded.balance(a, Currency.MONEY));
        assertEquals(77, reloaded.balance(b, Currency.MONEY));
    }

    /**
     * Many threads move money between a fixed set of accounts at the same time. Total supply must be exactly
     * conserved, no balance may go negative, and memory, stored balances and the ledger history must agree.
     */
    @Test
    void parallelTransfersConserveTotalSupply() throws Exception {
        int accounts = 40;
        long start = 10_000;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            UUID id = player();
            ids.add(id);
            mint(id, start);
        }
        int threads = 16;
        int perThread = 1_500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong succeeded = new AtomicLong();
        AtomicLong refused = new AtomicLong();
        List<CompletableFuture<Void>> commits = java.util.Collections.synchronizedList(new ArrayList<>());
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < perThread; i++) {
                    UUID from = ids.get(random.nextInt(accounts));
                    UUID to = ids.get(random.nextInt(accounts));
                    if (from.equals(to)) {
                        continue;
                    }
                    TransactionResult result = this.ledger.execute(LedgerTx.builder()
                        .transfer(from, to, Currency.MONEY, 1 + random.nextInt(2_500), "pay", null).build());
                    if (result.success()) {
                        succeeded.incrementAndGet();
                        commits.add(result.committed());
                    } else {
                        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, result.status());
                        refused.incrementAndGet();
                    }
                }
            }));
        }
        for (var future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        CompletableFuture.allOf(commits.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
        assertTrue(succeeded.get() > 1_000, "enough transfers went through: " + succeeded.get());
        assertEquals(accounts * start, this.ledger.totalSupply(Currency.MONEY), "supply conserved in memory");
        for (UUID id : ids) {
            assertFalse(this.ledger.balance(id, Currency.MONEY) < 0, "no negative balance");
        }
        Ledger.AuditReport report = this.ledger.audit().get(60, TimeUnit.SECONDS);
        assertTrue(report.healthy(), report.problems().toString());
        assertEquals(accounts * start, report.perCurrency().get(Currency.MONEY)[1], "supply conserved in storage");
        Ledger reloaded = new Ledger(this.database, Logger.getLogger("ledger-test"), 1_000_000_000_000L);
        reloaded.load();
        for (UUID id : ids) {
            assertEquals(this.ledger.balance(id, Currency.MONEY), reloaded.balance(id, Currency.MONEY));
        }
    }
}
