package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.List;

/**
 * The "while you were away" summary of an owner's notices. Pure: the server side renders it.
 * <p>
 * Deliveries, completions and ending warnings follow the player's settings ({@link Filter}); refunds of ended orders
 * and staff cancellations always show (money moved). Detail lines are capped; the rest is counted.
 *
 * @param delivered items delivered to the owner's orders while away
 * @param complete  orders that completed
 * @param refunded  money that came back from orders that ended early (expired or cancelled by staff)
 * @param details   rows worth one line each, most important first, at most the cap
 * @param more      rows that did not fit the cap
 * @param shown     every row that was considered (they are deleted once the summary was shown)
 */
record NoticeSummary(long delivered, int complete, long refunded, List<OrderStore.NoticeRow> details, int more,
                     List<OrderStore.NoticeRow> shown) {

    static final String DELIVERED = "delivered";
    static final String COMPLETE = "complete";
    static final String EXPIRED = "expired";
    static final String CANCELLED = "cancelled";
    static final String ENDING = "ending";

    /**
     * Which kinds of rows a player wants summed up (refunds and staff cancellations always show).
     *
     * @param deliveries  every delivery ({@code order-notices} chat or above the hotbar)
     * @param completions completed orders ({@code order-notices} anything but off)
     * @param ending      orders that end soon ({@code order-ending-alerts})
     */
    record Filter(boolean deliveries, boolean completions, boolean ending) {

        static final Filter ALL = new Filter(true, true, true);
        static final Filter REFUNDS_ONLY = new Filter(false, false, false);

        /**
         * The filter of a player's settings: with the join summary off ({@code summary} false) only refunds show,
         * otherwise their delivery alerts and ending warnings decide.
         */
        static Filter of(boolean summary, DeliveryAlerts alerts, boolean endingAlerts) {
            return summary ? new Filter(alerts.everyDelivery(), alerts.completions(), endingAlerts) : REFUNDS_ONLY;
        }

        boolean shows(String kind) {
            return switch (kind) {
                case DELIVERED -> this.deliveries;
                case COMPLETE -> this.completions;
                case ENDING -> this.ending;
                default -> true;
            };
        }
    }

    NoticeSummary {
        details = List.copyOf(details);
        shown = List.copyOf(shown);
    }

    /** True when there is nothing to tell. */
    boolean empty() {
        return this.delivered == 0 && this.complete == 0 && this.refunded == 0 && this.details.isEmpty();
    }

    /**
     * Summarizes rows (newest first, as stored). Staff cancellations come first, then expiries, completions, ending
     * warnings and deliveries, each kind newest first.
     *
     * @param filter     which kinds the player wants to hear about
     * @param maxDetails detail lines to show at most
     */
    static NoticeSummary of(List<OrderStore.NoticeRow> rows, Filter filter, int maxDetails) {
        long delivered = 0;
        int complete = 0;
        long refunded = 0;
        List<OrderStore.NoticeRow> ordered = new ArrayList<>();
        for (String kind : List.of(CANCELLED, EXPIRED, COMPLETE, ENDING, DELIVERED)) {
            for (OrderStore.NoticeRow row : rows) {
                if (!row.kind().equals(kind)) {
                    continue;
                }
                if (!filter.shows(kind)) {
                    continue;
                }
                switch (kind) {
                    case DELIVERED -> delivered += row.units();
                    case COMPLETE -> complete++;
                    case CANCELLED, EXPIRED -> refunded += row.amount();
                    default -> {
                    }
                }
                ordered.add(row);
            }
        }
        int cap = Math.max(0, maxDetails);
        List<OrderStore.NoticeRow> details = ordered.size() <= cap ? ordered : ordered.subList(0, cap);
        return new NoticeSummary(delivered, complete, refunded, details, Math.max(0, ordered.size() - cap), rows);
    }
}
