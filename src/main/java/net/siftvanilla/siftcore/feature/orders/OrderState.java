package net.siftvanilla.siftcore.feature.orders;

/**
 * Where an order is in its life. Only an {@link #ACTIVE} order accepts deliveries and holds money. The other states
 * are final; an order in one of them stays visible to its owner until every delivered item was collected, and is
 * closed from then on (its row stays in storage as history).
 * <pre>
 *   ACTIVE --(last item delivered)--> FILLED
 *   ACTIVE --(owner or staff cancels)--> CANCELLED   (held money refunded)
 *   ACTIVE --(time ran out)--> EXPIRED               (held money refunded)
 * </pre>
 */
enum OrderState {
    ACTIVE,
    FILLED,
    CANCELLED,
    EXPIRED;

    /** Parses a stored state; unknown values throw {@link IllegalArgumentException}. */
    static OrderState parse(String stored) {
        return valueOf(stored.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
