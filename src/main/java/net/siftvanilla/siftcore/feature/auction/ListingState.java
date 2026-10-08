package net.siftvanilla.siftcore.feature.auction;

import java.util.Locale;

/**
 * The life of a listing. A listing is {@link #ACTIVE} until exactly one of the three closing transitions happens:
 * someone buys it ({@link #SOLD}), its time runs out ({@link #EXPIRED}) or the seller or staff take it down
 * ({@link #CANCELLED}). Closed listings never change again. Stored as the enum name in {@code auction_listings.state}.
 */
public enum ListingState {
    ACTIVE,
    SOLD,
    EXPIRED,
    CANCELLED;

    /** True for the three final states. */
    public boolean closed() {
        return this != ACTIVE;
    }

    /** Reads a stored state; throws {@link IllegalArgumentException} for anything unknown. */
    public static ListingState parse(String stored) {
        if (stored == null) {
            throw new IllegalArgumentException("No listing state");
        }
        return valueOf(stored.trim().toUpperCase(Locale.ROOT));
    }
}
