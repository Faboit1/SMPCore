package net.siftvanilla.siftcore.feature.auction;

import java.util.Locale;

/**
 * Why an auction transaction was refused by its domain checks. The id travels as the
 * {@link net.siftvanilla.siftcore.api.economy.TransactionResult#reason()} of a rejected transaction.
 */
public enum Refusal {
    /** The listing is no longer active (sold, expired, cancelled or never existed). */
    GONE,
    /** The listing was just created and is not stored yet. */
    PENDING,
    /** Buyers cannot buy their own listing. */
    OWN_LISTING,
    /** The price differs from the one the buyer confirmed. */
    PRICE_CHANGED,
    /** The listing ran out of time. */
    EXPIRED,
    /** Only the seller (or staff) can cancel a listing. */
    NOT_OWNER,
    /** An expiry was attempted before the listing's time ran out. */
    NOT_EXPIRED,
    /** The seller has no free listing slot. */
    SLOTS_FULL;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The refusal with this id, or null for reasons that are not auction refusals. */
    public static Refusal from(String reason) {
        if (reason == null) {
            return null;
        }
        for (Refusal refusal : values()) {
            if (refusal.id().equals(reason)) {
                return refusal;
            }
        }
        return null;
    }
}
