package net.siftvanilla.siftcore.core.item;

import java.util.Locale;

/**
 * The kinds of item the auction house, the worth browser and orders filter by. Every item type is in exactly one;
 * see {@link ItemCategories} for the rules.
 */
public enum ItemCategory {
    BLOCKS,
    TOOLS,
    COMBAT,
    FOOD,
    POTIONS,
    BOOKS,
    SPAWNERS,
    MISC;

    /** Stable lowercase id, stored in {@code auction_listings.category} and used in lang keys and settings. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The category with this id, or null when the id is unknown. */
    public static ItemCategory byId(String id) {
        if (id == null) {
            return null;
        }
        for (ItemCategory category : values()) {
            if (category.id().equals(id.trim().toLowerCase(Locale.ROOT))) {
                return category;
            }
        }
        return null;
    }
}
