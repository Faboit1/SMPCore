package net.siftvanilla.siftcore.feature.auction;

import java.util.Comparator;
import java.util.Locale;

/** The auction house sort orders. Ties are broken by listing id (newest first) so the order is stable. */
public enum SortOrder {
    NEWEST,
    ENDING_SOON,
    LOWEST_PRICE,
    HIGHEST_PRICE;

    /** Stable id, used in config, the per-player setting and lang keys. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static SortOrder byId(String id) {
        if (id == null) {
            return null;
        }
        for (SortOrder order : values()) {
            if (order.id().equals(id.trim().toLowerCase(Locale.ROOT))) {
                return order;
            }
        }
        return null;
    }

    public <T> Comparator<Listing<T>> comparator() {
        Comparator<Listing<T>> newestFirst = Comparator.comparingLong((Listing<T> listing) -> listing.created()).reversed()
            .thenComparing(Comparator.comparingLong((Listing<T> listing) -> listing.id()).reversed());
        return switch (this) {
            case NEWEST -> newestFirst;
            case ENDING_SOON -> Comparator.comparingLong((Listing<T> listing) -> listing.expires()).thenComparing(newestFirst);
            case LOWEST_PRICE -> Comparator.comparingLong((Listing<T> listing) -> listing.price()).thenComparing(newestFirst);
            case HIGHEST_PRICE -> Comparator.comparingLong((Listing<T> listing) -> listing.price()).reversed().thenComparing(newestFirst);
        };
    }
}
