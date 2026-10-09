package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
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

/** Mastery totals move with the sale's transaction, and the top sellers are read from them. */
class MasteryBookTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000000c0");

    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("mastery-test");
    private JdbcDatabase database;
    private Ledger ledger;
    private MasteryBook book;

    @BeforeEach
    void setUp() throws Exception {
        this.logger.setLevel(Level.OFF);
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), this.logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, this.logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, this.logger, 1_000_000_000_000_000L);
        this.ledger.load();
        this.book = new MasteryBook(this.database, this.logger);
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    /** A sale of {@code money} with mastery credits, as the sell service builds it. */
    private TransactionResult sell(UUID player, long money, Map<String, Long> credits) {
        LedgerTx.Builder tx = LedgerTx.builder().actor(player).note("test sale")
            .source(player, Currency.MONEY, money, "sell", "hand");
        this.book.contribute(tx, player, credits);
        return this.ledger.execute(tx.build());
    }

    private void awaitLoaded(UUID player) throws InterruptedException {
        for (int i = 0; i < 200 && !this.book.loaded(player); i++) {
            Thread.sleep(10);
        }
        assertTrue(this.book.loaded(player));
    }

    @Test
    void creditsApplyInMemoryAndAddUpInStorage() throws Exception {
        this.book.load(ALICE);
        awaitLoaded(ALICE);
        assertTrue(sell(ALICE, 38_400, Map.of("mining", 25_600L)).success());
        TransactionResult second = sell(ALICE, 900, Map.of("mining", 400L, "farming", 600L));
        assertTrue(second.success());
        second.committed().get();
        assertEquals(26_000, this.book.sold(ALICE, "mining"));
        assertEquals(600, this.book.sold(ALICE, "farming"));
        assertEquals(26_600, this.book.total(ALICE));
        // storage has the same, through the additive upsert
        assertEquals(Map.of("mining", 26_000L, "farming", 600L), this.book.read(ALICE).get());
    }

    @Test
    void aRefusedSaleChangesNothing() throws Exception {
        this.book.load(ALICE);
        awaitLoaded(ALICE);
        LedgerTx.Builder tx = LedgerTx.builder().actor(ALICE).source(ALICE, Currency.MONEY, 100, "sell", "hand")
            .check(() -> "refused");
        this.book.contribute(tx, ALICE, Map.of("mining", 100L));
        assertFalse(this.ledger.execute(tx.build()).success());
        assertEquals(0, this.book.sold(ALICE, "mining"));
        this.database.flush();
        assertEquals(Map.of(), this.book.read(ALICE).get());
    }

    @Test
    void aRejoinSeesWhatEarlierSessionsStoredAndNothingTwice() throws Exception {
        this.book.load(ALICE);
        awaitLoaded(ALICE);
        sell(ALICE, 100, Map.of("mining", 50_000L)).committed().get();
        this.book.forget(ALICE);
        assertEquals(0, this.book.sold(ALICE, "mining"));
        // a sale right after joining, while the stored totals are still loading, is neither lost nor counted twice
        this.book.load(ALICE);
        TransactionResult early = sell(ALICE, 100, Map.of("mining", 1_000L));
        assertTrue(early.success());
        early.committed().get();
        awaitLoaded(ALICE);
        assertEquals(51_000, this.book.sold(ALICE, "mining"));
        assertEquals(Map.of("mining", 51_000L), this.book.read(ALICE).get());
    }

    @Test
    void staffCanSetAndResetACategory() throws Exception {
        this.book.load(BOB);
        awaitLoaded(BOB);
        sell(BOB, 10, Map.of("wood", 10L)).committed().get();
        TransactionResult set = this.ledger.executeDomain(this.book.setTotal(BOB, "wood", 250_000));
        assertTrue(set.success());
        set.committed().get();
        assertEquals(250_000, this.book.sold(BOB, "wood"));
        assertEquals(Map.of("wood", 250_000L), this.book.read(BOB).get());
        this.ledger.executeDomain(this.book.setTotal(BOB, "wood", 0)).committed().get();
        assertEquals(0, this.book.sold(BOB, "wood"));
        assertEquals(Map.of(), this.book.read(BOB).get());
    }

    @Test
    void staffCanResetEveryCategoryAtOnce() throws Exception {
        this.book.load(CAROL).get();
        assertTrue(this.book.loaded(CAROL));
        sell(CAROL, 10, Map.of("wood", 10L, "mining", 70_000L)).committed().get();
        // a category that is no longer configured is reset too
        this.ledger.executeDomain(this.book.setTotal(CAROL, "renamed", 5L)).committed().get();
        TransactionResult reset = this.ledger.executeDomain(this.book.resetAll(CAROL));
        assertTrue(reset.success());
        reset.committed().get();
        assertEquals(Map.of(), this.book.totals(CAROL));
        assertEquals(Map.of(), this.book.read(CAROL).get());
        // an offline player is reset in storage
        this.book.forget(CAROL);
        sell(BOB, 1, Map.of("farming", 3L)).committed().get();
        this.ledger.executeDomain(this.book.resetAll(BOB)).committed().get();
        assertEquals(Map.of(), this.book.read(BOB).get());
    }

    @Test
    void theLoadFinishesWithTheStoredTotals() throws Exception {
        this.book.load(ALICE).get();
        sell(ALICE, 100, Map.of("fishing", 12L)).committed().get();
        this.book.forget(ALICE);
        assertFalse(this.book.present(ALICE));
        this.book.load(ALICE).get();
        assertTrue(this.book.present(ALICE) && this.book.loaded(ALICE));
        assertEquals(12, this.book.sold(ALICE, "fishing"));
    }

    @Test
    void topSellersRankEveryCategoryTogether() throws Exception {
        for (UUID player : List.of(ALICE, BOB, CAROL)) {
            this.book.load(player);
            awaitLoaded(player);
        }
        sell(ALICE, 1, Map.of("mining", 100L, "wood", 450L)).committed().get();
        sell(BOB, 1, Map.of("farming", 900L)).committed().get();
        sell(CAROL, 1, Map.of("wood", 500L)).committed().get();
        TopSellers top = new TopSellers(this.database, uuid -> uuid.equals(BOB) ? "Bob" : null);
        TopSellers.Snapshot snapshot = top.refresh(HiddenSellers.NONE).get();
        assertEquals(List.of(BOB, ALICE, CAROL), snapshot.top().stream().map(TopSellers.Entry::uuid).toList());
        assertEquals("Bob", snapshot.place(1).orElseThrow().name());
        assertEquals(900, snapshot.place(1).orElseThrow().sold());
        assertEquals(500, snapshot.byUuid().get(CAROL));
        // a total equal to someone's shares their place; totals below everyone come last
        assertEquals(2, snapshot.rankOf(550));
        assertEquals(3, snapshot.rankOf(500));
        assertEquals(1, snapshot.rankOf(10_000));
        assertEquals(4, snapshot.rankOf(1));
        assertTrue(snapshot.place(4).isEmpty());
        assertTrue(top.snapshot() == snapshot);

        // Bob hides from leaderboards: no place, no rank, the others move up; his own total stays known.
        TopSellers.Snapshot hidden = top.refresh(BOB::equals).get();
        assertEquals(List.of(ALICE, CAROL), hidden.top().stream().map(TopSellers.Entry::uuid).toList());
        assertEquals(1, hidden.rankOf(550));
        assertEquals(900, hidden.byUuid().get(BOB));
        assertTrue(top.snapshot() == hidden);
    }
}
