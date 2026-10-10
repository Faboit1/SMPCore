package net.siftvanilla.siftcore.feature.orders;

import java.util.Locale;
import net.siftvanilla.siftcore.core.link.OrderMarket;

/**
 * Why the order engine refused a transaction. A check returns {@link #reason()} as the ledger's failure reason and
 * {@link #from(String)} reads it back. The reasons are {@code order_<name>}, the format of {@link OrderMarket.Refusal},
 * so a sale that routed items to orders recognizes every delivery refusal and retries with fresh bids.
 */
enum Refusal {
    /** The order does not exist (any more). */
    GONE,
    /** The order no longer accepts deliveries or changes (filled, cancelled or expired). */
    NOT_ACTIVE,
    /** Players can't deliver to their own orders (nor to orders of a player who shares their address). */
    OWN_ORDER,
    /** Only the owner may do that. */
    NOT_OWNER,
    /** The price is not the one the player saw. */
    PRICE_CHANGED,
    /** Fewer items are wanted than the player tried to deliver. */
    NOT_ENOUGH_LEFT,
    /** The order's time ran out. */
    EXPIRED,
    /** The order still has time left (refused expiry). */
    NOT_EXPIRED,
    /** The player has as many active orders as allowed. */
    LIMIT,
    /** Fewer items wait to be collected than asked for. */
    NOTHING_WAITING,
    /** The order changed between reading it and the transaction (retried by the engine where that is safe). */
    CHANGED,
    /** A new price, quantity or total is outside the configured limits. */
    OUT_OF_LIMITS,
    /** The owner was already warned that the order ends soon. */
    ALREADY_WARNED;

    /** The ledger failure reason, e.g. {@code order_price_changed}. */
    String reason() {
        return "order_" + name().toLowerCase(Locale.ROOT);
    }

    /** The refusal behind a ledger failure reason, or null for reasons that are not refusals. */
    static Refusal from(String reason) {
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
