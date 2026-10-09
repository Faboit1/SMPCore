package net.siftvanilla.siftcore.feature.orders;

import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.BUYER;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.DAY;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.OTHER;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.SELLER;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.TAX;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.committed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The order state machine and its money against the real ledger and schema: escrow on create, partial and final
 * fills, refunds on cancel and expiry, collecting, collect-all, edits, extensions and warnings, and races.
 */
class OrderEngineTest {

    @TempDir
    Path dir;

    private OrdersTestSupport t;

    @BeforeEach
    void setUp() throws Exception {
        this.t = new OrdersTestSupport(this.dir);
        this.t.fund(BUYER, 1_000_000);
        this.t.fund(OTHER, 1_000_000);
    }

    @AfterEach
    void tearDown() {
        this.t.close();
    }

    private static void refused(TransactionResult result, Refusal refusal) {
        assertEquals(TransactionStatus.REJECTED, result.status(), () -> "expected " + refusal + ", got " + result);
        assertEquals(refusal, Refusal.from(result.reason()));
    }

    private TransactionResult fill(UUID seller, Order order, int units) {
        return this.t.engine.fill(seller, order.id(), units, order.priceEach(), TAX, FillSource.MENU, seller.toString());
    }

    private Order now(Order order) {
        return this.t.book.get(order.id());
    }

    // ------------------------------------------------------------------ placing

    @Test
    void placingHoldsTheWholeOrder() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 64, 100);
        assertEquals(1_000_000 - 6_400, this.t.balance(BUYER));
        assertEquals(6_400, this.t.escrow());
        assertEquals(OrderState.ACTIVE, now(order).state());
        assertEquals("ACTIVE", this.t.sql("SELECT state FROM orders WHERE id = ?", rs -> rs.getString(1), order.id()));
        assertNull(this.t.sql("SELECT variant FROM orders WHERE id = ?", rs -> rs.getString(1), order.id()));
        assertEquals(6_400L, this.t.number("SELECT -SUM(delta) FROM ledger WHERE ref = ? AND kind = 'order_escrow' AND account = ?",
            order.ref(), BUYER.toString()));
        this.t.assertHealthy();
    }

    @Test
    void variantOrdersKeepTheirVariant() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:enchanted_book|enchant:minecraft:mending:1", 3, 2_000);
        assertEquals("enchant:minecraft:mending:1", this.t.sql("SELECT variant FROM orders WHERE id = ?", rs -> rs.getString(1), order.id()));
        assertEquals("minecraft:enchanted_book|enchant:minecraft:mending:1", now(order).key());
        assertEquals(List.of(now(order)), this.t.book.bids("minecraft:enchanted_book|enchant:minecraft:mending:1"));
        assertTrue(this.t.book.bids("minecraft:enchanted_book").isEmpty());
    }

    @Test
    void theActiveLimitIsCheckedInsideTheTransaction() throws Exception {
        OrderEngine.Draft draft = new OrderEngine.Draft(BUYER, "minecraft:stone", null, 10, 5, DAY);
        committed(this.t.engine.create(draft, 2, "test").result());
        committed(this.t.engine.create(draft, 2, "test").result());
        refused(this.t.engine.create(draft, 2, "test").result(), Refusal.LIMIT);
        assertEquals(2, this.t.book.activeCount(BUYER));
        assertEquals(100, this.t.escrow());
        this.t.assertHealthy();
    }

    @Test
    void placingWithoutTheMoneyChangesNothing() throws Exception {
        OrderEngine.Created created = this.t.engine.create(new OrderEngine.Draft(SELLER, "minecraft:stone", null, 10, 5, DAY), 3, "test");
        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, created.result().status());
        assertEquals(0, this.t.book.size());
        assertEquals(0, this.t.escrow());
        assertEquals(0, this.t.number("SELECT COUNT(*) FROM orders"));
    }

    // ------------------------------------------------------------------ delivering

    @Test
    void partialFillsPayFromTheEscrowAndTheSellerPaysTax() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 64, 100);
        committed(fill(SELLER, order, 10));
        assertEquals(980, this.t.balance(SELLER), "1,000 paid, 2% tax");
        assertEquals(5_400, this.t.escrow());
        Order after = now(order);
        assertEquals(10, after.filled());
        assertEquals(54, after.remaining());
        assertEquals(10, after.waiting());
        assertEquals(OrderState.ACTIVE, after.state());
        assertEquals("10/1000/20/menu", this.t.sql("SELECT quantity, paid, tax, source FROM order_fills WHERE order_id = ?",
            rs -> rs.getInt(1) + "/" + rs.getLong(2) + "/" + rs.getLong(3) + "/" + rs.getString(4), order.id()));
        assertEquals(20L, this.t.number("SELECT -SUM(delta) FROM ledger WHERE kind = 'order_tax' AND ref = ?", order.ref()));
        this.t.assertHealthy();
    }

    @Test
    void theLastFillCompletesTheOrder() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 20, 50);
        committed(fill(SELLER, order, 15));
        this.t.clock.addAndGet(1_000);
        committed(fill(OTHER, order, 5));
        Order after = now(order);
        assertEquals(OrderState.FILLED, after.state());
        assertEquals(0, after.escrow());
        assertEquals(this.t.clock.get(), after.ended());
        assertEquals(0, this.t.escrow());
        assertEquals("FILLED", this.t.sql("SELECT state FROM orders WHERE id = ?", rs -> rs.getString(1), order.id()));
        assertEquals(this.t.clock.get(), this.t.number("SELECT ended FROM orders WHERE id = ?", order.id()));
        refused(fill(SELLER, order, 1), Refusal.NOT_ACTIVE);
        assertTrue(this.t.book.bids(order.key()).isEmpty(), "a complete order leaves the bid index");
        this.t.assertHealthy();
    }

    @Test
    void fillsNeverExceedTheQuantity() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:iron_ingot", 30, 10);
        committed(fill(SELLER, order, 25));
        refused(fill(SELLER, order, 6), Refusal.NOT_ENOUGH_LEFT);
        assertEquals(25, now(order).filled());
        assertEquals(50, this.t.escrow());
        this.t.assertHealthy();
    }

    @Test
    void deliveriesAreRefusedWhenAnythingChanged() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 64, 100);
        refused(fill(BUYER, order, 1), Refusal.OWN_ORDER);
        refused(this.t.engine.fill(SELLER, order.id(), 1, 99, TAX, FillSource.MENU, "test"), Refusal.PRICE_CHANGED);
        refused(this.t.engine.fill(SELLER, 999_999, 1, 100, TAX, FillSource.MENU, "test"), Refusal.GONE);
        this.t.clock.addAndGet(8 * DAY);
        refused(fill(SELLER, order, 1), Refusal.EXPIRED);
        assertEquals(0, now(order).filled());
        assertEquals(0, this.t.balance(SELLER));
        this.t.assertHealthy();
    }

    @Test
    void ruleRefusalsComeFromTheRules() throws Exception {
        try (OrdersTestSupport guarded = new OrdersTestSupport(this.dir.resolve("guarded"), new OrderEngine.Rules() {
            @Override
            public boolean related(UUID owner, UUID seller) {
                return seller.equals(OTHER);
            }

            @Override
            public boolean deliverable(Order order) {
                return !order.itemType().equals("minecraft:gone");
            }
        })) {
            guarded.fund(BUYER, 10_000);
            Order order = guarded.place(BUYER, "minecraft:diamond", 10, 10);
            Order unknown = guarded.place(BUYER, "minecraft:gone", 10, 10);
            // Both refuse with reasons a routed sale knows (it retries with fresh bids, which leave both orders out).
            TransactionResult related = guarded.engine.fill(OTHER, order.id(), 1, 10, TAX, FillSource.QUICK, "test");
            refused(related, Refusal.OWN_ORDER);
            assertEquals(OrderMarket.Refusal.OWN_ORDER, OrderMarket.Refusal.of(related.reason()), "a shared address counts as your own");
            TransactionResult unavailable = guarded.engine.fill(SELLER, unknown.id(), 1, 10, TAX, FillSource.QUICK, "test");
            refused(unavailable, Refusal.NOT_ACTIVE);
            assertEquals(OrderMarket.Refusal.NOT_ACTIVE, OrderMarket.Refusal.of(unavailable.reason()), "an unknown item takes nothing");
            committed(guarded.engine.fill(SELLER, order.id(), 1, 10, TAX, FillSource.QUICK, "test"));
            assertEquals("quick", guarded.sql("SELECT source FROM order_fills WHERE order_id = ?", rs -> rs.getString(1), order.id()));
        }
    }

    @Test
    void concurrentFillsCannotOverfill() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:emerald", 50, 7);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransactionResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID seller = new UUID(30, i);
            results.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return this.t.engine.fill(seller, order.id(), 10, 7, TAX, FillSource.MENU, seller.toString());
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<TransactionResult> result : results) {
            TransactionResult done = result.get(10, TimeUnit.SECONDS);
            if (done.success()) {
                succeeded++;
                done.committed().get(10, TimeUnit.SECONDS);
            } else {
                assertTrue(Refusal.from(done.reason()) == Refusal.NOT_ENOUGH_LEFT || Refusal.from(done.reason()) == Refusal.NOT_ACTIVE,
                    "losers are refused for the count: " + done);
            }
        }
        pool.shutdown();
        assertEquals(5, succeeded);
        assertEquals(50, now(order).filled());
        assertEquals(OrderState.FILLED, now(order).state());
        assertEquals(5L, this.t.number("SELECT COUNT(*) FROM order_fills WHERE order_id = ?", order.id()));
        assertEquals(0, this.t.escrow());
        this.t.assertHealthy();
    }

    // ------------------------------------------------------------------ ending

    @Test
    void cancelRefundsExactlyAndTwiceIsHarmless() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 64, 100);
        committed(fill(SELLER, order, 14));
        long before = this.t.balance(BUYER);
        OrderEngine.Ended ended = this.t.engine.cancel(order.id(), BUYER, BUYER.toString());
        committed(ended.result());
        assertEquals(5_000, ended.refund());
        assertEquals(before + 5_000, this.t.balance(BUYER));
        assertEquals(0, this.t.escrow());
        Order after = now(order);
        assertEquals(OrderState.CANCELLED, after.state());
        assertEquals(5_000, after.refunded());
        assertEquals(14, after.waiting(), "delivered items stay collectable");
        assertEquals(5_000L, this.t.number("SELECT refunded FROM orders WHERE id = ?", order.id()));
        refused(this.t.engine.cancel(order.id(), BUYER, "test").result(), Refusal.NOT_ACTIVE);
        assertEquals(before + 5_000, this.t.balance(BUYER), "a second cancel pays nothing");
        refused(fill(SELLER, order, 1), Refusal.NOT_ACTIVE);
        this.t.assertHealthy();
    }

    @Test
    void onlyTheOwnerCancelsUnlessStaff() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:stone", 10, 10);
        refused(this.t.engine.cancel(order.id(), OTHER, "test").result(), Refusal.NOT_OWNER);
        committed(this.t.engine.cancel(order.id(), null, "console").result());
        assertEquals(OrderState.CANCELLED, now(order).state());
        this.t.assertHealthy();
    }

    @Test
    void expiryRefundsWhatIsLeft() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:stone", 10, 10);
        committed(fill(SELLER, order, 4));
        refused(this.t.engine.expire(order.id()).result(), Refusal.NOT_EXPIRED);
        assertTrue(this.t.engine.expireDue().isEmpty());
        this.t.clock.addAndGet(7 * DAY);
        List<OrderEngine.Ended> ended = this.t.engine.expireDue();
        assertEquals(1, ended.size());
        committed(ended.getFirst().result());
        assertEquals(60, ended.getFirst().refund());
        assertEquals(OrderState.EXPIRED, now(order).state());
        assertEquals(this.t.clock.get(), now(order).ended());
        assertEquals(1_000_000 - 100 + 60, this.t.balance(BUYER));
        assertTrue(this.t.engine.expireDue().isEmpty(), "an ended order is not expired twice");
        this.t.assertHealthy();
    }

    // ------------------------------------------------------------------ collecting

    @Test
    void collectingCountsAndPuttingBack() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 64, 100);
        committed(fill(SELLER, order, 10));
        committed(this.t.engine.collect(BUYER, order.id(), 6, "test", null));
        assertEquals(4, now(order).waiting());
        refused(this.t.engine.collect(BUYER, order.id(), 5, "test", null), Refusal.NOTHING_WAITING);
        refused(this.t.engine.collect(OTHER, order.id(), 1, "test", null), Refusal.NOT_OWNER);
        committed(this.t.engine.uncollect(BUYER, order.id(), 2, "test"));
        assertEquals(6, now(order).waiting());
        assertEquals(4L, this.t.number("SELECT collected FROM orders WHERE id = ?", order.id()));
        this.t.assertHealthy();
    }

    @Test
    void anOrderClosesWhenEndedAndCollected() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 5, 100);
        committed(fill(SELLER, order, 5));
        assertFalse(now(order).closed(), "delivered items still wait");
        assertEquals(0, this.t.engine.prune(id -> false));
        committed(this.t.engine.collect(BUYER, order.id(), 5, "test", null));
        assertTrue(now(order).closed());
        assertEquals(0, this.t.engine.prune(id -> id == order.id()), "kept while a handover holds it");
        assertEquals(1, this.t.engine.prune(id -> false));
        assertNull(this.t.book.get(order.id()));
        this.t.assertHealthy();
    }

    @Test
    void collectAllChangesEveryOrderInOneTransaction() throws Exception {
        Order first = this.t.place(BUYER, "minecraft:diamond", 10, 100);
        Order second = this.t.place(BUYER, "minecraft:stone", 100, 1);
        committed(fill(SELLER, first, 10));
        committed(fill(SELLER, second, 40));
        committed(this.t.engine.collectAll(BUYER, List.of(new OrderEngine.Collect(second.id(), 40),
            new OrderEngine.Collect(first.id(), 3)), "test"));
        assertEquals(0, now(second).waiting());
        assertEquals(7, now(first).waiting());
        // One part no longer valid refuses the whole collect.
        refused(this.t.engine.collectAll(BUYER, List.of(new OrderEngine.Collect(first.id(), 7),
            new OrderEngine.Collect(second.id(), 1)), "test"), Refusal.NOTHING_WAITING);
        assertEquals(7, now(first).waiting(), "nothing of a refused collect-all applied");
        assertThrows(IllegalArgumentException.class, () -> this.t.engine.collectAll(BUYER,
            List.of(new OrderEngine.Collect(first.id(), 1), new OrderEngine.Collect(first.id(), 1)), "test"));
        this.t.assertHealthy();
    }

    @Test
    void collectCanAddToTheSameTransaction() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 10, 100);
        committed(fill(SELLER, order, 10));
        List<String> extra = new ArrayList<>();
        committed(this.t.engine.collect(BUYER, order.id(), 10, "test", tx -> tx.apply(() -> extra.add("claim box"), () -> extra.clear())));
        assertEquals(List.of("claim box"), extra);
        assertEquals(0, now(order).waiting());
    }

    // ------------------------------------------------------------------ changing

    @Test
    void editingHoldsExactlyTheExtra() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 100, 10);
        committed(fill(SELLER, order, 20));
        long before = this.t.balance(BUYER);
        OrderEngine.Seen seen = OrderEngine.Seen.of(now(order));
        TransactionResult result = this.t.engine.edit(BUYER, order.id(), seen, 12, 150,
            new OrderEngine.EditLimits(1, 100_000, 1_000_000), BUYER.toString());
        committed(result);
        long extra = (150 - 20) * 12L - (100 - 20) * 10L;
        assertEquals(760, extra);
        assertEquals(before - extra, this.t.balance(BUYER));
        Order after = now(order);
        assertEquals(12, after.priceEach());
        assertEquals(150, after.quantity());
        assertEquals(130 * 12, after.escrow());
        assertEquals(130 * 12L, this.t.escrow());
        assertEquals("12/150/1560", this.t.sql("SELECT price_each, quantity, escrow FROM orders WHERE id = ?",
            rs -> rs.getLong(1) + "/" + rs.getInt(2) + "/" + rs.getLong(3), order.id()));
        // A delivery menu opened at the old price is refused.
        refused(this.t.engine.fill(SELLER, order.id(), 1, 10, TAX, FillSource.MENU, "test"), Refusal.PRICE_CHANGED);
        committed(this.t.engine.fill(SELLER, order.id(), 1, 12, TAX, FillSource.MENU, "test"));
        this.t.assertHealthy();
    }

    @Test
    void editsAreRefusedWhenTheOrderMoved() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:diamond", 100, 10);
        OrderEngine.Seen seen = OrderEngine.Seen.of(order);
        OrderEngine.EditLimits limits = new OrderEngine.EditLimits(1, 1_000, 1_000_000);
        committed(fill(SELLER, order, 1));
        refused(this.t.engine.edit(BUYER, order.id(), seen, 11, 100, limits, "test"), Refusal.CHANGED);
        OrderEngine.Seen fresh = OrderEngine.Seen.of(now(order));
        refused(this.t.engine.edit(OTHER, order.id(), fresh, 11, 100, limits, "test"), Refusal.NOT_OWNER);
        refused(this.t.engine.edit(BUYER, order.id(), fresh, 11, 2_000, limits, "test"), Refusal.OUT_OF_LIMITS);
        refused(this.t.engine.edit(BUYER, order.id(), fresh, 100_000, 100, new OrderEngine.EditLimits(1, 1_000, 100_000), "test"),
            Refusal.OUT_OF_LIMITS);
        assertThrows(IllegalArgumentException.class, () -> this.t.engine.edit(BUYER, order.id(), fresh, 9, 100, limits, "test"),
            "prices only go up");
        assertThrows(IllegalArgumentException.class, () -> this.t.engine.edit(BUYER, order.id(), fresh, 10, 100, limits, "test"),
            "an edit must change something");
        committed(this.t.engine.cancel(order.id(), BUYER, "test").result());
        refused(this.t.engine.edit(BUYER, order.id(), OrderEngine.Seen.of(now(order)), 11, 100, limits, "test"), Refusal.NOT_ACTIVE);
        assertEquals(10, now(order).priceEach());
        this.t.assertHealthy();
    }

    @Test
    void editingWithoutTheMoneyChangesNothing() throws Exception {
        this.t.fund(SELLER, 1_000);
        Order order = this.t.place(SELLER, "minecraft:stone", 100, 10);
        TransactionResult result = this.t.engine.edit(SELLER, order.id(), OrderEngine.Seen.of(order), 20, 100,
            new OrderEngine.EditLimits(1, 1_000, 1_000_000), "test");
        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, result.status());
        assertEquals(10, now(order).priceEach());
        this.t.assertHealthy();
    }

    @Test
    void extendingMovesTheEndOnly() throws Exception {
        Order order = this.t.place(BUYER, "minecraft:stone", 10, 10);
        committed(this.t.engine.warn(order.id()));
        assertTrue(now(order).warned());
        refused(this.t.engine.warn(order.id()), Refusal.ALREADY_WARNED);
        long end = order.expires() + 3 * DAY;
        committed(this.t.engine.extend(BUYER, order.id(), order.expires(), end, "test"));
        assertEquals(end, now(order).expires());
        assertFalse(now(order).warned(), "the owner is warned again before the new end");
        assertEquals(end, this.t.number("SELECT expires FROM orders WHERE id = ?", order.id()));
        assertEquals(0L, this.t.number("SELECT warned FROM orders WHERE id = ?", order.id()));
        refused(this.t.engine.extend(BUYER, order.id(), order.expires(), end + DAY, "test"), Refusal.CHANGED);
        refused(this.t.engine.extend(OTHER, order.id(), end, end + DAY, "test"), Refusal.NOT_OWNER);
        assertEquals(100, this.t.escrow(), "extending is free");
        this.t.assertHealthy();
    }

    // ------------------------------------------------------------------ storage

    @Test
    void storageLoadsTheSameBook() throws Exception {
        Order active = this.t.place(BUYER, "minecraft:diamond", 10, 100);
        Order ended = this.t.place(BUYER, "minecraft:stone", 10, 1);
        Order closed = this.t.place(BUYER, "minecraft:dirt", 1, 1);
        committed(fill(SELLER, active, 3));
        committed(fill(SELLER, ended, 2));
        committed(this.t.engine.cancel(ended.id(), BUYER, "test").result());
        committed(fill(SELLER, closed, 1));
        committed(this.t.engine.collect(BUYER, closed.id(), 1, "test", null));
        this.t.database.flush();
        OrderStore store = new OrderStore(this.t.database);
        OrderStore.Loaded loaded = store.loadOpen().get(10, TimeUnit.SECONDS);
        assertEquals(List.of(), loaded.unreadable());
        assertEquals(List.of(now(active), now(ended)), loaded.orders(), "closed orders are not loaded");
        List<OrderStore.Past> history = store.history(BUYER, 10).get(10, TimeUnit.SECONDS);
        assertEquals(2, history.size());
        assertEquals(List.of(closed.id(), ended.id()).stream().sorted().toList(),
            history.stream().map(past -> past.order().id()).sorted().toList());
        assertEquals(200L, history.stream().filter(past -> past.order().id() == ended.id()).findFirst().orElseThrow().paidOut() * 100);
        List<OrderStore.Delivery> deliveries = store.deliveries(SELLER, 10).get(10, TimeUnit.SECONDS);
        assertEquals(3, deliveries.size());
        OrderStore.DeliveryTotals totals = store.deliveryTotals(SELLER).get(10, TimeUnit.SECONDS);
        assertEquals(3, totals.count());
        assertEquals(300 - 6 + 2 + 1, totals.earned());
        assertEquals(3, store.popularity(0).get(10, TimeUnit.SECONDS).size());
    }

    // ------------------------------------------------------------------ notices

    private static OrderStore.NoticeRow row(long order, String kind, int units, long amount) {
        return new OrderStore.NoticeRow(order, kind, units, amount, kind.equals(NoticeSummary.CANCELLED) ? "griefing" : "Steve", 0,
            "minecraft:diamond", null, 64, 0);
    }

    @Test
    void noticesAddUpAndRefundsAlwaysShow() {
        List<OrderStore.NoticeRow> rows = List.of(
            row(1, NoticeSummary.DELIVERED, 10, 1_000), row(2, NoticeSummary.DELIVERED, 5, 500), row(2, NoticeSummary.COMPLETE, 64, 0),
            row(3, NoticeSummary.EXPIRED, 64, 2_000), row(4, NoticeSummary.CANCELLED, 64, 300), row(5, NoticeSummary.ENDING, 64, 0));
        NoticeSummary all = NoticeSummary.of(rows, NoticeSummary.Filter.ALL, 4);
        assertEquals(15, all.delivered());
        assertEquals(1, all.complete());
        assertEquals(2_300, all.refunded());
        assertEquals(4, all.details().size());
        assertEquals(2, all.more());
        assertEquals(NoticeSummary.CANCELLED, all.details().get(0).kind(), "staff cancellations come first");
        assertEquals(NoticeSummary.EXPIRED, all.details().get(1).kind());
        assertEquals(rows, all.shown(), "every row is deleted after showing");

        NoticeSummary quiet = NoticeSummary.of(rows, NoticeSummary.Filter.REFUNDS_ONLY, 4);
        assertEquals(0, quiet.delivered(), "deliveries respect the order settings");
        assertEquals(0, quiet.complete());
        assertEquals(2_300, quiet.refunded(), "refunds and staff cancels always show");
        assertEquals(2, quiet.details().size());
        assertEquals(0, quiet.more());
        assertFalse(quiet.empty());
        assertTrue(NoticeSummary.of(List.of(row(1, NoticeSummary.DELIVERED, 1, 1)), NoticeSummary.Filter.REFUNDS_ONLY, 4).empty());
        assertNotNull(NoticeSummary.of(List.of(), NoticeSummary.Filter.ALL, 4).details());
    }
}
