package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.List;

/**
 * The "while you were away" summary of an owner's notices. Pure: the server side renders it.
 * <p>
 * Deliveries, completions and ending warnings respect the player's order messages setting; refunds of ended orders
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
     * @param notifications the player's order messages setting
     * @param maxDetails    detail lines to show at most
     */
    static NoticeSummary of(List<OrderStore.NoticeRow> rows, boolean notifications, int maxDetails) {
        long delivered = 0;
        int complete = 0;
        long refunded = 0;
        List<OrderStore.NoticeRow> ordered = new ArrayList<>();
        for (String kind : List.of(CANCELLED, EXPIRED, COMPLETE, ENDING, DELIVERED)) {
            for (OrderStore.NoticeRow row : rows) {
                if (!row.kind().equals(kind)) {
                    continue;
                }
                boolean always = kind.equals(CANCELLED) || kind.equals(EXPIRED);
                if (!always && !notifications) {
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
