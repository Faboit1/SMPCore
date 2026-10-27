package net.siftvanilla.siftcore.feature.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.api.event.StoreDeliveryEvent;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Store delivery is exactly-once per reference: repeated, concurrent and restarted deliveries never pay twice, and an
 * interrupted rank delivery is finished, not repeated. Runs on a real migrated SQLite database and the real ledger.
 */
class StoreServiceTest {

    private static final IntegrationsSettings.Store LIMITS = new IntegrationsSettings.Store(1_000_000, 50_000, 100,
        Set.of("elite", "legend"), Duration.ofHours(1), Duration.ofDays(365), true, false);

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Ledger ledger;
    private FakeKeys keys;
    private FakeBoosters boosters;
    private FakeRanks ranks;
    private AtomicBoolean allow;
    private StoreService store;

    @BeforeEach
    void setUp() throws Exception {
        open();
    }

    private void open() throws Exception {
        Logger logger = Logger.getLogger("store-test");
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, logger, 1_000_000_000_000L);
        this.ledger.load();
        if (this.keys == null) {
            this.keys = new FakeKeys();
            this.boosters = new FakeBoosters();
            this.ranks = new FakeRanks();
            this.allow = new AtomicBoolean(true);
        }
        this.store = new StoreService(this.ledger, this.database, this.keys, this.boosters, () -> this.ranks, () -> LIMITS,
            (player, kind, item, amount, duration, ref, actor) -> this.allow.get(), System::currentTimeMillis);
        this.store.load();
    }

    /** Shuts down cleanly and starts again from the same files, as a server restart does. */
    private void restart() throws Exception {
        this.database.close();
        open();
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    private static StoreService.Outcome get(CompletableFuture<StoreService.Outcome> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private int rows(String ref) throws Exception {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM store_deliveries WHERE ref = ?")) {
                ps.setString(1, ref);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }).get();
    }

    private String state(String ref) throws Exception {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT state FROM store_deliveries WHERE ref = ?")) {
                ps.setString(1, ref);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).get();
    }

    // ------------------------------------------------------------------ money and shards

    @Test
    void moneyIsPaidOncePerReferenceEvenAfterARestart() throws Exception {
        UUID player = UUID.randomUUID();
        StoreService.Outcome first = get(this.store.currency(player, Currency.MONEY, 25_000, "tbx-1", "console"));
        assertEquals(StoreService.Status.DELIVERED, first.status());
        assertEquals(25_000, this.ledger.balance(player, Currency.MONEY));
        StoreService.Outcome again = get(this.store.currency(player, Currency.MONEY, 25_000, "tbx-1", "console"));
        assertEquals(StoreService.Status.ALREADY, again.status());
        assertEquals("tbx-1", again.delivery().ref());
        assertEquals(25_000, this.ledger.balance(player, Currency.MONEY), "nothing paid twice");

        restart();
        assertEquals(25_000, this.ledger.balance(player, Currency.MONEY));
        assertEquals(StoreService.Status.ALREADY, get(this.store.currency(player, Currency.MONEY, 25_000, "tbx-1", "console")).status());
        assertEquals(25_000, this.ledger.balance(player, Currency.MONEY), "a retry after a restart pays nothing");
        assertEquals(1, rows("tbx-1"));
        assertTrue(this.ledger.audit().get().healthy());
    }

    @Test
    void theReferenceIsStoredWithTheLedgerRows() throws Exception {
        UUID player = UUID.randomUUID();
        get(this.store.currency(player, Currency.SHARDS, 500, "order-9", "console"));
        String ref = this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ref, kind FROM ledger WHERE account = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) + "/" + rs.getString(2) : null;
                }
            }
        }).get();
        assertEquals("order-9/" + StoreService.KIND, ref);
        assertEquals(500, this.ledger.balance(player, Currency.SHARDS));
        Delivery delivery = this.store.find("order-9");
        assertEquals(StoreDeliveryEvent.Kind.SHARDS, delivery.kind());
        assertEquals("shards", delivery.item());
    }

    @Test
    void manyConcurrentDeliveriesOfOneReferencePayExactlyOnce() throws Exception {
        UUID player = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<CompletableFuture<StoreService.Outcome>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                results.add(CompletableFuture.supplyAsync(() -> this.store.currency(player, Currency.MONEY, 1_000, "race", "console"), pool)
                    .thenCompose(future -> future));
            }
            int delivered = 0;
            for (CompletableFuture<StoreService.Outcome> result : results) {
                StoreService.Outcome outcome = get(result);
                if (outcome.status() == StoreService.Status.DELIVERED) {
                    delivered++;
                } else {
                    assertEquals(StoreService.Status.ALREADY, outcome.status());
                }
            }
            assertEquals(1, delivered);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1_000, this.ledger.balance(player, Currency.MONEY));
        assertEquals(1, rows("race"));
    }

    @Test
    void refusalsChangeNothingAndRecordNothing() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals("too_much", get(this.store.currency(player, Currency.MONEY, 1_000_001, "big", "console")).reason());
        assertEquals("too_much", get(this.store.currency(player, Currency.SHARDS, 50_001, "big", "console")).reason());
        assertEquals("bad_amount", get(this.store.currency(player, Currency.MONEY, 0, "zero", "console")).reason());
        assertEquals("bad_ref", get(this.store.currency(player, Currency.MONEY, 10, "has space", "console")).reason());
        this.allow.set(false);
        assertEquals("cancelled", get(this.store.currency(player, Currency.MONEY, 10, "blocked", "console")).reason());
        this.allow.set(true);
        assertEquals(0, this.ledger.balance(player, Currency.MONEY));
        assertEquals(0, this.store.size());
        assertEquals(StoreService.Status.DELIVERED, get(this.store.currency(player, Currency.MONEY, 10, "blocked", "console")).status(),
            "a cancelled reference can be delivered later");
    }

    @Test
    void theBalanceLimitRefusesWithoutRecording() throws Exception {
        UUID player = UUID.randomUUID();
        this.ledger.maxBalance(500);
        StoreService.Outcome outcome = get(this.store.currency(player, Currency.MONEY, 600, "cap", "console"));
        assertEquals("balance_limit", outcome.reason());
        assertEquals(0, rows("cap"));
    }

    // ------------------------------------------------------------------ keys

    @Test
    void keysAreGrantedOnceWithAPrefixedCrateReference() throws Exception {
        UUID player = UUID.randomUUID();
        StoreService.Outcome first = get(this.store.keys(player, "vote", 3, "k-1", "console"));
        assertEquals(StoreService.Status.DELIVERED, first.status());
        assertEquals(3, this.keys.keys(player, "vote"));
        assertEquals(Set.of("store:k-1"), this.keys.refs);
        assertEquals(StoreService.Status.ALREADY, get(this.store.keys(player, "vote", 3, "k-1", "console")).status());
        assertEquals(3, this.keys.keys(player, "vote"));
        assertEquals(1, this.keys.grants.get());
        assertEquals(1, rows("k-1"));
    }

    @Test
    void aKeyGrantAppliedBeforeACrashIsRecordedNotRepeated() throws Exception {
        UUID player = UUID.randomUUID();
        // The crates feature stored the grant, then the server died before the store recorded the reference.
        this.keys.give(player, "vote", 2, "console", "store:k-2");
        StoreService.Outcome outcome = get(this.store.keys(player, "vote", 2, "k-2", "console"));
        assertEquals(StoreService.Status.ALREADY, outcome.status());
        assertEquals(2, this.keys.keys(player, "vote"), "the keys were not given again");
        assertEquals(1, rows("k-2"), "the reference is recorded now");
    }

    @Test
    void keyRefusals() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals("unknown_crate", get(this.store.keys(player, "nope", 1, "k-3", "console")).reason());
        assertEquals("too_much", get(this.store.keys(player, "vote", 101, "k-3", "console")).reason());
        this.keys.fail = true;
        assertEquals("limit", get(this.store.keys(player, "vote", 1, "k-3", "console")).reason());
        assertEquals(0, rows("k-3"));
    }

    // ------------------------------------------------------------------ ranks

    @Test
    void aTimedRankIsGrantedOnceAndRecordedDone() throws Exception {
        UUID player = UUID.randomUUID();
        StoreService.Outcome outcome = get(this.store.rank(player, "Elite", Duration.ofDays(30), "r-1", "console"));
        assertEquals(StoreService.Status.DELIVERED, outcome.status(), String.valueOf(outcome));
        Delivery delivery = outcome.delivery();
        assertFalse(delivery.pending());
        assertEquals("elite", delivery.item());
        assertEquals(Duration.ofDays(30).toSeconds(), delivery.duration());
        RankAccess.Held held = this.ranks.held(player, "elite").get();
        assertEquals(Instant.ofEpochSecond(delivery.until()), held.until());
        assertTrue(held.until().isAfter(Instant.now().plus(Duration.ofDays(29))));
        assertEquals("done", state("r-1"));

        assertEquals(StoreService.Status.ALREADY, get(this.store.rank(player, "elite", Duration.ofDays(30), "r-1", "console")).status());
        assertEquals(held, this.ranks.held(player, "elite").get(), "the same reference never extends the rank");
        assertEquals(1, this.ranks.ensures.get());
    }

    @Test
    void twoPurchasesOfTheSameRankAddUp() throws Exception {
        UUID player = UUID.randomUUID();
        CompletableFuture<StoreService.Outcome> a = this.store.rank(player, "elite", Duration.ofDays(30), "r-a", "console");
        CompletableFuture<StoreService.Outcome> b = this.store.rank(player, "elite", Duration.ofDays(30), "r-b", "console");
        assertEquals(StoreService.Status.DELIVERED, get(a).status());
        assertEquals(StoreService.Status.DELIVERED, get(b).status());
        Instant until = this.ranks.held(player, "elite").get().until();
        Duration total = Duration.between(Instant.now(), until);
        assertTrue(total.compareTo(Duration.ofDays(59)) > 0 && total.compareTo(Duration.ofDays(61)) < 0, "60 days in total: " + total);
    }

    @Test
    void aPermanentRankStaysPermanent() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals(StoreService.Status.DELIVERED, get(this.store.rank(player, "legend", null, "r-p", "console")).status());
        assertTrue(this.ranks.held(player, "legend").get().permanent());
        StoreService.Outcome timed = get(this.store.rank(player, "legend", Duration.ofDays(7), "r-t", "console"));
        assertEquals(StoreService.Status.DELIVERED, timed.status());
        assertTrue(timed.delivery().permanent(), "buying time on a permanent rank keeps it permanent");
        assertTrue(this.ranks.held(player, "legend").get().permanent());
    }

    @Test
    void aFailedLuckPermsChangeStaysPendingAndTheSameReferenceFinishesIt() throws Exception {
        UUID player = UUID.randomUUID();
        this.ranks.fail = true;
        StoreService.Outcome failed = get(this.store.rank(player, "elite", Duration.ofDays(30), "r-f", "console"));
        assertEquals(StoreService.Status.FAILED, failed.status());
        assertEquals("luckperms_failed", failed.reason());
        assertNotNull(failed.delivery());
        assertTrue(failed.delivery().pending());
        assertEquals("pending", state("r-f"));
        assertEquals(1, this.store.pendingCount());
        long until = failed.delivery().until();

        this.ranks.fail = false;
        StoreService.Outcome finished = get(this.store.rank(player, "elite", Duration.ofDays(30), "r-f", "console"));
        assertEquals(StoreService.Status.DELIVERED, finished.status());
        assertEquals(until, finished.delivery().until(), "finished with the end worked out the first time");
        assertEquals(Instant.ofEpochSecond(until), this.ranks.held(player, "elite").get().until());
        assertEquals("done", state("r-f"));
        assertEquals(0, this.store.pendingCount());
    }

    @Test
    void aRankInterruptedByARestartIsFinishedOnStartWithoutAddingTimeTwice() throws Exception {
        UUID player = UUID.randomUUID();
        this.ranks.applyThenFail = true;
        StoreService.Outcome interrupted = get(this.store.rank(player, "elite", Duration.ofDays(30), "r-c", "console"));
        assertEquals(StoreService.Status.FAILED, interrupted.status(), "LuckPerms changed but the answer was lost");
        Instant granted = this.ranks.held(player, "elite").get().until();

        this.ranks.applyThenFail = false;
        restart();
        assertEquals(1, this.store.pendingCount());
        List<CompletableFuture<StoreService.Outcome>> resumed = this.store.resumePending();
        assertEquals(1, resumed.size());
        assertEquals(StoreService.Status.DELIVERED, get(resumed.getFirst()).status());
        assertEquals(granted, this.ranks.held(player, "elite").get().until(), "resuming is idempotent: no extra time");
        assertEquals(0, this.store.pendingCount());
    }

    @Test
    void rankRefusals() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals("group_not_allowed", get(this.store.rank(player, "admin", null, "r-x", "console")).reason());
        assertEquals("group_not_allowed", get(this.store.rank(player, "group.admin", null, "r-x", "console")).reason());
        this.ranks.groups.remove("legend");
        assertEquals("unknown_group", get(this.store.rank(player, "legend", null, "r-x", "console")).reason());
        assertEquals("bad_duration", get(this.store.rank(player, "elite", Duration.ofMinutes(5), "r-x", "console")).reason());
        assertEquals("bad_duration", get(this.store.rank(player, "elite", Duration.ofDays(400), "r-x", "console")).reason());
        this.ranks.available = false;
        assertEquals("no_luckperms", get(this.store.rank(player, "elite", null, "r-x", "console")).reason());
        assertEquals(0, rows("r-x"));
    }

    @Test
    void historyListsAPlayersDeliveriesNewestFirst() throws Exception {
        UUID player = UUID.randomUUID();
        get(this.store.currency(player, Currency.MONEY, 10, "h-1", "console"));
        Thread.sleep(5);
        get(this.store.keys(player, "vote", 1, "h-2", "console"));
        get(this.store.currency(UUID.randomUUID(), Currency.MONEY, 10, "h-3", "console"));
        List<Delivery> history = this.store.history(player, 10);
        assertEquals(List.of("h-2", "h-1"), history.stream().map(Delivery::ref).toList());
        assertEquals(1, this.store.history(player, 1).size());
    }

    // ------------------------------------------------------------------ revoke

    @Test
    void aRefundTakesBackWhatIsLeftOnceAndTheReferenceIsNeverDeliveredAgain() throws Exception {
        UUID player = UUID.randomUUID();
        get(this.store.currency(player, Currency.MONEY, 25_000, "rv-1", "console"));
        // The buyer already spent part of it.
        this.ledger.execute(net.siftvanilla.siftcore.economy.LedgerTx.builder()
            .sink(player, Currency.MONEY, 10_000, "test_spend", null).build()).committed().get();
        StoreService.Outcome revoked = get(this.store.revoke("rv-1", "chargeback", "console"));
        assertEquals(StoreService.Status.REVOKED, revoked.status(), String.valueOf(revoked));
        assertEquals(15_000, revoked.taken(), "as much as they still had");
        assertEquals(0, this.ledger.balance(player, Currency.MONEY));
        assertEquals("chargeback, took back 15000 of 25000", revoked.delivery().note());
        assertEquals("revoked", state("rv-1"));

        assertEquals(StoreService.Status.ALREADY, get(this.store.revoke("rv-1", "chargeback", "console")).status(), "a second revoke does nothing");
        StoreService.Outcome again = get(this.store.currency(player, Currency.MONEY, 25_000, "rv-1", "console"));
        assertEquals(StoreService.Status.ALREADY, again.status());
        assertTrue(again.delivery().revoked(), "a revoked reference is never delivered again");
        assertEquals(0, this.ledger.balance(player, Currency.MONEY));

        restart();
        assertEquals(Delivery.State.REVOKED, this.store.find("rv-1").state(), "revoked after a restart too");
        assertEquals(StoreService.Status.ALREADY, get(this.store.currency(player, Currency.MONEY, 25_000, "rv-1", "console")).status());
        assertTrue(this.ledger.audit().get().healthy());
    }

    @Test
    void aFullRefundOfShardsTakesThemAll() throws Exception {
        UUID player = UUID.randomUUID();
        get(this.store.currency(player, Currency.SHARDS, 500, "rv-2", "console"));
        StoreService.Outcome revoked = get(this.store.revoke("rv-2", null, "console"));
        assertEquals(500, revoked.taken());
        assertEquals("refund", revoked.delivery().note(), "refund is the default reason");
        assertEquals(0, this.ledger.balance(player, Currency.SHARDS));
    }

    @Test
    void revokedKeysAreRecordedButStay() throws Exception {
        UUID player = UUID.randomUUID();
        get(this.store.keys(player, "vote", 3, "rv-3", "console"));
        StoreService.Outcome revoked = get(this.store.revoke("rv-3", "refund", "console"));
        assertEquals(StoreService.Status.REVOKED, revoked.status());
        assertEquals("refund, keys not taken back", revoked.delivery().note());
        assertEquals(3, this.keys.keys(player, "vote"), "the crates contract can't take keys; staff do it by hand");
        assertEquals("revoked", state("rv-3"));
    }

    @Test
    void revokingATimedRankTakesItsTimeOffTheCurrentEnd() throws Exception {
        UUID player = UUID.randomUUID();
        get(this.store.rank(player, "elite", Duration.ofDays(30), "rv-a", "console"));
        get(this.store.rank(player, "elite", Duration.ofDays(30), "rv-b", "console"));
        Instant sixty = this.ranks.held(player, "elite").get().until();

        StoreService.Outcome first = get(this.store.revoke("rv-a", "refund", "console"));
        assertEquals(StoreService.Status.REVOKED, first.status(), String.valueOf(first));
        assertEquals(sixty.minus(Duration.ofDays(30)), this.ranks.held(player, "elite").get().until(), "30 of the 60 days are left");
        assertEquals(sixty.minus(Duration.ofDays(30)).getEpochSecond(), first.delivery().revokeUntil());

        StoreService.Outcome second = get(this.store.revoke("rv-b", "refund", "console"));
        assertEquals(StoreService.Status.REVOKED, second.status());
        assertEquals(0, second.delivery().revokeUntil(), "nothing left: the grant is removed");
        assertEquals(new RankAccess.Held(false, null), this.ranks.held(player, "elite").get());
        assertEquals(StoreService.Status.ALREADY, get(this.store.revoke("rv-b", "refund", "console")).status());
    }

    @Test
    void revokingAPermanentRankRemovesIt() throws Exception {
        UUID player = UUID.randomUUID();
        get(this.store.rank(player, "legend", null, "rv-p", "console"));
        StoreService.Outcome revoked = get(this.store.revoke("rv-p", "chargeback", "console"));
        assertEquals(StoreService.Status.REVOKED, revoked.status());
        assertEquals(new RankAccess.Held(false, null), this.ranks.held(player, "legend").get());
    }

    @Test
    void anInterruptedRankRevokeIsFinishedWithoutTakingTimeTwice() throws Exception {
        UUID player = UUID.randomUUID();
        get(this.store.rank(player, "elite", Duration.ofDays(30), "rv-c1", "console"));
        get(this.store.rank(player, "elite", Duration.ofDays(30), "rv-c2", "console"));
        Instant sixty = this.ranks.held(player, "elite").get().until();
        this.ranks.applyThenFail = true;
        StoreService.Outcome interrupted = get(this.store.revoke("rv-c1", "refund", "console"));
        assertEquals(StoreService.Status.FAILED, interrupted.status());
        assertEquals(Delivery.State.REVOKING, interrupted.delivery().state());
        assertEquals("revoking", state("rv-c1"));
        this.ranks.applyThenFail = false;

        restart();
        assertEquals(1, this.store.pendingCount());
        List<CompletableFuture<StoreService.Outcome>> resumed = this.store.resumePending();
        assertEquals(StoreService.Status.REVOKED, get(resumed.getFirst()).status());
        assertEquals(sixty.minus(Duration.ofDays(30)), this.ranks.held(player, "elite").get().until(), "30 days taken once, not twice");
        assertEquals(0, this.store.pendingCount());
    }

    @Test
    void revokeRefusals() throws Exception {
        assertEquals("unknown_ref", get(this.store.revoke("nothing-here", "refund", "console")).reason());
        UUID player = UUID.randomUUID();
        get(this.store.rank(player, "elite", Duration.ofDays(30), "rv-x", "console"));
        this.ranks.available = false;
        assertEquals("no_luckperms", get(this.store.revoke("rv-x", "refund", "console")).reason());
        assertEquals("done", state("rv-x"), "nothing recorded without LuckPerms");
    }

    @Test
    void revokePlans() {
        Instant now = Instant.parse("2026-10-08T12:00:00Z");
        Delivery timed = Delivery.of("p", StoreDeliveryEvent.Kind.RANK, UUID.randomUUID(), "elite", 0, Duration.ofDays(30).toSeconds(),
            Delivery.State.DONE, "console", 0, 1);
        Delivery permanent = Delivery.of("q", StoreDeliveryEvent.Kind.RANK, UUID.randomUUID(), "elite", 0, 0, Delivery.State.DONE,
            "console", 0, 0);
        Instant fortyDays = now.plus(Duration.ofDays(40));
        assertEquals(now.plus(Duration.ofDays(10)).getEpochSecond(), StoreService.revokePlan(timed, new RankAccess.Held(false, fortyDays), now));
        assertEquals(0, StoreService.revokePlan(timed, new RankAccess.Held(false, now.plus(Duration.ofDays(20))), now));
        assertEquals(-1, StoreService.revokePlan(timed, new RankAccess.Held(true, null), now), "a permanent holder has no time to cut");
        assertEquals(-1, StoreService.revokePlan(permanent, new RankAccess.Held(true, null), now), "a permanent purchase leaves timed grants");
    }

    // ------------------------------------------------------------------ boosters

    @Test
    void aBoosterIsDeliveredOncePerReferenceEvenUnderConcurrentRetries() throws Exception {
        UUID buyer = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<StoreService.Outcome>> attempts = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                attempts.add(CompletableFuture.supplyAsync(() -> this.store.booster(buyer, "sell", 10, Duration.ofMinutes(30), "tbx-boost",
                    "console"), pool).thenCompose(future -> future));
            }
            int delivered = 0;
            for (CompletableFuture<StoreService.Outcome> attempt : attempts) {
                StoreService.Outcome outcome = get(attempt);
                if (outcome.status() == StoreService.Status.DELIVERED) {
                    delivered++;
                } else {
                    assertEquals(StoreService.Status.ALREADY, outcome.status());
                }
            }
            assertEquals(1, delivered, "exactly one of 64 concurrent deliveries starts a booster");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, this.boosters.started.size());
        assertEquals(new ServerBoosters.Grant("sell", 10, Duration.ofMinutes(30), buyer, "tbx-boost", "console"),
            this.boosters.started.getFirst());
        assertEquals(1, rows("tbx-boost"));
        Delivery delivery = this.store.find("tbx-boost");
        assertEquals(StoreDeliveryEvent.Kind.BOOSTER, delivery.kind());
        assertEquals(10, delivery.amount());
        assertEquals(1800, delivery.duration());
        restart();
        assertEquals(StoreService.Status.ALREADY, get(this.store.booster(buyer, "sell", 10, Duration.ofMinutes(30), "tbx-boost", "console")).status());
        assertEquals(1, this.boosters.started.size(), "a retry after a restart starts nothing");
    }

    @Test
    void consoleBoostersHaveNoBuyerAndRefusalsRecordNothing() throws Exception {
        assertEquals(StoreService.Status.DELIVERED,
            get(this.store.booster(StoreService.SERVER, "sell", 10, Duration.ofHours(48), "goal-2026-10", "console")).status());
        assertNull(this.boosters.started.getFirst().owner(), "a community goal booster is from the server itself");
        assertEquals("bad_booster_percent", get(this.store.booster(UUID.randomUUID(), "sell", 60, Duration.ofMinutes(30), "tbx-x1", "console")).reason());
        assertEquals("bad_booster_duration", get(this.store.booster(UUID.randomUUID(), "sell", 10, Duration.ofSeconds(5), "tbx-x2", "console")).reason());
        assertEquals("unknown_booster", get(this.store.booster(UUID.randomUUID(), "xp", 10, Duration.ofMinutes(30), "tbx-x3", "console")).reason());
        assertEquals("bad_ref", get(this.store.booster(UUID.randomUUID(), "sell", 10, Duration.ofMinutes(30), "bad ref!", "console")).reason());
        this.allow.set(false);
        assertEquals("cancelled", get(this.store.booster(UUID.randomUUID(), "sell", 10, Duration.ofMinutes(30), "tbx-x4", "console")).reason());
        this.allow.set(true);
        for (String ref : List.of("tbx-x1", "tbx-x2", "tbx-x3", "tbx-x4")) {
            assertNull(this.store.find(ref), ref + " recorded nothing");
            assertEquals(0, rows(ref));
        }
        assertEquals(1, this.boosters.started.size());
    }

    @Test
    void aPaidBoosterAboveTheCurrentLimitIsStillDelivered() throws Exception {
        UUID buyer = UUID.randomUUID();
        // max-percent is 25 here: a +40% package (bought before the owner lowered it) and a 20 day one still arrive.
        assertEquals(StoreService.Status.DELIVERED,
            get(this.store.booster(buyer, "sell", 40, Duration.ofMinutes(30), "tbx-big", "console")).status());
        assertEquals(StoreService.Status.DELIVERED,
            get(this.store.booster(buyer, "sell", 10, Duration.ofDays(20), "tbx-long", "console")).status());
        assertEquals(2, this.boosters.started.size());
        assertEquals(40, this.boosters.started.getFirst().percent(), "recorded as bought");
        assertEquals(25, this.store.boosterStatus("tbx-big").orElseThrow().percent(), "it pays the current limit");
        assertEquals(1, rows("tbx-big"));
        assertEquals("bad_booster_percent", get(this.store.booster(buyer, "sell", ServerBoosters.PERCENT_CAP + 1,
            Duration.ofMinutes(30), "tbx-cap", "console")).reason(), "the hard cap still refuses");
        assertEquals("bad_booster_duration", get(this.store.booster(buyer, "sell", 10, ServerBoosters.MAX_LENGTH.plusSeconds(1),
            "tbx-cap2", "console")).reason());
    }

    @Test
    void revokingABoosterEndsItOrTakesItOutOfLineOnce() throws Exception {
        UUID buyer = UUID.randomUUID();
        get(this.store.booster(buyer, "sell", 10, Duration.ofMinutes(30), "tbx-r1", "console"));
        get(this.store.booster(buyer, "sell", 15, Duration.ofMinutes(30), "tbx-r2", "console"));
        assertTrue(this.store.boosterStatus("tbx-r2").isPresent());
        assertFalse(this.store.boosterStatus("tbx-r2").get().running());

        StoreService.Outcome queued = get(this.store.revoke("tbx-r2", "refund", "console"));
        assertEquals(StoreService.Status.REVOKED, queued.status());
        assertEquals("removed", queued.reason());
        assertEquals("refund, booster taken out of line", queued.delivery().note());
        StoreService.Outcome running = get(this.store.revoke("tbx-r1", "chargeback", "console"));
        assertEquals("ended", running.reason());
        assertEquals("revoked", state("tbx-r1"));
        assertEquals(StoreService.Status.ALREADY, get(this.store.revoke("tbx-r1", "refund", "console")).status());
        assertEquals(StoreService.Status.ALREADY, get(this.store.booster(buyer, "sell", 10, Duration.ofMinutes(30), "tbx-r1", "console")).status(),
            "a revoked reference is never delivered again");
        assertEquals(List.of("tbx-r2", "tbx-r1"), this.boosters.revoked);

        get(this.store.booster(buyer, "sell", 10, Duration.ofMinutes(30), "tbx-r3", "console"));
        this.boosters.over.add("tbx-r3");
        StoreService.Outcome over = get(this.store.revoke("tbx-r3", "refund", "console"));
        assertEquals("over", over.reason());
        assertEquals("refund, booster had already ended", over.delivery().note());
        restart();
        assertEquals(Delivery.State.REVOKED, this.store.find("tbx-r1").state());
        assertEquals(3, this.store.history(buyer, 10).size());
    }

    // ------------------------------------------------------------------ fakes

    /** The boosters feature as the store sees it: a booster starts or waits inside the delivery's transaction. */
    private static final class FakeBoosters implements ServerBoosters {
        final List<Grant> started = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> revoked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Set<String> over = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int percent() {
            return 0;
        }

        @Override
        public int maxPercent() {
            return 25;
        }

        @Override
        public int latestMaxPercent() {
            return 25;
        }

        @Override
        public int paid(int percent) {
            return Math.min(percent, maxPercent());
        }

        /** The contract: only the hard limits refuse a purchase; max-percent (25 here) caps what it pays instead. */
        @Override
        public String problem(String kind, int percent, Duration duration) {
            if (!SELL.equals(kind)) {
                return "unknown_kind";
            }
            if (percent < 1 || percent > PERCENT_CAP) {
                return "bad_percent";
            }
            return duration.compareTo(MIN_LENGTH) < 0 || duration.compareTo(MAX_LENGTH) > 0 ? "bad_duration" : null;
        }

        @Override
        public void deliver(net.siftvanilla.siftcore.economy.LedgerTx.Builder tx, Grant grant) {
            tx.apply(() -> this.started.add(grant), () -> this.started.remove(grant));
        }

        @Override
        public java.util.concurrent.atomic.AtomicReference<Revoked> revoke(net.siftvanilla.siftcore.economy.LedgerTx.Builder tx, String ref) {
            java.util.concurrent.atomic.AtomicReference<Revoked> outcome = new java.util.concurrent.atomic.AtomicReference<>(Revoked.OVER);
            tx.apply(() -> {
                if (this.over.contains(ref)) {
                    outcome.set(Revoked.OVER);
                    return;
                }
                boolean first = this.started.stream().filter(grant -> !this.revoked.contains(grant.ref())).findFirst()
                    .map(grant -> grant.ref().equals(ref)).orElse(false);
                outcome.set(first ? Revoked.ENDED : Revoked.REMOVED);
                this.revoked.add(ref);
            }, () -> this.revoked.remove(ref));
            return outcome;
        }

        @Override
        public java.util.Optional<Status> status(String ref) {
            for (int i = 0; i < this.started.size(); i++) {
                if (this.started.get(i).ref().equals(ref) && !this.revoked.contains(ref)) {
                    return java.util.Optional.of(new Status(i == 0, paid(this.started.get(i).percent()), this.started.get(i).duration(), i));
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** Crate keys with the crates feature's reference rule: a reference is applied once. */
    private static final class FakeKeys implements CrateKeys {
        final Map<String, Integer> counts = new HashMap<>();
        final Set<String> refs = new HashSet<>();
        final AtomicInteger grants = new AtomicInteger();
        volatile boolean fail;

        @Override
        public Set<String> crates() {
            return Set.of("vote", "legendary");
        }

        @Override
        public synchronized TransactionResult give(UUID player, String crate, int amount, String actor, String ref) {
            if (this.fail) {
                return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.REJECTED, "limit");
            }
            if (ref != null && !this.refs.add(ref)) {
                return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.REJECTED, "duplicate");
            }
            this.counts.merge(player + crate, amount, Integer::sum);
            this.grants.incrementAndGet();
            return new TransactionResult(UUID.randomUUID(), TransactionStatus.SUCCESS, null, CompletableFuture.completedFuture(null));
        }

        @Override
        public synchronized int keys(UUID player, String crate) {
            return this.counts.getOrDefault(player + crate, 0);
        }
    }

    /** LuckPerms as the hook behaves: ensure keeps the longer of the current and the requested grant. */
    private static final class FakeRanks implements RankAccess {
        final Set<String> groups = new HashSet<>(Set.of("elite", "legend", "admin"));
        final Map<String, Held> holds = new HashMap<>();
        final AtomicInteger ensures = new AtomicInteger();
        final AtomicInteger limits = new AtomicInteger();
        volatile boolean available = true;
        volatile boolean fail;
        volatile boolean applyThenFail;

        @Override
        public boolean available() {
            return this.available;
        }

        @Override
        public boolean groupExists(String group) {
            return this.groups.contains(group);
        }

        @Override
        public synchronized CompletableFuture<Held> held(UUID player, String group) {
            return CompletableFuture.completedFuture(this.holds.getOrDefault(player + group, new Held(false, null)));
        }

        @Override
        public CompletableFuture<Boolean> ensure(UUID player, String group, Instant until) {
            if (this.fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("storage is down"));
            }
            boolean changed;
            synchronized (this) {
                this.ensures.incrementAndGet();
                Held current = this.holds.getOrDefault(player + group, new Held(false, null));
                changed = !current.permanent() && (until == null || current.until() == null || current.until().isBefore(until));
                if (changed) {
                    this.holds.put(player + group, until == null ? new Held(true, null) : new Held(false, until));
                }
            }
            if (this.applyThenFail) {
                return CompletableFuture.failedFuture(new IllegalStateException("connection lost after saving"));
            }
            return CompletableFuture.completedFuture(changed);
        }

        @Override
        public CompletableFuture<Boolean> permission(UUID player, String node) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Boolean> limit(UUID player, String group, boolean removePermanent, boolean cutTimed, Instant cutTo) {
            if (this.fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("storage is down"));
            }
            boolean changed = false;
            synchronized (this) {
                this.limits.incrementAndGet();
                Held current = this.holds.getOrDefault(player + group, new Held(false, null));
                Held next = current;
                if (current.permanent() && removePermanent) {
                    next = new Held(false, null);
                } else if (!current.permanent() && current.until() != null && cutTimed) {
                    if (cutTo == null || !cutTo.isAfter(Instant.now())) {
                        next = new Held(false, null);
                    } else if (current.until().isAfter(cutTo)) {
                        next = new Held(false, cutTo);
                    }
                }
                if (!next.equals(current)) {
                    this.holds.put(player + group, next);
                    changed = true;
                }
            }
            if (this.applyThenFail) {
                return CompletableFuture.failedFuture(new IllegalStateException("connection lost after saving"));
            }
            return CompletableFuture.completedFuture(changed);
        }
    }
}
