package net.siftvanilla.siftcore.feature.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.core.item.ItemCategory;
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
 * The auction engine against the real ledger, ordered database writer and the real schema (SQLite). Items are plain
 * strings; the claim box is a recording fake that writes real {@code deliveries} rows in the same transaction.
 */
class AuctionEngineTest {

    private static final UUID SELLER = new UUID(10, 1);
    private static final UUID BUYER = new UUID(10, 2);
    private static final UUID OTHER = new UUID(10, 3);
    private static final int TAX = 500;
    private static final Duration DURATION = Duration.ofHours(48);
    private static final AuctionEngine.Codec<String> CODEC = new AuctionEngine.Codec<>() {
        @Override
        public byte[] encode(String item) {
            return item.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (value.startsWith("corrupt")) {
                throw new IllegalArgumentException("unreadable item");
            }
            return value;
        }
    };

    /** A delivery recorded by the fake claim box. */
    record Delivered(UUID owner, String ref, String item) {
    }

    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("auction-test");
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final AtomicLong deliveryIds = new AtomicLong();
    private final Map<Long, Delivered> delivered = new ConcurrentHashMap<>();
    private JdbcDatabase database;
    private Ledger ledger;
    private AuctionEngine<String> engine;

    @BeforeEach
    void setUp() throws Exception {
        this.logger.setLevel(Level.OFF);
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), this.logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, this.logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, this.logger, 1_000_000_000_000_000L);
        this.ledger.load();
        this.engine = newEngine();
        this.engine.load();
        fund(BUYER, 100_000);
        fund(OTHER, 100_000);
    }

    private AuctionEngine<String> newEngine() {
        return new AuctionEngine<>(this.ledger, this.database, CODEC, this::deliver, this.clock::get, this.logger);
    }

    private void deliver(LedgerTx.Builder tx, UUID owner, String ref, String item) {
        long id = this.deliveryIds.incrementAndGet();
        long now = this.clock.get();
        tx.apply(() -> this.delivered.put(id, new Delivered(owner, ref, item)), () -> this.delivered.remove(id));
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO deliveries (id, owner, source, ref, item, created, claimed) VALUES (?, ?, ?, ?, ?, ?, NULL)")) {
                ps.setLong(1, id);
                ps.setString(2, owner.toString());
                ps.setString(3, AuctionEngine.SOURCE);
                ps.setString(4, ref);
                ps.setBytes(5, CODEC.encode(item));
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    private void fund(UUID player, long amount) throws Exception {
        TransactionResult result = this.ledger.execute(LedgerTx.builder().source(player, Currency.MONEY, amount, "test_grant", null).build());
        assertTrue(result.success());
        result.committed().get(10, TimeUnit.SECONDS);
    }

    private long balance(UUID player) {
        return this.ledger.balance(player, Currency.MONEY);
    }

    private Listing<String> list(UUID seller, String item, long price, int limit) throws Exception {
        AuctionEngine.Created<String> created = this.engine.create(
            new AuctionEngine.Draft<>(seller, item, "minecraft:" + item, item, ItemCategory.MISC, 1, price, DURATION), limit, seller.toString());
        assertTrue(created.result().success(), () -> "listing failed: " + created.result());
        created.saved().get(10, TimeUnit.SECONDS);
        return created.listing();
    }

    private Listing<String> list(String item, long price) throws Exception {
        return list(SELLER, item, price, Integer.MAX_VALUE);
    }

    private <T> T sql(String query, Object param, Supplier<T> empty, ResultReader<T> reader) throws Exception {
        this.database.flush();
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                if (param != null) {
                    ps.setObject(1, param);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? reader.read(rs) : empty.get();
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ResultReader<T> {
        T read(ResultSet rs) throws java.sql.SQLException;
    }

    private String state(long id) throws Exception {
        return sql("SELECT state FROM auction_listings WHERE id = ?", id, () -> null, rs -> rs.getString(1));
    }

    private long count(String query, Object param) throws Exception {
        return sql(query, param, () -> 0L, rs -> rs.getLong(1));
    }

    private void assertLedgerHealthy() throws Exception {
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), () -> "ledger problems: " + report.problems());
        assertNull(this.engine.verify().get(10, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------ creating

    @Test
    void createStoresTheListing() throws Exception {
        Listing<String> listing = list("diamond", 1_000);
        assertSame(listing, this.engine.book().get(listing.id()));
        assertTrue(this.engine.book().saved(listing.id()));
        assertEquals("ACTIVE", state(listing.id()));
        assertEquals(this.clock.get() + DURATION.toMillis(), listing.expires());
        assertEquals(1_000L, count("SELECT price FROM auction_listings WHERE id = ?", listing.id()));
        assertLedgerHealthy();
    }

    @Test
    void slotLimitIsEnforcedInsideTheTransaction() throws Exception {
        list(SELLER, "a", 10, 2);
        list(SELLER, "b", 10, 2);
        AuctionEngine.Created<String> third = this.engine.create(
            new AuctionEngine.Draft<>(SELLER, "c", "minecraft:c", "c", ItemCategory.MISC, 1, 10, DURATION), 2, SELLER.toString());
        assertEquals(TransactionStatus.REJECTED, third.result().status());
        assertEquals(Refusal.SLOTS_FULL.id(), third.result().reason());
        assertEquals(2, this.engine.book().count(SELLER));
        assertEquals(2L, count("SELECT COUNT(*) FROM auction_listings WHERE seller = ?", SELLER.toString()));
    }

    @Test
    void anUnsavedListingCannotBeBoughtCancelledOrExpired() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> blocker = this.database.write(c -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        AuctionEngine.Created<String> created = this.engine.create(
            new AuctionEngine.Draft<>(SELLER, "slow", "minecraft:slow", "slow", ItemCategory.MISC, 1, 100, DURATION), 10, SELLER.toString());
        assertTrue(created.result().success());
        long id = created.listing().id();
        assertFalse(this.engine.book().saved(id));
        assertEquals(Refusal.PENDING.id(), this.engine.buy(BUYER, id, 100, TAX, "test").reason());
        assertEquals(Refusal.PENDING.id(), this.engine.cancel(id, SELLER, "test").reason());
        this.clock.addAndGet(DURATION.toMillis());
        assertEquals(Refusal.PENDING.id(), this.engine.expire(id, "test").reason());
        this.clock.addAndGet(-DURATION.toMillis());
        assertTrue(this.engine.book().available(this.clock.get()).isEmpty());
        release.countDown();
        blocker.get(10, TimeUnit.SECONDS);
        created.saved().get(10, TimeUnit.SECONDS);
        assertTrue(this.engine.book().saved(id));
        assertTrue(this.engine.buy(BUYER, id, 100, TAX, "test").success());
    }

    // ------------------------------------------------------------------ buying

    @Test
    void buyMovesMoneyTaxAndItemInOneTransaction() throws Exception {
        Listing<String> listing = list("diamond", 1_000);
        TransactionResult result = this.engine.buy(BUYER, listing.id(), 1_000, TAX, BUYER.toString());
        assertTrue(result.success());
        result.committed().get(10, TimeUnit.SECONDS);
        assertEquals(99_000, balance(BUYER));
        assertEquals(950, balance(SELLER));
        assertNull(this.engine.book().get(listing.id()));
        assertEquals("SOLD", state(listing.id()));
        assertEquals(BUYER.toString(), sql("SELECT buyer FROM auction_listings WHERE id = ?", listing.id(), () -> null, rs -> rs.getString(1)));
        assertEquals(50L, count("SELECT tax FROM auction_listings WHERE id = ?", listing.id()));
        assertEquals(this.clock.get(), count("SELECT closed_at FROM auction_listings WHERE id = ?", listing.id()));
        assertEquals(List.of(new Delivered(BUYER, listing.ref(), "diamond")), List.copyOf(this.delivered.values()));
        assertEquals(1L, count("SELECT COUNT(*) FROM deliveries WHERE ref = ?", listing.ref()));
        assertEquals(-1_000L, count("SELECT SUM(delta) FROM ledger WHERE account = ? AND kind = 'ah_sale'", BUYER.toString()));
        assertEquals(1_000L, count("SELECT SUM(delta) FROM ledger WHERE account = ? AND kind = 'ah_sale'", SELLER.toString()));
        assertEquals(-50L, count("SELECT SUM(delta) FROM ledger WHERE account = ? AND kind = 'ah_tax'", SELLER.toString()));
        assertEquals(3L, count("SELECT COUNT(*) FROM ledger WHERE ref = ?", listing.ref()));
        assertLedgerHealthy();
    }

    @Test
    void cheapSalesPayNoTax() throws Exception {
        Listing<String> listing = list("dirt", 19);
        TransactionResult result = this.engine.buy(BUYER, listing.id(), 19, TAX, BUYER.toString());
        assertTrue(result.success());
        result.committed().get(10, TimeUnit.SECONDS);
        assertEquals(19, balance(SELLER));
        assertEquals(0L, count("SELECT COUNT(*) FROM ledger WHERE kind = 'ah_tax' AND ref = ?", listing.ref()));
        assertLedgerHealthy();
    }

    @Test
    void buyIsRefusedWithoutChangingAnything() throws Exception {
        Listing<String> listing = list("diamond", 1_000);
        assertEquals(Refusal.OWN_LISTING.id(), this.engine.buy(SELLER, listing.id(), 1_000, TAX, "test").reason());
        assertEquals(Refusal.PRICE_CHANGED.id(), this.engine.buy(BUYER, listing.id(), 999, TAX, "test").reason());
        assertEquals(Refusal.GONE.id(), this.engine.buy(BUYER, 9_999, 1_000, TAX, "test").reason());
        UUID poor = new UUID(10, 9);
        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, this.engine.buy(poor, listing.id(), 1_000, TAX, "test").status());
        this.clock.addAndGet(DURATION.toMillis());
        assertEquals(Refusal.EXPIRED.id(), this.engine.buy(BUYER, listing.id(), 1_000, TAX, "test").reason());
        assertSame(listing, this.engine.book().get(listing.id()));
        assertEquals(100_000, balance(BUYER));
        assertEquals(0, balance(SELLER));
        assertTrue(this.delivered.isEmpty());
        assertEquals("ACTIVE", state(listing.id()));
        assertLedgerHealthy();
    }

    @Test
    void sellerAtTheBalanceLimitBlocksTheSale() throws Exception {
        this.ledger.maxBalance(500);
        Listing<String> listing = list("diamond", 1_000);
        assertEquals(TransactionStatus.BALANCE_LIMIT, this.engine.buy(BUYER, listing.id(), 1_000, TAX, "test").status());
        assertSame(listing, this.engine.book().get(listing.id()));
    }

    // ------------------------------------------------------------------ cancelling and expiring

    @Test
    void sellerCancelReturnsTheItem() throws Exception {
        Listing<String> listing = list("diamond", 1_000);
        assertEquals(Refusal.NOT_OWNER.id(), this.engine.cancel(listing.id(), BUYER, "test").reason());
        TransactionResult result = this.engine.cancel(listing.id(), SELLER, SELLER.toString());
        assertTrue(result.success());
        result.committed().get(10, TimeUnit.SECONDS);
        assertEquals("CANCELLED", state(listing.id()));
        assertEquals(List.of(new Delivered(SELLER, listing.ref(), "diamond")), List.copyOf(this.delivered.values()));
        assertEquals(Refusal.GONE.id(), this.engine.cancel(listing.id(), SELLER, "test").reason());
        assertEquals(0, this.engine.book().count(SELLER));
        assertLedgerHealthy();
    }

    @Test
    void staffCanRemoveAnyListing() throws Exception {
        Listing<String> listing = list("diamond", 1_000);
        TransactionResult result = this.engine.cancel(listing.id(), null, "console");
        assertTrue(result.success());
        result.committed().get(10, TimeUnit.SECONDS);
        assertEquals("CANCELLED", state(listing.id()));
        assertEquals(SELLER, this.delivered.values().iterator().next().owner());
    }

    @Test
    void expiryReturnsOnlyListingsWhoseTimeRanOut() throws Exception {
        Listing<String> early = list("early", 10);
        this.clock.addAndGet(Duration.ofHours(1).toMillis());
        Listing<String> late = list("late", 10);
        assertTrue(this.engine.expireDue("system").isEmpty());
        assertEquals(Refusal.NOT_EXPIRED.id(), this.engine.expire(early.id(), "system").reason());
        this.clock.set(early.expires());
        List<AuctionEngine.Closed<String>> closed = this.engine.expireDue("system");
        assertEquals(1, closed.size());
        assertSame(early, closed.getFirst().listing());
        assertTrue(closed.getFirst().result().success());
        closed.getFirst().result().committed().get(10, TimeUnit.SECONDS);
        assertEquals("EXPIRED", state(early.id()));
        assertEquals("ACTIVE", state(late.id()));
        assertEquals(List.of(new Delivered(SELLER, early.ref(), "early")), List.copyOf(this.delivered.values()));
        assertLedgerHealthy();
    }

    // ------------------------------------------------------------------ races

    @Test
    void concurrentBuyCancelAndExpireHaveExactlyOneWinner() throws Exception {
        int rounds = 120;
        List<Listing<String>> listings = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            listings.add(list("item" + i, 100));
        }
        long buyersBefore = balance(BUYER) + balance(OTHER);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        int sold = 0;
        try {
            for (int i = 0; i < rounds; i++) {
                Listing<String> listing = listings.get(i);
                // Half the rounds race before the listing expires (buyers and the seller can win), half after
                // (only the seller and the expiry can win). Either way exactly one transaction may succeed.
                this.clock.set(i % 2 == 0 ? listing.expires() - 1 : listing.expires());
                CyclicBarrier barrier = new CyclicBarrier(5);
                List<Future<TransactionResult>> attempts = List.of(
                    pool.submit(() -> race(barrier, () -> this.engine.buy(BUYER, listing.id(), 100, TAX, "a"))),
                    pool.submit(() -> race(barrier, () -> this.engine.buy(OTHER, listing.id(), 100, TAX, "b"))),
                    pool.submit(() -> race(barrier, () -> this.engine.cancel(listing.id(), SELLER, "c"))),
                    pool.submit(() -> race(barrier, () -> this.engine.expire(listing.id(), "d"))),
                    pool.submit(() -> race(barrier, () -> this.engine.buy(BUYER, listing.id(), 100, TAX, "e"))));
                int winners = 0;
                for (Future<TransactionResult> attempt : attempts) {
                    TransactionResult result = attempt.get(10, TimeUnit.SECONDS);
                    if (result.success()) {
                        winners++;
                        result.committed().get(10, TimeUnit.SECONDS);
                    } else {
                        assertNotNull(Refusal.from(result.reason()), () -> "unexpected failure " + result);
                    }
                }
                assertEquals(1, winners, "round " + i);
                assertNull(this.engine.book().get(listing.id()));
                String state = state(listing.id());
                assertTrue(List.of("SOLD", "CANCELLED", "EXPIRED").contains(state), state);
                if (state.equals("SOLD")) {
                    sold++;
                }
                assertEquals(1L, count("SELECT COUNT(*) FROM deliveries WHERE ref = ?", listing.ref()), "round " + i);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(rounds, this.delivered.size());
        assertEquals(0, this.engine.book().size());
        assertEquals(buyersBefore - sold * 100L, balance(BUYER) + balance(OTHER));
        assertEquals(sold * 95L, balance(SELLER));
        assertEquals(sold, count("SELECT COUNT(*) FROM auction_listings WHERE state = ?", "SOLD"));
        assertLedgerHealthy();
    }

    private static TransactionResult race(CyclicBarrier barrier, Supplier<TransactionResult> action) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return action.get();
    }

    // ------------------------------------------------------------------ crash safety

    @Test
    void aSaleThatCannotBeStoredIsRevertedCompletely() throws Exception {
        Listing<String> listing = list("diamond", 1_000);
        // Storage disagrees with memory: the row is no longer active. The UPDATE guard must refuse the second close.
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE auction_listings SET state = 'CANCELLED' WHERE id = ?")) {
                ps.setLong(1, listing.id());
                ps.executeUpdate();
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        TransactionResult result = this.engine.buy(BUYER, listing.id(), 1_000, TAX, "test");
        assertTrue(result.success(), "memory still thinks the listing is active");
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.committed().get(10, TimeUnit.SECONDS));
        assertNotNull(failure.getCause());
        this.database.flush();
        assertEquals(100_000, balance(BUYER), "the buyer's money is back");
        assertEquals(0, balance(SELLER));
        assertSame(listing, this.engine.book().get(listing.id()), "the listing is back in memory");
        assertTrue(this.delivered.isEmpty(), "no item was delivered");
        assertEquals(0L, count("SELECT COUNT(*) FROM deliveries WHERE ref = ?", listing.ref()));
        assertEquals(0L, count("SELECT COUNT(*) FROM ledger WHERE ref = ?", listing.ref()));
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), () -> "ledger problems: " + report.problems());
        String mismatch = this.engine.verify().get(10, TimeUnit.SECONDS);
        assertNotNull(mismatch, "verify notices the listing that is not active in storage");
        assertTrue(mismatch.contains(Long.toString(listing.id())), mismatch);
    }

    @Test
    void aListingThatCannotBeStoredDisappears() throws Exception {
        Listing<String> existing = list("diamond", 1_000);
        // A row that already holds the next id makes the INSERT fail inside the writer: the new listing must vanish
        // from memory again and the caller learns it through the failed future.
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO auction_listings (id, seller, item, item_type, search_name, "
                + "category, amount, price, created, expires, state, tax) VALUES (?, ?, ?, 'x', 'x', 'misc', 1, 1, 1, 2, 'SOLD', 0)")) {
                ps.setLong(1, existing.id() + 1);
                ps.setString(2, OTHER.toString());
                ps.setBytes(3, new byte[] {1});
                ps.executeUpdate();
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        AuctionEngine.Created<String> created = this.engine.create(
            new AuctionEngine.Draft<>(SELLER, "clash", "minecraft:clash", "clash", ItemCategory.MISC, 1, 10, DURATION), 10, SELLER.toString());
        assertTrue(created.result().success());
        assertEquals(existing.id() + 1, created.listing().id());
        assertThrows(ExecutionException.class, () -> created.saved().get(10, TimeUnit.SECONDS));
        this.database.flush();
        assertNull(this.engine.book().get(created.listing().id()));
        assertEquals(0, this.engine.book().unsavedCount());
        assertEquals(1, this.engine.book().count(SELLER), "only the stored listing holds a slot");
        assertSame(existing, this.engine.book().get(existing.id()));
    }

    // ------------------------------------------------------------------ startup and reads

    @Test
    void restartLoadsActiveListingsOnly() throws Exception {
        Listing<String> kept = list("kept", 10);
        Listing<String> sold = list("sold", 10);
        this.engine.buy(BUYER, sold.id(), 10, TAX, "test").committed().get(10, TimeUnit.SECONDS);
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO auction_listings (id, seller, item, item_type, search_name, "
                + "category, amount, price, created, expires, state, tax) VALUES (?, ?, ?, 'minecraft:x', 'x', 'weird', 1, 5, 1, 2, 'ACTIVE', 0)")) {
                ps.setLong(1, 900);
                ps.setString(2, OTHER.toString());
                ps.setBytes(3, "corrupt".getBytes(StandardCharsets.UTF_8));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO auction_listings (id, seller, item, item_type, search_name, "
                + "category, amount, price, created, expires, state, tax) VALUES (?, ?, ?, 'minecraft:y', 'y', 'weird', 2, 7, 1, 2, 'ACTIVE', 0)")) {
                ps.setLong(1, 901);
                ps.setString(2, OTHER.toString());
                ps.setBytes(3, "fine".getBytes(StandardCharsets.UTF_8));
                ps.executeUpdate();
            }
            return null;
        }).get(10, TimeUnit.SECONDS);

        AuctionEngine<String> restarted = newEngine();
        AuctionEngine.LoadResult result = restarted.load();
        assertEquals(2, result.loaded());
        assertEquals(1, result.unreadable());
        assertEquals("kept", restarted.book().get(kept.id()).item());
        assertTrue(restarted.book().saved(kept.id()));
        assertNull(restarted.book().get(sold.id()));
        assertEquals(ItemCategory.MISC, restarted.book().get(901).category(), "unknown categories fall back to misc");
        assertNull(restarted.verify().get(10, TimeUnit.SECONDS), "the unreadable row is accounted for");
        AuctionEngine.Created<String> next = restarted.create(
            new AuctionEngine.Draft<>(SELLER, "next", "minecraft:next", "next", ItemCategory.MISC, 1, 10, DURATION), 10, SELLER.toString());
        assertEquals(902, next.listing().id(), "ids continue after the highest stored id");
    }

    @Test
    void historyShowsSalesAndPurchasesNewestFirst() throws Exception {
        Listing<String> first = list(SELLER, "first", 100, 10);
        Listing<String> second = list(SELLER, "second", 200, 10);
        Listing<String> bought = list(OTHER, "bought", 300, 10);
        Listing<String> cancelled = list(SELLER, "cancelled", 400, 10);
        fund(SELLER, 1_000);
        this.clock.addAndGet(1_000);
        this.engine.buy(BUYER, first.id(), 100, TAX, "t").committed().get(10, TimeUnit.SECONDS);
        this.clock.addAndGet(1_000);
        this.engine.buy(OTHER, second.id(), 200, TAX, "t").committed().get(10, TimeUnit.SECONDS);
        this.clock.addAndGet(1_000);
        this.engine.buy(SELLER, bought.id(), 300, TAX, "t").committed().get(10, TimeUnit.SECONDS);
        this.engine.cancel(cancelled.id(), SELLER, "t").committed().get(10, TimeUnit.SECONDS);

        List<AuctionEngine.HistoryEntry<String>> history = this.engine.history(SELLER, 20).get(10, TimeUnit.SECONDS);
        assertEquals(List.of("bought", "second", "first"), history.stream().map(AuctionEngine.HistoryEntry::item).toList());
        assertFalse(history.get(0).sale());
        assertEquals(OTHER, history.get(0).counterparty());
        assertTrue(history.get(1).sale());
        assertEquals(OTHER, history.get(1).counterparty());
        assertEquals(10L, history.get(1).tax());
        assertEquals(BUYER, history.get(2).counterparty());
        assertEquals(2, this.engine.history(SELLER, 2).get(10, TimeUnit.SECONDS).size());
        assertTrue(this.engine.history(new UUID(9, 9), 20).get(10, TimeUnit.SECONDS).isEmpty());
        assertLedgerHealthy();
    }

    /**
     * The join summary's read: only the seller's sales after the moment they left and up to the moment they joined
     * (later sales were told live), their earnings after tax, the newest few.
     */
    @Test
    void salesSinceCountsOnlySalesBetweenLeavingAndJoining() throws Exception {
        Listing<String> old = list(SELLER, "old", 100, 10);
        Listing<String> second = list(SELLER, "second", 200, 10);
        Listing<String> third = list(SELLER, "third", 300, 10);
        Listing<String> corrupt = list(SELLER, "corrupt-item", 1_000, 10);
        Listing<String> bought = list(OTHER, "bought", 50, 10);
        fund(SELLER, 1_000);
        this.clock.addAndGet(1_000);
        this.engine.buy(BUYER, old.id(), 100, TAX, "t").committed().get(10, TimeUnit.SECONDS);
        long since = this.clock.get();
        this.clock.addAndGet(1_000);
        this.engine.buy(OTHER, second.id(), 200, TAX, "t").committed().get(10, TimeUnit.SECONDS);
        this.clock.addAndGet(1_000);
        this.engine.buy(BUYER, corrupt.id(), 1_000, TAX, "t").committed().get(10, TimeUnit.SECONDS);
        this.clock.addAndGet(1_000);
        this.engine.buy(BUYER, third.id(), 300, TAX, "t").committed().get(10, TimeUnit.SECONDS);
        this.engine.buy(SELLER, bought.id(), 50, TAX, "t").committed().get(10, TimeUnit.SECONDS);
        long joined = this.clock.get();
        // After the seller joined: told live, so the join summary leaves it out.
        Listing<String> live = list(SELLER, "live", 400, 10);
        this.clock.addAndGet(1_000);
        this.engine.buy(BUYER, live.id(), 400, TAX, "t").committed().get(10, TimeUnit.SECONDS);

        AuctionEngine.SalesSince<String> one = this.engine.salesSince(SELLER, since, joined, 1).get(10, TimeUnit.SECONDS);
        assertEquals(3, one.count(), "the sale at the moment itself, the sale after the join and the purchase don't count");
        assertEquals((200 - 10) + (1_000 - 50) + (300 - 15), one.earned(), "what the seller got after tax");
        assertEquals(10 + 50 + 15, one.taxed(), "the tax taken from those sales");
        assertEquals(List.of("third"), one.latest().stream().map(AuctionEngine.HistoryEntry::item).toList());
        assertEquals(BUYER, one.latest().getFirst().counterparty());
        assertTrue(one.latest().getFirst().sale());

        AuctionEngine.SalesSince<String> all = this.engine.salesSince(SELLER, since, joined, 5).get(10, TimeUnit.SECONDS);
        assertEquals(List.of("third", "second"), all.latest().stream().map(AuctionEngine.HistoryEntry::item).toList(),
            "newest first; an unreadable item is counted but not listed");
        assertEquals(3, all.count());

        AuctionEngine.SalesSince<String> later = this.engine.salesSince(SELLER, since, this.clock.get(), 5).get(10, TimeUnit.SECONDS);
        assertEquals(List.of("live", "third", "second"), later.latest().stream().map(AuctionEngine.HistoryEntry::item).toList(),
            "with the bound at now the later sale counts too");
        assertEquals(4, later.count());

        AuctionEngine.SalesSince<String> none = this.engine.salesSince(SELLER, this.clock.get(), this.clock.get(), 5).get(10, TimeUnit.SECONDS);
        assertEquals(0, none.count());
        assertEquals(0, none.earned());
        assertEquals(0, none.taxed());
        assertTrue(none.latest().isEmpty());
        assertEquals(0, this.engine.salesSince(BUYER, 0, this.clock.get(), 5).get(10, TimeUnit.SECONDS).count(), "buyers sold nothing");
        assertLedgerHealthy();
    }
}
