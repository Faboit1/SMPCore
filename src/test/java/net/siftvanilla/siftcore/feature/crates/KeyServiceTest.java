package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keys against a real migrated SQLite database and the real ledger. */
class KeyServiceTest {

    private static final Set<String> CRATES = Set.of("basic", "rare");

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Ledger ledger;
    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);

    private JdbcDatabase open() throws Exception {
        Logger logger = Logger.getLogger("crates-test");
        JdbcDatabase db = new JdbcDatabase(new SqliteSource(this.dir.resolve("crates-test.db"), 2), logger);
        ClassLoader loader = KeyServiceTest.class.getClassLoader();
        new Migrations(db, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        return db;
    }

    private KeyService service() throws Exception {
        KeyService service = new KeyService(this.ledger, this.database, () -> CRATES, this.clock::get);
        service.load(Duration.ofDays(90));
        return service;
    }

    @BeforeEach
    void setUp() throws Exception {
        this.database = open();
        this.ledger = new Ledger(this.database, Logger.getLogger("crates-ledger"), 1_000_000_000_000L);
        this.ledger.load();
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    private static void committed(TransactionResult result) throws Exception {
        assertTrue(result.success(), "expected success, got " + result.status() + " " + result.reason());
        result.committed().get(10, TimeUnit.SECONDS);
    }

    private int stored(UUID player, String crate) throws Exception {
        this.database.flush();
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM crate_keys WHERE uuid = ? AND crate = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, crate);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).get();
    }

    @Test
    void giveAndTakeAreStoredAndSurviveARestart() throws Exception {
        KeyService keys = service();
        UUID player = UUID.randomUUID();
        committed(keys.give(player, "basic", 5, "console", null));
        committed(keys.take(player, "basic", 2, "console"));
        assertEquals(3, keys.keys(player, "basic"));
        assertEquals(3, stored(player, "basic"));
        assertEquals(0, keys.keys(player, "rare"));

        KeyService restarted = service();
        assertEquals(3, restarted.keys(player, "basic"));
        assertEquals(Map.of("basic", 3), restarted.keysOf(player));
        assertNull(restarted.verify().get(10, TimeUnit.SECONDS));
    }

    @Test
    void takingTheLastKeyDeletesTheRow() throws Exception {
        KeyService keys = service();
        UUID player = UUID.randomUUID();
        committed(keys.give(player, "rare", 1, "console", null));
        committed(keys.take(player, "rare", 1, "console"));
        assertEquals(0, keys.keys(player, "rare"));
        int rows = this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM crate_keys WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }).get();
        assertEquals(0, rows);
        assertNull(keys.verify().get(10, TimeUnit.SECONDS));
    }

    @Test
    void takingMoreThanTheyHaveChangesNothing() throws Exception {
        KeyService keys = service();
        UUID player = UUID.randomUUID();
        committed(keys.give(player, "basic", 2, "console", null));
        TransactionResult result = keys.take(player, "basic", 3, "console");
        assertEquals(TransactionStatus.REJECTED, result.status());
        assertEquals(KeyService.NOT_ENOUGH, result.reason());
        assertEquals(2, keys.keys(player, "basic"));
        assertEquals(2, stored(player, "basic"));
    }

    @Test
    void aReferenceIsAppliedOnlyOnceEvenAfterARestart() throws Exception {
        KeyService keys = service();
        UUID player = UUID.randomUUID();
        committed(keys.give(player, "rare", 3, "store", "tebex-1001"));
        TransactionResult again = keys.give(player, "rare", 3, "store", "tebex-1001");
        assertEquals(TransactionStatus.REJECTED, again.status());
        assertEquals(KeyService.DUPLICATE, again.reason());
        assertEquals(3, keys.keys(player, "rare"));
        committed(keys.give(player, "rare", 1, "store", "tebex-1002"));
        assertEquals(4, keys.keys(player, "rare"));

        KeyService restarted = service();
        TransactionResult afterRestart = restarted.give(player, "rare", 3, "store", "tebex-1001");
        assertEquals(KeyService.DUPLICATE, afterRestart.reason());
        assertEquals(4, restarted.keys(player, "rare"));
        assertEquals(4, stored(player, "rare"));
        assertNull(restarted.verify().get(10, TimeUnit.SECONDS));
    }

    @Test
    void racingGrantsWithOneReferenceApplyExactlyOnce() throws Exception {
        KeyService keys = service();
        UUID player = UUID.randomUUID();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            runs.add(CompletableFuture.runAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                TransactionResult result = keys.give(player, "basic", 1, "system", "keyall:s1:" + player);
                if (result.success()) {
                    successes.incrementAndGet();
                } else if (KeyService.DUPLICATE.equals(result.reason())) {
                    duplicates.incrementAndGet();
                }
            }));
        }
        start.countDown();
        CompletableFuture.allOf(runs.toArray(new CompletableFuture<?>[0])).get(20, TimeUnit.SECONDS);
        assertEquals(1, successes.get());
        assertEquals(threads - 1, duplicates.get());
        assertEquals(1, keys.keys(player, "basic"));
        assertEquals(1, stored(player, "basic"));
        assertNull(keys.verify().get(10, TimeUnit.SECONDS));
    }

    @Test
    void forgottenReferencesCanBeUsedAgain() throws Exception {
        KeyService keys = service();
        UUID player = UUID.randomUUID();
        committed(keys.give(player, "basic", 1, "system", "old-ref"));
        this.clock.addAndGet(Duration.ofDays(91).toMillis());
        committed(keys.give(player, "basic", 1, "system", "new-ref"));
        assertEquals(1, keys.prune(Duration.ofDays(90)));
        committed(keys.give(player, "basic", 1, "system", "old-ref"));
        assertEquals(KeyService.DUPLICATE, keys.give(player, "basic", 1, "system", "new-ref").reason());
        assertEquals(3, keys.keys(player, "basic"));
        assertNull(keys.verify().get(10, TimeUnit.SECONDS));

        // References older than the window are deleted at startup too.
        this.clock.addAndGet(Duration.ofDays(91).toMillis());
        KeyService restarted = service();
        assertEquals(0, restarted.book().refCount());
        assertNull(restarted.verify().get(10, TimeUnit.SECONDS));
    }

    @Test
    void badGrantsAreRefusedWithAReason() throws Exception {
        KeyService keys = service();
        UUID player = UUID.randomUUID();
        assertEquals(KeyService.UNKNOWN_CRATE, keys.give(player, "mythic", 1, "console", null).reason());
        assertEquals(KeyService.BAD_AMOUNT, keys.give(player, "basic", 0, "console", null).reason());
        assertEquals(KeyService.BAD_AMOUNT, keys.give(player, "basic", -5, "console", null).reason());
        assertEquals(KeyService.BAD_REF, keys.give(player, "basic", 1, "console", "x".repeat(65)).reason());
        assertEquals(KeyService.BAD_REF, keys.give(player, "basic", 1, "console", "  ").reason());
        committed(keys.give(player, "basic", KeyBook.MAX_KEYS, "console", null));
        assertEquals(KeyService.LIMIT, keys.give(player, "basic", 1, "console", null).reason());
        assertEquals(KeyBook.MAX_KEYS, keys.keys(player, "basic"));
    }

    @Test
    void openingSpendsTheKeyAndPaysTheRewardInOneTransaction() throws Exception {
        KeyService keys = service();
        CrateLog log = new CrateLog(this.database);
        UUID player = UUID.randomUUID();
        committed(keys.give(player, "basic", 1, "console", null));
        LedgerTx.Builder tx = LedgerTx.builder().actor(player).source(player, Currency.MONEY, 750, CrateOpener.LEDGER_KIND, "crate:x");
        keys.spend(tx, player, "basic", 1, KeyService.NO_KEYS);
        keys.grant(tx, player, "rare", 2, null, player.toString());
        log.add(tx, this.clock.get(), player, "basic", "money-small", "$750 | crate:x");
        committed(this.ledger.execute(tx.build()));
        assertEquals(0, keys.keys(player, "basic"));
        assertEquals(2, keys.keys(player, "rare"));
        assertEquals(750, this.ledger.balance(player, Currency.MONEY));
        List<CrateLog.Entry> entries = log.recent(player, 10, 0).get(10, TimeUnit.SECONDS);
        assertEquals(1, entries.size());
        assertEquals("money-small", entries.getFirst().reward());
        assertEquals(1L, log.count(player).get(10, TimeUnit.SECONDS));

        // With no key left, a second opening changes nothing at all.
        LedgerTx.Builder second = LedgerTx.builder().actor(player).source(player, Currency.MONEY, 750, CrateOpener.LEDGER_KIND, "crate:y");
        keys.spend(second, player, "basic", 1, KeyService.NO_KEYS);
        log.add(second, this.clock.get(), player, "basic", "money-small", "$750 | crate:y");
        TransactionResult refused = this.ledger.execute(second.build());
        assertEquals(KeyService.NO_KEYS, refused.reason());
        assertEquals(750, this.ledger.balance(player, Currency.MONEY));
        assertEquals(1L, log.count(player).get(10, TimeUnit.SECONDS));
        assertTrue(this.ledger.audit().get(10, TimeUnit.SECONDS).healthy());
        assertNull(keys.verify().get(10, TimeUnit.SECONDS));
    }

    @Test
    void aStorageFailureRollsTheKeyBack() throws Exception {
        KeyService keys = service();
        UUID player = UUID.randomUUID();
        committed(keys.give(player, "basic", 2, "console", null));
        LedgerTx.Builder tx = LedgerTx.builder().actor(player).silent();
        keys.spend(tx, player, "basic", 1, KeyService.NO_KEYS);
        keys.grant(tx, player, "rare", 1, "will-fail", player.toString());
        tx.write(c -> {
            throw new SQLException("disk full");
        });
        TransactionResult result = this.ledger.execute(tx.build());
        assertTrue(result.success(), "applied in memory first");
        boolean failed;
        try {
            result.committed().get(10, TimeUnit.SECONDS);
            failed = false;
        } catch (Exception e) {
            failed = true;
        }
        assertTrue(failed, "the commit fails");
        assertEquals(2, keys.keys(player, "basic"));
        assertEquals(0, keys.keys(player, "rare"));
        assertFalse(keys.book().applied("will-fail"), "a rolled back grant can be retried with its reference");
        committed(keys.give(player, "rare", 1, "console", "will-fail"));
        assertNull(keys.verify().get(10, TimeUnit.SECONDS));
    }

    /** Other features (store deliveries) word keys through the crates feature: one key is singular. */
    @Test
    void keysReadLikeTheCratesText() throws Exception {
        assertEquals("1 basic key", TextStyle.plain(CrateKeys.NONE.keysText("basic", 1)));
        assertEquals("3 basic keys", TextStyle.plain(CrateKeys.NONE.keysText("basic", 3)));
        assertEquals("1 basic key", TextStyle.plain(service().keysText("basic", 1)), "no wording given: the default");
        KeyService worded = new KeyService(this.ledger, this.database, () -> CRATES, this.clock::get,
            (crate, amount) -> Component.text(amount == 1 ? "1 Basic key" : amount + " Basic keys"),
            crate -> Component.text("Common", net.kyori.adventure.text.format.TextColor.color(0xC8C8C8)));
        assertEquals("1 Basic key", TextStyle.plain(worded.keysText("basic", 1)));
        assertEquals("2 Basic keys", TextStyle.plain(CrateKeys.late(() -> worded).keysText("basic", 2)), "late keys pass it on");
        assertEquals("basic", TextStyle.plain(CrateKeys.NONE.crateName("basic")), "without the crates feature: the id");
        assertEquals("Common", TextStyle.plain(CrateKeys.late(() -> worded).crateName("basic")), "the crate's name, passed on");
        assertEquals(0xC8C8C8, CrateKeys.late(() -> worded).crateName("basic").color().value(), "in the crate's colour");
    }
}
