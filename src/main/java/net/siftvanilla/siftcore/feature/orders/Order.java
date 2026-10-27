package net.siftvanilla.siftcore.feature.orders;

import java.util.Objects;
import java.util.UUID;

/**
 * One buy order, immutable. The {@link OrderBook} replaces the record on every change, so a reader always sees a
 * consistent order.
 *
 * @param id        unique id, also the reference in the ledger ({@code order:<id>})
 * @param owner     the player who placed it
 * @param itemType  the wanted item type as a namespaced key, e.g. {@code minecraft:diamond}
 * @param variant   the exact variant wanted (see {@link Variant}), or null for plain items of {@code itemType}
 * @param quantity  how many items are wanted
 * @param filled    how many were delivered
 * @param collected how many of the delivered items the owner took out
 * @param priceEach what the owner pays for each delivered item
 * @param escrow    money still held for the order: {@code (quantity - filled) * priceEach} while active, else 0
 * @param created   epoch millis
 * @param expires   epoch millis when the order ends unless filled or cancelled first
 * @param state     where the order is in its life
 * @param ended     epoch millis when it stopped taking deliveries (complete, cancelled, expired), 0 while active
 * @param refunded  money that went back to the owner when it ended early
 * @param warned    whether the owner was told it ends soon
 */
record Order(long id, UUID owner, String itemType, String variant, int quantity, int filled, int collected, long priceEach,
             long escrow, long created, long expires, OrderState state, long ended, long refunded, boolean warned) {

    /** The ledger reference of an order. */
    static String ref(long id) {
        return "order:" + id;
    }

    Order {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(itemType);
        Objects.requireNonNull(state);
        if (variant != null && variant.isBlank()) {
            variant = null;
        }
    }

    /** A new active order. */
    static Order placed(long id, UUID owner, String itemType, String variant, int quantity, long priceEach, long created, long expires) {
        return new Order(id, owner, itemType, variant, quantity, 0, 0, priceEach, escrowFor(quantity, priceEach), created, expires,
            OrderState.ACTIVE, 0, 0, false);
    }

    String ref() {
        return ref(this.id);
    }

    /** The exact thing this order takes: {@code minecraft:diamond} or {@code minecraft:enchanted_book|enchant:...}. */
    String key() {
        return OrderKeys.key(this.itemType, this.variant);
    }

    boolean active() {
        return this.state == OrderState.ACTIVE;
    }

    /** Items still wanted: 0 once the order is no longer active. */
    int remaining() {
        return active() ? Math.max(0, this.quantity - this.filled) : 0;
    }

    /** Delivered items the owner has not collected yet. */
    int waiting() {
        return Math.max(0, this.filled - this.collected);
    }

    /** Closed: no longer active and nothing left to collect. Closed orders leave memory. */
    boolean closed() {
        return !active() && this.collected >= this.filled;
    }

    /** True once the order's time ran out (it may not have been refunded yet). */
    boolean expiredAt(long now) {
        return now >= this.expires;
    }

    long millisLeft(long now) {
        return Math.max(0, this.expires - now);
    }

    /** What the order holds when it is first placed. */
    static long escrowFor(int units, long priceEach) {
        return Math.multiplyExact(priceEach, (long) units);
    }

    // ------------------------------------------------------------------ transitions (exact inverses in pairs)

    /** {@code units} were delivered for {@code paid}; the last delivery completes the order at {@code now}. */
    Order withFill(int units, long paid, long now) {
        int newFilled = this.filled + units;
        boolean completes = this.state == OrderState.ACTIVE && newFilled >= this.quantity;
        return new Order(this.id, this.owner, this.itemType, this.variant, this.quantity, newFilled, this.collected, this.priceEach,
            this.escrow - paid, this.created, this.expires, completes ? OrderState.FILLED : this.state,
            completes ? now : this.ended, this.refunded, this.warned);
    }

    /** Undoes {@link #withFill}. */
    Order withoutFill(int units, long paid) {
        boolean reopens = this.state == OrderState.FILLED;
        return new Order(this.id, this.owner, this.itemType, this.variant, this.quantity, this.filled - units, this.collected,
            this.priceEach, this.escrow + paid, this.created, this.expires, reopens ? OrderState.ACTIVE : this.state,
            reopens ? 0 : this.ended, this.refunded, this.warned);
    }

    /** Ends the order ({@link OrderState#CANCELLED} or {@link OrderState#EXPIRED}); the held money was refunded. */
    Order withEnd(OrderState end, long refund, long now) {
        return new Order(this.id, this.owner, this.itemType, this.variant, this.quantity, this.filled, this.collected, this.priceEach,
            this.escrow - refund, this.created, this.expires, end, now, this.refunded + refund, this.warned);
    }

    /** Undoes {@link #withEnd}. */
    Order withoutEnd(long refund) {
        return new Order(this.id, this.owner, this.itemType, this.variant, this.quantity, this.filled, this.collected, this.priceEach,
            this.escrow + refund, this.created, this.expires, OrderState.ACTIVE, 0, this.refunded - refund, this.warned);
    }

    /** {@code amount} delivered items were taken out (negative to put them back). */
    Order withCollected(int amount) {
        return new Order(this.id, this.owner, this.itemType, this.variant, this.quantity, this.filled, this.collected + amount,
            this.priceEach, this.escrow, this.created, this.expires, this.state, this.ended, this.refunded, this.warned);
    }

    /** A new price each and quantity, holding {@code newEscrow} (the owner paid the difference). */
    Order withTerms(long newPrice, int newQuantity, long newEscrow) {
        return new Order(this.id, this.owner, this.itemType, this.variant, newQuantity, this.filled, this.collected, newPrice,
            newEscrow, this.created, this.expires, this.state, this.ended, this.refunded, this.warned);
    }

    /** A new end time; the owner will be warned again before it. */
    Order withExpires(long newExpires, boolean newWarned) {
        return new Order(this.id, this.owner, this.itemType, this.variant, this.quantity, this.filled, this.collected, this.priceEach,
            this.escrow, this.created, newExpires, this.state, this.ended, this.refunded, newWarned);
    }

    Order withWarned(boolean newWarned) {
        return withExpires(this.expires, newWarned);
    }
}
