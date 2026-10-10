package net.siftvanilla.siftcore.core.link;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The buy-order book as a market that sales can route items to. Implemented by the orders feature; consumed by
 * selling ({@code /sell}), which sends a unit to an order when the order pays the seller more than the server would,
 * all inside the sale's single ledger transaction.
 * <p>
 * Contract: {@link #bids} returns an immutable snapshot (safe to read on any thread); {@link #contribute} adds the
 * order side of a sale to the seller's transaction (the transfer out of escrow, the tax, the guarded check that
 * re-validates every take under the economy lock, the in-memory change and the guarded SQL), so the order book and
 * the money can never disagree; {@link #approve} and {@link #committed} run on the seller's thread.
 */
public interface OrderMarket {

    /** No orders feature: nothing is ever routed. */
    OrderMarket NONE = new OrderMarket() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public long revision() {
            return 0;
        }

        @Override
        public int taxBasisPoints() {
            return 0;
        }

        @Override
        public String key(ItemStack sample) {
            return null;
        }

        @Override
        public List<Bid> bids(UUID seller, String key) {
            return List.of();
        }

        @Override
        public Problem usable(Player seller) {
            return null;
        }

        @Override
        public List<Take> approve(Player seller, List<Take> takes) {
            return List.of();
        }

        @Override
        public void contribute(LedgerTx.Builder tx, UUID seller, List<Take> takes) {
            if (!takes.isEmpty()) {
                throw new IllegalStateException("There are no buy orders to fill");
            }
        }

        @Override
        public void committed(Player seller, List<Take> takes) {
        }
    };

    /**
     * An open order that would take items.
     *
     * @param orderId   the order
     * @param owner     who placed it
     * @param priceEach what it pays per item before tax
     * @param remaining how many items it still wants
     * @param created   when it was placed (epoch millis), the tie breaker between equal prices
     */
    record Bid(long orderId, UUID owner, long priceEach, long remaining, long created) {
        public Bid {
            Objects.requireNonNull(owner, "owner");
            if (priceEach < 1 || remaining < 0) {
                throw new IllegalArgumentException("A bid pays at least $1 and wants zero or more items");
            }
        }
    }

    /**
     * Units of one item sent to one order.
     *
     * @param orderId   the order
     * @param key       the order key of the item ({@link #key(ItemStack)})
     * @param units     how many items
     * @param priceEach the price per item the seller saw (re-checked inside the transaction)
     */
    record Take(long orderId, String key, long units, long priceEach) {
        public Take {
            Objects.requireNonNull(key, "key");
            if (units < 1 || priceEach < 1) {
                throw new IllegalArgumentException("A take moves at least one item worth at least $1");
            }
        }

        /** Units times price each, before tax. */
        public long gross() {
            return Math.multiplyExact(this.units, this.priceEach);
        }
    }

    /** Why a seller can't fill orders right now (a message for them). */
    record Problem(MessageKey message, List<Arg> args) {
        public Problem {
            Objects.requireNonNull(message, "message");
            args = List.copyOf(args);
        }
    }

    /**
     * Why the guarded order check refused a take. The order side of a transaction fails with the reason
     * {@link #reason()}, so a seller can tell an order refusal from other failures and retry with fresh bids.
     */
    enum Refusal {
        GONE,
        NOT_ACTIVE,
        OWN_ORDER,
        PRICE_CHANGED,
        EXPIRED,
        NOT_ENOUGH_LEFT;

        /** The transaction failure reason, e.g. {@code order_price_changed}. */
        public String reason() {
            return "order_" + name().toLowerCase(Locale.ROOT);
        }

        /** The refusal behind a transaction failure reason, or null when the reason is not an order refusal. */
        public static Refusal of(String reason) {
            if (reason == null) {
                return null;
            }
            for (Refusal refusal : values()) {
                if (refusal.reason().equals(reason)) {
                    return refusal;
                }
            }
            return null;
        }
    }

    /** False when there is no order book (orders disabled or not installed): nothing is routed. */
    boolean available();

    /** Bumps on every change of the order book; previews cache bids per revision. */
    long revision();

    /** The order tax in basis points (200 = 2%), taken from what the seller is paid. */
    int taxBasisPoints();

    /**
     * The order key of an exact item ({@code minecraft:diamond} for a plain diamond), or null when no order could
     * take it. Call on a thread that may read the stack.
     */
    String key(ItemStack sample);

    /**
     * The open orders for a key that the seller may fill: active, not expired, not their own, best price first and
     * then oldest first. An immutable snapshot.
     */
    List<Bid> bids(UUID seller, String key);

    /** Null when the seller may fill orders now (permission and combat), else why not. Seller's thread. */
    Problem usable(Player seller);

    /**
     * Fires the cancellable order fill event for every take (source: selling) on the seller's thread and returns
     * the takes nobody cancelled.
     */
    List<Take> approve(Player seller, List<Take> takes);

    /**
     * Adds the order side of a sale to the seller's transaction, per take: the transfer out of escrow to the seller
     * (units x price each, kind {@code order_fill}, ref {@code order:<id>}), the tax as a sink (kind
     * {@code order_tax}), a check that refuses with a {@link Refusal} reason when the order changed, the in-memory
     * change with its undo, and the guarded SQL (fill source {@code sell}).
     */
    void contribute(LedgerTx.Builder tx, UUID seller, List<Take> takes);

    /** After the sale committed: tells each order owner once and refreshes their menus. Seller's thread. */
    void committed(Player seller, List<Take> takes);

    /**
     * Opens the "place a buy order" form with the item filled in (from the worth details' "Order it" button);
     * {@code back} returns to where the player came from. False when orders can't be placed for the item. Player's
     * thread.
     */
    default boolean openOrderForm(Player player, String key, Runnable back) {
        return false;
    }

    /**
     * The tax on one take's {@code gross} (units x price each) at {@code taxBasisPoints}, rounded down once per take.
     * Exact for every long (no intermediate overflow).
     */
    static long tax(long gross, int taxBasisPoints) {
        if (taxBasisPoints < 0 || taxBasisPoints > 10_000) {
            throw new IllegalArgumentException("Tax is 0 to 10000 basis points");
        }
        if (gross < 0) {
            throw new IllegalArgumentException("A gross amount is never negative");
        }
        return (gross / 10_000L) * taxBasisPoints + (gross % 10_000L) * taxBasisPoints / 10_000L;
    }

    /** What a take pays the seller: its gross minus the tax. */
    static long net(Take take, int taxBasisPoints) {
        long gross = take.gross();
        return gross - tax(gross, taxBasisPoints);
    }
}
