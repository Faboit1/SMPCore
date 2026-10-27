package net.siftvanilla.siftcore.feature.shards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.core.link.CrateKeys;
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
 * Crate keys bought with shards against a real ledger and SQLite: keys given, refused, failing or already given, a
 * crash between paying and giving, and concurrent attempts. After every test the ledger audit holds and the pending
 * purchases in memory match storage.
 */
class KeyGrantsTest {

    private static final Logger LOG = Logger.getLogger("shards-test");

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Ledger ledger;
    private ExecutorService async;

    /** A crates feature: gives keys for a ref at most once; can be told to refuse, throw or fail to store. */
    private static final class FakeCrates implements CrateKeys {
        enum Mode { GIVE, REFUSE, THROW, FAIL_STORAGE }

        private volatile Mode mode = Mode.GIVE;
        private final Map<String, Integer> given = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> keys = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch gate;

        @Override
        public Set<String> crates() {
            return Set.of("rare");
        }

        @Override
        public TransactionResult give(UUID player, String crate, int amount, String actor, String ref) {
            this.calls.incrementAndGet();
            CountDownLatch wait = this.gate;
            if (wait != null) {
                try {
                    wait.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            switch (this.mode) {
                case REFUSE -> {
                    return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.REJECTED, "unknown_crate");
                }
                case THROW -> throw new IllegalStateException("crates are broken");
                case FAIL_STORAGE -> {
                    return new TransactionResult(UUID.randomUUID(), TransactionStatus.SUCCESS, null,
                        CompletableFuture.failedFuture(new java.sql.SQLException("disk full")));
                }
                case GIVE -> {
                }
            }
            if (this.given.putIfAbsent(ref, amount) != null) {
                return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.REJECTED, "duplicate");
            }
            this.keys.merge(player, amount, Integer::sum);
            return new TransactionResult(UUID.randomUUID(), TransactionStatus.SUCCESS, null, CompletableFuture.completedFuture(null));
        }

        @Override
        public int keys(UUID player, String crate) {
            return this.keys.getOrDefault(player, 0);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        this.database = open();
        this.ledger = new Ledger(this.database, LOG, 1_000_000_000_000_000L);
        this.ledger.load();
        this.async = Executors.newFixedThreadPool(4);
    }

    private JdbcDatabase open() throws Exception {
        JdbcDatabase db = new JdbcDatabase(new SqliteSource(this.dir.resolve("shards.db"), 2), LOG);
        ClassLoader loader = KeyGrantsTest.class.getClassLoader();
        new Migrations(db, LOG, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        return db;
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
            assertTrue(report.healthy(), report.problems().toString());
        } finally {
            this.async.shutdownNow();
            this.database.close();
        }
    }

    private UUID player(long shards) {
        UUID player = UUID.randomUUID();
        assertTrue(this.ledger.execute(LedgerTx.builder().source(player, Currency.SHARDS, shards, "test_mint", null).build()).success());
        return player;
    }

    private long shards(UUID player) {
        return this.ledger.balance(player, Currency.SHARDS);
    }

    /** Pays for keys like the shop does and waits until it is stored. */
    private KeyGrants.Purchase buy(KeyGrants grants, UUID player, int keys, long cost) throws Exception {
        KeyGrants.Purchase purchase = new KeyGrants.Purchase(KeyGrants.newRef(), player, "rare-key", "rare", keys, cost,
            System.currentTimeMillis());
        TransactionResult result = this.ledger.execute(grants.purchase(LedgerTx.builder().actor(player), purchase).build());
        assertTrue(result.success(), String.valueOf(result));
        result.committed().get(10, TimeUnit.SECONDS);
        return purchase;
    }

    private String state(String ref) throws Exception {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT state FROM shard_purchases WHERE ref = ?")) {
                ps.setString(1, ref);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private long ledgerSum(String kind, String ref) throws Exception {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE kind = ? AND ref = ?")) {
                ps.setString(1, kind);
                ps.setString(2, ref);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private KeyGrants.Outcome grant(KeyGrants grants, KeyGrants.Purchase purchase) throws Exception {
        KeyGrants.Outcome outcome = grants.grant(purchase).get(10, TimeUnit.SECONDS);
        this.database.write(c -> null).get(10, TimeUnit.SECONDS);
        return outcome;
    }

    @Test
    void keysGivenAfterTheShardsAreTaken() throws Exception {
        FakeCrates crates = new FakeCrates();
        KeyGrants grants = new KeyGrants(this.ledger, this.database, crates, this.async, LOG);
        UUID player = player(500);
        KeyGrants.Purchase purchase = buy(grants, player, 3, 200);
        assertEquals(300, shards(player), "the shards go first");
        assertEquals("pending", state(purchase.ref()));
        assertEquals(1, grants.pending().size());
        assertEquals(KeyGrants.Outcome.GRANTED, grant(grants, purchase));
        assertEquals(3, crates.keys(player, "rare"));
        assertEquals(300, shards(player));
        assertEquals("done", state(purchase.ref()));
        assertEquals(-200, ledgerSum(KeyGrants.SPEND_KIND, purchase.ref()));
        assertEquals(0, grants.pending().size());
        assertNull(grants.check().get(10, TimeUnit.SECONDS));
        assertEquals(KeyGrants.Outcome.FINISHED, grant(grants, purchase), "a finished purchase is not given again");
        assertEquals(1, crates.calls.get());
    }

    @Test
    void refusedKeysAreRefunded() throws Exception {
        FakeCrates crates = new FakeCrates();
        crates.mode = FakeCrates.Mode.REFUSE;
        KeyGrants grants = new KeyGrants(this.ledger, this.database, crates, this.async, LOG);
        UUID player = player(500);
        KeyGrants.Purchase purchase = buy(grants, player, 1, 200);
        assertEquals(KeyGrants.Outcome.REFUNDED, grant(grants, purchase));
        assertEquals(500, shards(player), "every shard came back");
        assertEquals("refunded", state(purchase.ref()));
        assertEquals(-200, ledgerSum(KeyGrants.SPEND_KIND, purchase.ref()));
        assertEquals(200, ledgerSum(KeyGrants.REFUND_KIND, purchase.ref()), "a compensating refund with the same ref");
        assertEquals(0, crates.keys(player, "rare"));
        assertNull(grants.check().get(10, TimeUnit.SECONDS));
        assertEquals(KeyGrants.Outcome.FINISHED, grant(grants, purchase), "never refunded twice");
        assertEquals(500, shards(player));
    }

    @Test
    void aBrokenCratesFeatureOrLostKeysAreRefunded() throws Exception {
        FakeCrates crates = new FakeCrates();
        KeyGrants grants = new KeyGrants(this.ledger, this.database, crates, this.async, LOG);
        UUID player = player(1_000);
        crates.mode = FakeCrates.Mode.THROW;
        KeyGrants.Purchase thrown = buy(grants, player, 1, 200);
        assertEquals(KeyGrants.Outcome.REFUNDED, grant(grants, thrown));
        crates.mode = FakeCrates.Mode.FAIL_STORAGE;
        KeyGrants.Purchase lost = buy(grants, player, 1, 300);
        assertEquals(KeyGrants.Outcome.REFUNDED, grant(grants, lost), "keys whose storage failed were taken back, so refund");
        assertEquals(1_000, shards(player));
        assertEquals("refunded", state(thrown.ref()));
        assertEquals("refunded", state(lost.ref()));
    }

    @Test
    void keysGivenBeforeACrashAreNotGivenOrRefundedAgain() throws Exception {
        FakeCrates crates = new FakeCrates();
        KeyGrants grants = new KeyGrants(this.ledger, this.database, crates, this.async, LOG);
        UUID player = player(500);
        KeyGrants.Purchase purchase = buy(grants, player, 2, 100);
        // The keys were given, then the server died before the purchase was marked done.
        crates.give(player, "rare", 2, player.toString(), purchase.ref());
        assertEquals(KeyGrants.Outcome.GRANTED, grant(grants, purchase), "the crates feature answers duplicate");
        assertEquals(2, crates.keys(player, "rare"), "not twice");
        assertEquals(400, shards(player), "and no refund");
        assertEquals("done", state(purchase.ref()));
    }

    @Test
    void aPurchaseCutOffByACrashIsResumedAfterTheRestart() throws Exception {
        FakeCrates crates = new FakeCrates();
        UUID player = player(1_000);
        KeyGrants before = new KeyGrants(this.ledger, this.database, crates, this.async, LOG);
        KeyGrants.Purchase given = buy(before, player, 1, 200);
        KeyGrants.Purchase refused = buy(before, player, 1, 300);
        // Crash: neither grant ran. Restart with a fresh ledger and journal from storage.
        this.database.flush();
        this.ledger = new Ledger(this.database, LOG, 1_000_000_000_000_000L);
        this.ledger.load();
        assertEquals(500, shards(player));
        KeyGrants after = new KeyGrants(this.ledger, this.database, crates, this.async, LOG);
        assertEquals(2, after.load());
        assertNull(after.check().get(10, TimeUnit.SECONDS));
        Map<String, KeyGrants.Outcome> outcomes = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(2);
        crates.mode = FakeCrates.Mode.GIVE;
        // The first resumes with the crate giving keys; switch to refusing for the second through the ref.
        CrateKeys selective = new CrateKeys() {
            @Override
            public Set<String> crates() {
                return crates.crates();
            }

            @Override
            public TransactionResult give(UUID p, String crate, int amount, String actor, String ref) {
                return ref.equals(refused.ref())
                    ? TransactionResult.failed(UUID.randomUUID(), TransactionStatus.REJECTED, "crate_removed")
                    : crates.give(p, crate, amount, actor, ref);
            }

            @Override
            public int keys(UUID p, String crate) {
                return crates.keys(p, crate);
            }
        };
        KeyGrants resumed = new KeyGrants(this.ledger, this.database, selective, this.async, LOG);
        assertEquals(2, resumed.load());
        resumed.resume((purchase, outcome) -> {
            outcomes.put(purchase.ref(), outcome);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        this.database.write(c -> null).get(10, TimeUnit.SECONDS);
        assertEquals(KeyGrants.Outcome.GRANTED, outcomes.get(given.ref()));
        assertEquals(KeyGrants.Outcome.REFUNDED, outcomes.get(refused.ref()));
        assertEquals(1, crates.keys(player, "rare"));
        assertEquals(800, shards(player), "300 refunded, 200 spent");
        assertEquals("done", state(given.ref()));
        assertEquals("refunded", state(refused.ref()));
        assertEquals(0, resumed.pending().size());
        assertNull(resumed.check().get(10, TimeUnit.SECONDS));
    }

    @Test
    void concurrentAttemptsFinishAPurchaseOnce() throws Exception {
        FakeCrates crates = new FakeCrates();
        crates.mode = FakeCrates.Mode.REFUSE;
        crates.gate = new CountDownLatch(1);
        KeyGrants grants = new KeyGrants(this.ledger, this.database, crates, this.async, LOG);
        UUID player = player(500);
        KeyGrants.Purchase purchase = buy(grants, player, 1, 200);
        CompletableFuture<KeyGrants.Outcome> first = grants.grant(purchase);
        CompletableFuture<KeyGrants.Outcome> second = grants.grant(purchase);
        assertEquals(KeyGrants.Outcome.PENDING, second.get(10, TimeUnit.SECONDS), "the second attempt waits for the first");
        crates.gate.countDown();
        assertEquals(KeyGrants.Outcome.REFUNDED, first.get(10, TimeUnit.SECONDS));
        this.database.write(c -> null).get(10, TimeUnit.SECONDS);
        assertEquals(500, shards(player), "refunded exactly once");
        assertEquals(1, crates.calls.get());
        assertEquals(KeyGrants.Outcome.FINISHED, grant(grants, purchase));
        assertEquals(500, shards(player));
    }

    @Test
    void aPurchaseWithoutTheShardsStoresNothing() throws Exception {
        FakeCrates crates = new FakeCrates();
        KeyGrants grants = new KeyGrants(this.ledger, this.database, crates, this.async, LOG);
        UUID player = player(100);
        KeyGrants.Purchase purchase = new KeyGrants.Purchase(KeyGrants.newRef(), player, "rare-key", "rare", 1, 200, 0);
        TransactionResult result = this.ledger.execute(grants.purchase(LedgerTx.builder().actor(player), purchase).build());
        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, result.status());
        assertEquals(0, grants.pending().size(), "nothing pending");
        assertNull(state(purchase.ref()), "no journal row");
        assertEquals(100, shards(player));
        assertTrue(KeyGrants.newRef().length() <= 64, "fits the ledger's ref column");
    }
}
