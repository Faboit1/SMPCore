package net.siftvanilla.siftcore.feature.auction;

import java.util.Locale;

/** The auction house filter categories. Every listing is in exactly one; see {@link ItemCategories} for the rules. */
public enum Category {
    BLOCKS,
    TOOLS,
    COMBAT,
    FOOD,
    POTIONS,
    BOOKS,
    SPAWNERS,
    MISC;

    /** Stable lowercase id, stored in {@code auction_listings.category} and used in lang keys. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The category with this id, or null when the id is unknown. */
    public static Category byId(String id) {
        if (id == null) {
            return null;
        }
        for (Category category : values()) {
            if (category.id().equals(id.trim().toLowerCase(Locale.ROOT))) {
                return category;
            }
        }
        return null;
    }
}
