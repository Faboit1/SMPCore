package net.siftvanilla.siftcore.feature.orders;

import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.BUYER;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.OTHER;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.SELLER;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.TAX;
import static net.siftvanilla.siftcore.feature.orders.OrdersTestSupport.committed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Buy orders as a market a sale is routed into: the bid index (best price each first, then oldest), what a seller may
 * fill, the revision that tells callers to refresh, and the order part of a mixed sale, which commits or fails together
 * with the server part.
 */
class OrderMarketTest {

    private static final String DIAMOND = "minecraft:diamond";

    @TempDir
    Path dir;

    private OrdersTestSupport t;

    @BeforeEach
    void setUp() throws Exception {
        this.t = new OrdersTestSupport(this.dir);
        this.t.fund(BUYER, 10_000_000);
        this.t.fund(OTHER, 10_000_000);
        this.t.fund(SELLER, 10_000_000);
    }

    @AfterEach
    void tearDown() {
        this.t.close();
    }

    private List<OrderMarket.Bid> bids(UUID seller) {
        return OrdersMarket.bids(this.t.book.bids(DIAMOND), seller, this.t.clock.get(), order -> false);
    }

    private Order now(Order order) {
        return this.t.book.get(order.id());
    }

    /** A sale as the sell feature builds it: the server pays for some units and orders take the rest, in one transaction. */
    private TransactionResult sale(UUID seller, long serverPart, List<OrderMarket.Take> takes) {
        LedgerTx.Builder tx = LedgerTx.builder().actor(seller).note("test sale");
        if (serverPart > 0) {
            tx.source(seller, Currency.MONEY, serverPart, "sell", null);
        }
        OrdersMarket.contribute(this.t.engine, tx, seller, takes, TAX);
        return this.t.ledger.execute(tx.build());
    }

    @Test
    void bidsAreTheBestPriceFirstThenTheOldest() throws Exception {
        Order low = this.t.place(BUYER, DIAMOND, 10, 300);
        this.t.clock.addAndGet(1_000);
        Order highOld = this.t.place(OTHER, DIAMOND, 10, 450);
        this.t.clock.addAndGet(1_000);
        Order highNew = this.t.place(BUYER, DIAMOND, 10, 450);
        this.t.clock.addAndGet(1_000);
        Order other = this.t.place(BUYER, "minecraft:emerald", 10, 999);

        List<Long> ids = this.t.book.bids(DIAMOND).stream().map(Order::id).toList();
        assertEquals(List.of(highOld.id(), highNew.id(), low.id()), ids, "price each descending, then created ascending");
        assertEquals(List.of(other.id()), this.t.book.bids("minecraft:emerald").stream().map(Order::id).toList(), "one list per order key");
        assertEquals(List.of(), this.t.book.bids("minecraft:gold_ingot"));

        List<OrderMarket.Bid> forSeller = bids(SELLER);
        assertEquals(3, forSeller.size());
        assertEquals(new OrderMarket.Bid(highOld.id(), OTHER, 450, 10, highOld.created()), forSeller.getFirst());
        this.t.assertHealthy();
    }

    @Test
    void sellersNeverSeeTheirOwnOrdersOrEndedOnes() throws Exception {
        Order own = this.t.place(SELLER, DIAMOND, 10, 500);
        Order cheap = this.t.place(BUYER, DIAMOND, 10, 100);
        Order expiring = this.t.place(OTHER, DIAMOND, 10, 200);
        Order filled = this.t.place(OTHER, DIAMOND, 2, 400);
        committed(this.t.engine.fill(BUYER, filled.id(), 2, 400, TAX, FillSource.MENU, "test"));

        assertEquals(List.of(expiring.id(), cheap.id()), bids(SELLER).stream().map(OrderMarket.Bid::orderId).toList(),
            "own and complete orders are left out");
        assertEquals(List.of(own.id(), expiring.id()), bids(BUYER).stream().map(OrderMarket.Bid::orderId).toList(),
            "the buyer's own order is left out for the buyer only");

        this.t.clock.addAndGet(8 * OrdersTestSupport.DAY);
        assertEquals(List.of(), bids(UUID.randomUUID()), "expired orders are left out before the sweep ends them");
        List<OrderMarket.Bid> skipped = OrdersMarket.bids(this.t.book.bids(DIAMOND), SELLER, 0, order -> order.id() == cheap.id());
        assertEquals(List.of(expiring.id()), skipped.stream().map(OrderMarket.Bid::orderId).toList(), "the skip rule (alt guard) applies");
    }

    @Test
    void theRevisionMovesWithEveryChangeAndTheIndexFollows() throws Exception {
        long start = this.t.book.revision();
        Order order = this.t.place(BUYER, DIAMOND, 10, 100);
        long placed = this.t.book.revision();
        assertTrue(placed > start, "placing bumps the revision");
        List<Order> before = this.t.book.bids(DIAMOND);
        assertSame(before, this.t.book.bids(DIAMOND), "the snapshot is reused while nothing changes");

        committed(this.t.engine.fill(SELLER, order.id(), 3, 100, TAX, FillSource.SELL, "test"));
        long filled = this.t.book.revision();
        assertTrue(filled > placed, "a fill bumps the revision");
        assertEquals(7, this.t.book.bids(DIAMOND).getFirst().remaining(), "the index shows the new count");

        committed(this.t.engine.cancel(order.id(), BUYER, "test").result());
        assertTrue(this.t.book.revision() > filled, "an end bumps the revision");
        assertEquals(List.of(), this.t.book.bids(DIAMOND), "ended orders leave the index");

        committed(this.t.engine.collect(BUYER, order.id(), 3, "test", null));
        long collected = this.t.book.revision();
        assertEquals(1, this.t.engine.prune(id -> false));
        assertNotEquals(collected, this.t.book.revision(), "pruning a closed order bumps the revision");
        assertEquals(null, this.t.engine.verifyIndex());
    }

    @Test
    void aMixedSaleCommitsTheServerPartAndEveryOrderTogether() throws Exception {
        Order first = this.t.place(BUYER, DIAMOND, 10, 500);
        Order second = this.t.place(OTHER, DIAMOND, 20, 450);
        long sellerBefore = this.t.balance(SELLER);
        List<OrderMarket.Take> takes = List.of(new OrderMarket.Take(first.id(), DIAMOND, 10, 500),
            new OrderMarket.Take(second.id(), DIAMOND, 5, 450));
        committed(sale(SELLER, 400, takes));

        long paid = 10 * 500 + 5 * 450;
        long tax = OrderMath.tax(10 * 500, TAX) + OrderMath.tax(5 * 450, TAX);
        assertEquals(sellerBefore + 400 + paid - tax, this.t.balance(SELLER));
        assertEquals(OrderState.FILLED, now(first).state());
        assertEquals(5, now(second).filled());
        assertEquals(15 * 450L, this.t.escrow());
        assertEquals(2L, this.t.number("SELECT COUNT(*) FROM order_fills WHERE source = 'sell' AND seller = ?", SELLER.toString()));
        this.t.assertHealthy();
    }

    @Test
    void aGuardedRefusalRollsBackTheWholeMixedSale() throws Exception {
        Order first = this.t.place(BUYER, DIAMOND, 10, 500);
        Order second = this.t.place(OTHER, DIAMOND, 20, 450);
        long sellerBefore = this.t.balance(SELLER);
        long escrowBefore = this.t.escrow();
        // The seller saw the second order at $450; its owner raised the price before the sale ran.
        committed(this.t.engine.edit(OTHER, second.id(), OrderEngine.Seen.of(second), 460, 20,
            new OrderEngine.EditLimits(1, 100_000, 1_000_000_000L), "test"));
        long escrowAfterEdit = this.t.escrow();
        assertEquals(escrowBefore + 20 * 10, escrowAfterEdit);
        List<OrderMarket.Take> takes = List.of(new OrderMarket.Take(first.id(), DIAMOND, 10, 500),
            new OrderMarket.Take(second.id(), DIAMOND, 5, 450));

        TransactionResult result = sale(SELLER, 400, takes);
        assertEquals(TransactionStatus.REJECTED, result.status());
        assertEquals(Refusal.PRICE_CHANGED, Refusal.from(result.reason()));
        assertEquals(OrderMarket.Refusal.PRICE_CHANGED, OrderMarket.Refusal.of(result.reason()), "the sell side reads the same reason");
        assertEquals(sellerBefore, this.t.balance(SELLER), "not even the server part was paid");
        assertEquals(0, now(first).filled(), "the order that was fine took nothing either");
        assertEquals(0, now(second).filled());
        assertEquals(escrowAfterEdit, this.t.escrow());
        assertEquals(0L, this.t.number("SELECT COUNT(*) FROM order_fills"));

        // More than an order still wants is refused the same way.
        TransactionResult tooMany = sale(SELLER, 400, List.of(new OrderMarket.Take(first.id(), DIAMOND, 11, 500)));
        assertEquals(Refusal.NOT_ENOUGH_LEFT, Refusal.from(tooMany.reason()));
        // So is a seller's own order.
        TransactionResult own = sale(BUYER, 400, List.of(new OrderMarket.Take(first.id(), DIAMOND, 1, 500)));
        assertEquals(Refusal.OWN_ORDER, Refusal.from(own.reason()));
        assertEquals(sellerBefore, this.t.balance(SELLER));
        this.t.assertHealthy();
    }

    @Test
    void takesForOneOrderAreMergedSoTheyCanNeverOverfill() throws Exception {
        Order order = this.t.place(BUYER, DIAMOND, 10, 500);
        List<OrderMarket.Take> split = List.of(new OrderMarket.Take(order.id(), DIAMOND, 6, 500),
            new OrderMarket.Take(order.id(), DIAMOND, 6, 500));
        assertEquals(List.of(new OrderMarket.Take(order.id(), DIAMOND, 12, 500)), OrdersMarket.merged(split));
        TransactionResult result = sale(SELLER, 0, split);
        assertEquals(Refusal.NOT_ENOUGH_LEFT, Refusal.from(result.reason()), "12 merged units are more than the 10 wanted");
        assertEquals(0, now(order).filled());

        committed(sale(SELLER, 0, List.of(new OrderMarket.Take(order.id(), DIAMOND, 4, 500),
            new OrderMarket.Take(order.id(), DIAMOND, 6, 500))));
        assertEquals(OrderState.FILLED, now(order).state());
        assertEquals(1L, this.t.number("SELECT COUNT(*) FROM order_fills WHERE order_id = ?", order.id()), "one fill row per order");

        assertThrows(IllegalArgumentException.class, () -> OrdersMarket.merged(List.of(new OrderMarket.Take(1, DIAMOND, 1, 500),
            new OrderMarket.Take(1, DIAMOND, 1, 501))), "the same order at two prices is a broken caller");
        TransactionResult broken = sale(SELLER, 100, List.of(new OrderMarket.Take(order.id(), DIAMOND, 1, 500),
            new OrderMarket.Take(order.id(), DIAMOND, 1, 501)));
        assertEquals(TransactionStatus.REJECTED, broken.status(), "a broken caller's sale is refused, never thrown at");
        assertTrue(OrderMarket.Refusal.of(broken.reason()) != null, "with a reason the sale retries on");
        assertThrows(IllegalArgumentException.class, () -> new OrderMarket.Take(1, DIAMOND, 0, 500));
        this.t.assertHealthy();
    }
}
