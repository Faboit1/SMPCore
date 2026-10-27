package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.economy.SystemAccounts;

/**
 * The money and state rules of buy orders, free of any server dependency. Every change is one economy transaction:
 * its checks re-validate the order under the economy lock (so a stale menu, a second click or a racing player can
 * never get past them), its applies change the {@link OrderBook}, and its SQL commits together with the ledger rows.
 * <ul>
 *   <li>create: buyer to the orders escrow, {@code quantity * priceEach} ({@value #KIND_ESCROW});</li>
 *   <li>fill: escrow to seller, {@code units * priceEach} ({@value #KIND_FILL}), seller pays the tax sink
 *       ({@value #KIND_TAX}); {@link #addFill} adds one to any transaction, so a sale can fill orders and pay the
 *       server part in one unit;</li>
 *   <li>edit: owner to escrow, the extra a higher price or more items need ({@value #KIND_ESCROW});</li>
 *   <li>cancel and expire: escrow back to the buyer, everything still held ({@value #KIND_REFUND});</li>
 *   <li>collect, extend and warn: no money, only the order's own fields (items are handed out after the commit).</li>
 * </ul>
 * The orders escrow account therefore always holds exactly the money of the active orders.
 */
final class OrderEngine {

    static final String KIND_ESCROW = "order_escrow";
    static final String KIND_FILL = "order_fill";
    static final String KIND_TAX = "order_tax";
    static final String KIND_REFUND = "order_refund";

    /** Retries when an order changed between reading it and the transaction (only cancel and expiry need this). */
    private static final int ATTEMPTS = 5;

    /** Extra rules the engine asks under the economy lock. Implementations must be cheap and thread-safe. */
    interface Rules {

        Rules NONE = new Rules() {
            @Override
            public boolean related(UUID owner, UUID seller) {
                return false;
            }

            @Override
            public boolean deliverable(Order order) {
                return true;
            }
        };

        /** True when {@code seller} may not fill {@code owner}'s orders (the alt-account guard). */
        boolean related(UUID owner, UUID seller);

        /** True when the order's item can be handled right now (a known variant). */
        boolean deliverable(Order order);
    }

    /** An order to place. */
    record Draft(UUID owner, String itemType, String variant, int quantity, long priceEach, long durationMillis) {
    }

    /** The result of {@link #create}: the order as it was placed and the transaction. */
    record Created(Order order, TransactionResult result) {
    }

    /**
     * The result of ending an order: the order as it was just before (its {@code escrow} is what was refunded when
     * the transaction succeeded), or null when it did not exist, and the transaction.
     */
    record Ended(Order before, TransactionResult result) {
        long refund() {
            return this.before == null ? 0 : this.before.escrow();
        }
    }

    /** What an order looked like when the owner chose to change it; the change is refused if it moved since. */
    record Seen(long priceEach, int quantity, int filled, long expires) {
        static Seen of(Order order) {
            return new Seen(order.priceEach(), order.quantity(), order.filled(), order.expires());
        }
    }

    /** Limits an edited order must stay within. */
    record EditLimits(long minPrice, int maxQuantity, long maxTotal) {
    }

    /** Collected items of one order in a collect-all. */
    record Collect(long orderId, int amount) {
    }

    /** Holds what an apply did, for the SQL that runs later on the database writer. */
    private static final class Change {
        private volatile Order before;
        private volatile Order after;

        void set(Order[] pair) {
            this.before = pair[0];
            this.after = pair[1];
        }
    }

    private final Ledger ledger;
    private final OrderBook book;
    private final LongSupplier clock;
    private final LongSupplier ids;
    private final Rules rules;

    OrderEngine(Ledger ledger, OrderBook book, LongSupplier clock, LongSupplier ids, Rules rules) {
        this.ledger = ledger;
        this.book = book;
        this.clock = clock;
        this.ids = ids;
        this.rules = rules;
    }

    OrderBook book() {
        return this.book;
    }

    Ledger ledger() {
        return this.ledger;
    }

    long now() {
        return this.clock.getAsLong();
    }

    // ------------------------------------------------------------------ placing

    /**
     * Places an order: holds {@code quantity * priceEach} of the owner's money. Refused when the owner already has
     * {@code limit} active orders (checked under the economy lock) or can't pay.
     */
    Created create(Draft draft, int limit, String actor) {
        long total = OrderMath.total(draft.quantity(), draft.priceEach());
        if (draft.quantity() <= 0 || draft.priceEach() <= 0 || total <= 0 || draft.durationMillis() <= 0) {
            throw new IllegalArgumentException("Invalid order " + draft);
        }
        long now = now();
        Order order = Order.placed(this.ids.getAsLong(), draft.owner(), draft.itemType(), draft.variant(), draft.quantity(),
            draft.priceEach(), now, now + draft.durationMillis());
        LedgerTx tx = LedgerTx.builder()
            .actor(actor)
            .note("order " + order.id() + ": " + draft.quantity() + " " + order.key())
            .transfer(draft.owner(), SystemAccounts.ORDERS_ESCROW, Currency.MONEY, total, KIND_ESCROW, order.ref())
            .check(() -> this.book.activeCount(draft.owner()) >= limit ? Refusal.LIMIT.reason() : null)
            .check(() -> this.book.get(order.id()) != null ? Refusal.CHANGED.reason() : null)
            .apply(() -> this.book.put(order), () -> this.book.remove(order.id()))
            .write(OrderStore.insert(order))
            .build();
        return new Created(order, this.ledger.execute(tx));
    }

    // ------------------------------------------------------------------ delivering

    /**
     * Adds a delivery of {@code units} items to {@code tx} at the price the seller saw: the order pays
     * {@code units * expectedPrice} to the seller from the escrow, the seller pays the tax, and under the economy lock
     * the order must still be active, not the seller's own, at that price, not expired, deliverable and want at least
     * {@code units}. One order may appear only once per transaction (checks see the state before every apply).
     */
    void addFill(LedgerTx.Builder tx, UUID seller, long id, int units, long expectedPrice, int taxBasisPoints, FillSource source) {
        if (units <= 0 || expectedPrice <= 0) {
            throw new IllegalArgumentException("Nothing to deliver");
        }
        long paid = OrderMath.total(units, expectedPrice);
        if (paid <= 0) {
            throw new IllegalArgumentException("Delivery value overflows");
        }
        long tax = OrderMath.tax(paid, taxBasisPoints);
        long now = now();
        String ref = Order.ref(id);
        Change change = new Change();
        tx.transfer(SystemAccounts.ORDERS_ESCROW, seller, Currency.MONEY, paid, KIND_FILL, ref);
        if (tax > 0) {
            tx.sink(seller, Currency.MONEY, tax, KIND_TAX, ref);
        }
        tx.check(() -> fillRefusal(id, seller, units, expectedPrice, now));
        tx.apply(() -> change.set(this.book.update(id, order -> order.withFill(units, paid, now))),
            () -> this.book.update(id, order -> order.withoutFill(units, paid)));
        tx.write(c -> {
            OrderStore.fill(c, change.before, change.after, seller, paid, tax, now, source.id());
            return null;
        });
    }

    /**
     * Why a delivery would be refused now, or null when it may go ahead. Call under the economy lock. Every reason is
     * one of {@link net.siftvanilla.siftcore.core.link.OrderMarket.Refusal}, so a routed sale retries with fresh bids:
     * an order whose item can't be handled right now refuses as no longer active, and an order of a player who shares
     * the seller's address refuses like the seller's own order (fresh bids leave both out). The delivery menu and
     * quick deliver tell the precise reason from their own checks.
     */
    String fillRefusal(long id, UUID seller, int units, long expectedPrice, long now) {
        Order order = this.book.get(id);
        if (order == null) {
            return Refusal.GONE.reason();
        }
        if (!order.active() || !this.rules.deliverable(order)) {
            return Refusal.NOT_ACTIVE.reason();
        }
        if (order.owner().equals(seller) || this.rules.related(order.owner(), seller)) {
            return Refusal.OWN_ORDER.reason();
        }
        if (order.priceEach() != expectedPrice) {
            return Refusal.PRICE_CHANGED.reason();
        }
        if (order.expiredAt(now)) {
            return Refusal.EXPIRED.reason();
        }
        return order.remaining() < units ? Refusal.NOT_ENOUGH_LEFT.reason() : null;
    }

    /** One delivery as its own transaction (the delivery menu and quick deliver). */
    TransactionResult fill(UUID seller, long id, int units, long expectedPrice, int taxBasisPoints, FillSource source, String actor) {
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(actor)
            .note("order " + id + ": " + units + " delivered (" + source.id() + ")");
        addFill(tx, seller, id, units, expectedPrice, taxBasisPoints, source);
        return this.ledger.execute(tx.build());
    }

    // ------------------------------------------------------------------ changing an active order

    /**
     * Raises the price each and/or the quantity of the owner's active order and holds the extra money it needs. Refused
     * unless the order still looks exactly as the owner saw it, is unexpired, the new terms are not lower and stay
     * within the limits. Returns the result; the extra is {@link OrderMath#editExtra}.
     */
    TransactionResult edit(UUID owner, long id, Seen seen, long newPrice, int newQuantity, EditLimits limits, String actor) {
        if (newPrice < seen.priceEach() || newQuantity < seen.quantity()
            || (newPrice == seen.priceEach() && newQuantity == seen.quantity())) {
            throw new IllegalArgumentException("An edit must raise the price or the quantity");
        }
        long extra = OrderMath.editExtra(seen.quantity(), seen.filled(), seen.priceEach(), newQuantity, newPrice);
        long newEscrow = OrderMath.total((long) newQuantity - seen.filled(), newPrice);
        if (extra <= 0 || newEscrow <= 0) {
            throw new IllegalArgumentException("An edit must hold more money");
        }
        long now = now();
        String ref = Order.ref(id);
        Change change = new Change();
        LedgerTx tx = LedgerTx.builder()
            .actor(actor)
            .note("order " + id + " changed to " + newQuantity + " at " + newPrice)
            .transfer(owner, SystemAccounts.ORDERS_ESCROW, Currency.MONEY, extra, KIND_ESCROW, ref)
            .check(() -> {
                Order order = this.book.get(id);
                if (order == null) {
                    return Refusal.GONE.reason();
                }
                if (!order.owner().equals(owner)) {
                    return Refusal.NOT_OWNER.reason();
                }
                if (!order.active()) {
                    return Refusal.NOT_ACTIVE.reason();
                }
                if (order.expiredAt(now)) {
                    return Refusal.EXPIRED.reason();
                }
                if (order.priceEach() != seen.priceEach() || order.quantity() != seen.quantity() || order.filled() != seen.filled()) {
                    return Refusal.CHANGED.reason();
                }
                long total = OrderMath.total(newQuantity, newPrice);
                if (newPrice < limits.minPrice() || newQuantity > limits.maxQuantity() || total <= 0 || total > limits.maxTotal()) {
                    return Refusal.OUT_OF_LIMITS.reason();
                }
                return null;
            })
            .apply(() -> change.set(this.book.update(id, order -> order.withTerms(newPrice, newQuantity, newEscrow))),
                () -> this.book.update(id, order -> order.withTerms(seen.priceEach(), seen.quantity(), order.escrow() - extra)))
            .write(c -> {
                OrderStore.terms(c, change.before, change.after);
                return null;
            })
            .build();
        return this.ledger.execute(tx);
    }

    /**
     * Moves the end of the owner's active order to {@code newExpires} (no money). Refused unless the order still ends
     * when the owner saw it and the new time is later.
     */
    TransactionResult extend(UUID owner, long id, long seenExpires, long newExpires, String actor) {
        if (newExpires <= seenExpires) {
            throw new IllegalArgumentException("Extending must move the end later");
        }
        Change change = new Change();
        LedgerTx tx = LedgerTx.builder()
            .actor(actor)
            .silent()
            .note("order " + id + " extended")
            .check(() -> {
                Order order = this.book.get(id);
                if (order == null) {
                    return Refusal.GONE.reason();
                }
                if (!order.owner().equals(owner)) {
                    return Refusal.NOT_OWNER.reason();
                }
                if (!order.active()) {
                    return Refusal.NOT_ACTIVE.reason();
                }
                return order.expires() != seenExpires ? Refusal.CHANGED.reason() : null;
            })
            .apply(() -> change.set(this.book.update(id, order -> order.withExpires(newExpires, false))),
                () -> this.book.update(id, order -> order.withExpires(seenExpires, change.before.warned())))
            .write(c -> {
                OrderStore.expires(c, change.before, change.after);
                return null;
            })
            .build();
        return this.ledger.executeDomain(tx);
    }

    /** Records that the owner was told the order ends soon, so they are told once. */
    TransactionResult warn(long id) {
        Change change = new Change();
        LedgerTx tx = LedgerTx.builder()
            .actor("system")
            .silent()
            .check(() -> {
                Order order = this.book.get(id);
                if (order == null) {
                    return Refusal.GONE.reason();
                }
                if (!order.active()) {
                    return Refusal.NOT_ACTIVE.reason();
                }
                return order.warned() ? Refusal.ALREADY_WARNED.reason() : null;
            })
            .apply(() -> change.set(this.book.update(id, order -> order.withWarned(true))),
                () -> this.book.update(id, order -> order.withWarned(false)))
            .write(c -> {
                OrderStore.warned(c, change.after);
                return null;
            })
            .build();
        return this.ledger.executeDomain(tx);
    }

    // ------------------------------------------------------------------ ending

    /**
     * Cancels an active order and refunds everything it still holds to its owner. {@code owner} is the player who
     * asks (refused unless it is the owner), or null for staff. Delivered items stay collectable.
     */
    Ended cancel(long id, UUID owner, String actor) {
        return end(id, owner, OrderState.CANCELLED, actor);
    }

    /** Ends an active order whose time ran out and refunds what it holds. Internal bookkeeping: no public event. */
    Ended expire(long id) {
        return end(id, null, OrderState.EXPIRED, "system");
    }

    private Ended end(long id, UUID asker, OrderState end, String actor) {
        Ended ended = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            ended = endOnce(id, asker, end, actor);
            TransactionResult result = ended.result();
            if (result.status() != TransactionStatus.REJECTED || Refusal.from(result.reason()) != Refusal.CHANGED) {
                return ended;
            }
        }
        return ended;
    }

    private Ended endOnce(long id, UUID asker, OrderState end, String actor) {
        Order seen = this.book.get(id);
        if (seen == null) {
            return new Ended(null, TransactionResult.failed(null, TransactionStatus.REJECTED, Refusal.GONE.reason()));
        }
        long refund = seen.escrow();
        long now = now();
        Change change = new Change();
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(actor)
            .note("order " + id + " " + end.name().toLowerCase(java.util.Locale.ROOT));
        if (end == OrderState.EXPIRED) {
            tx.silent();
        }
        if (refund > 0) {
            tx.transfer(SystemAccounts.ORDERS_ESCROW, seen.owner(), Currency.MONEY, refund, KIND_REFUND, seen.ref());
        }
        tx.check(() -> {
            Order order = this.book.get(id);
            if (order == null) {
                return Refusal.GONE.reason();
            }
            if (!order.active()) {
                return Refusal.NOT_ACTIVE.reason();
            }
            if (asker != null && !order.owner().equals(asker)) {
                return Refusal.NOT_OWNER.reason();
            }
            if (end == OrderState.EXPIRED && !order.expiredAt(now)) {
                return Refusal.NOT_EXPIRED.reason();
            }
            return order.escrow() != refund ? Refusal.CHANGED.reason() : null;
        });
        tx.apply(() -> change.set(this.book.update(id, order -> order.withEnd(end, refund, now))),
            () -> this.book.update(id, order -> order.withoutEnd(refund)));
        tx.write(c -> {
            OrderStore.end(c, change.before, change.after);
            return null;
        });
        return new Ended(seen, this.ledger.execute(tx.build()));
    }

    /** Ends every active order whose time ran out, one transaction each. */
    List<Ended> expireDue() {
        List<Ended> ended = new ArrayList<>();
        for (Order order : this.book.due(now())) {
            ended.add(expire(order.id()));
        }
        return ended;
    }

    // ------------------------------------------------------------------ collecting

    /**
     * Takes {@code amount} delivered items out of the owner's order. Only the count changes here; the caller hands
     * the items over after the transaction is committed (or puts them back with {@link #uncollect}). {@code extra}
     * may add more to the same transaction (the claim box part of a collect), or be null.
     */
    TransactionResult collect(UUID owner, long id, int amount, String actor, Consumer<LedgerTx.Builder> extra) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Nothing to collect");
        }
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor).silent().note("order " + id + ": collected " + amount);
        addCollect(tx, owner, id, amount);
        if (extra != null) {
            extra.accept(tx);
        }
        return this.ledger.execute(tx.build());
    }

    /**
     * Collects from several orders in one transaction, one guarded change per order; refused as a whole when any of
     * them changed. Each order may appear once.
     */
    TransactionResult collectAll(UUID owner, List<Collect> collects, String actor) {
        if (collects.isEmpty()) {
            throw new IllegalArgumentException("Nothing to collect");
        }
        if (collects.stream().map(Collect::orderId).distinct().count() != collects.size()) {
            throw new IllegalArgumentException("An order appears twice in one collect");
        }
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor).silent().note("orders: collected from " + collects.size());
        for (Collect collect : collects) {
            if (collect.amount() <= 0) {
                throw new IllegalArgumentException("Nothing to collect from order " + collect.orderId());
            }
            addCollect(tx, owner, collect.orderId(), collect.amount());
        }
        return this.ledger.execute(tx.build());
    }

    /** Puts items that were collected but could not be handed over back into the order. */
    TransactionResult uncollect(UUID owner, long id, int amount, String actor) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Nothing to put back");
        }
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor).silent().note("order " + id + ": " + amount + " put back");
        addCollect(tx, owner, id, -amount);
        return this.ledger.execute(tx.build());
    }

    private void addCollect(LedgerTx.Builder tx, UUID owner, long id, int delta) {
        Change change = new Change();
        tx.check(() -> {
            Order order = this.book.get(id);
            if (order == null) {
                return Refusal.GONE.reason();
            }
            if (!order.owner().equals(owner)) {
                return Refusal.NOT_OWNER.reason();
            }
            if (delta > 0 && order.waiting() < delta) {
                return Refusal.NOTHING_WAITING.reason();
            }
            return delta < 0 && order.collected() < -delta ? Refusal.CHANGED.reason() : null;
        });
        tx.apply(() -> change.set(this.book.update(id, order -> order.withCollected(delta))),
            () -> this.book.update(id, order -> order.withCollected(-delta)));
        tx.write(c -> {
            OrderStore.collected(c, change.before, change.after);
            return null;
        });
    }

    // ------------------------------------------------------------------ housekeeping

    /** Drops closed orders from memory (under the economy lock) unless {@code keep} holds for their id. */
    int prune(LongPredicate keep) {
        return this.ledger.locked(() -> this.book.pruneClosed(keep));
    }

    /**
     * Compares the orders escrow account with the money the orders hold, under the economy lock. Returns null when
     * they match, otherwise what differs.
     */
    String verifyEscrow() {
        return this.ledger.locked(() -> {
            long account = this.ledger.balance(SystemAccounts.ORDERS_ESCROW, Currency.MONEY);
            long held = this.book.escrowTotal();
            if (account != held) {
                return "the orders escrow account holds " + account + " but the open orders hold " + held;
            }
            return this.book.verify();
        });
    }

    /** Null when the published bid index matches the book, under the economy lock. */
    String verifyIndex() {
        return this.ledger.locked(this.book::verifyIndex);
    }
}
