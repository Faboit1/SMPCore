package net.siftvanilla.siftcore.feature.orders;

/**
 * Order keys: the exact thing an order takes, as one string. A plain item is its type key
 * ({@code minecraft:diamond}); a variant appends {@code |} and the variant id
 * ({@code minecraft:enchanted_book|enchant:minecraft:mending:1}). Pure.
 */
final class OrderKeys {

    static final char SEPARATOR = '|';

    /** An order key split into its parts. */
    record Parts(String itemType, String variant) {
    }

    private OrderKeys() {
    }

    static String key(String itemType, String variant) {
        return variant == null ? itemType : itemType + SEPARATOR + variant;
    }

    /** The parts of a key; the variant is null for plain items. */
    static Parts parse(String key) {
        int separator = key.indexOf(SEPARATOR);
        if (separator < 0) {
            return new Parts(key, null);
        }
        return new Parts(key.substring(0, separator), key.substring(separator + 1));
    }

    /** The item type part of a key. */
    static String itemType(String key) {
        return parse(key).itemType();
    }
}
