package net.siftvanilla.siftcore.feature.shards;

import java.util.OptionalLong;

/**
 * One thing the shard shop sells.
 *
 * @param id          stable id from features/shards.yml (used in purchase references)
 * @param kind        crate keys or an item
 * @param name        what the offer is called (plain text); empty for an item means the item's own name
 * @param description one line under the name (plain text, may be empty)
 * @param target      the crate id for keys, the item id ({@code minecraft:totem_of_undying}) for items
 * @param amount      keys or items one unit of the offer gives
 * @param price       shards for one unit
 * @param max         most units per purchase
 * @param permission  needed to see and buy it, or empty for everyone
 * @param order       where it is listed, lower first
 */
record ShardOffer(String id, Kind kind, String name, String description, String target, int amount, long price, int max,
                  String permission, int order) {

    /** Where an offer without an order is listed: after the shipped ones. */
    static final int DEFAULT_ORDER = 100;

    /** An offer listed at the default place. */
    ShardOffer(String id, Kind kind, String name, String description, String target, int amount, long price, int max,
               String permission) {
        this(id, kind, name, description, target, amount, price, max, permission, DEFAULT_ORDER);
    }

    /** What an offer gives. */
    enum Kind {
        KEY("key"),
        ITEM("item");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        String id() {
            return this.id;
        }
    }

    /** The shards {@code units} cost, or empty if that is more than a long holds. */
    OptionalLong total(int units) {
        try {
            return OptionalLong.of(Math.multiplyExact(this.price, (long) units));
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /** How many keys or items {@code units} give. */
    long given(int units) {
        return (long) this.amount * units;
    }

    /** The amount the purchase dialog starts at: one unit. */
    static int clampUnits(long wanted, int max) {
        if (wanted < 1) {
            return 1;
        }
        return (int) Math.min(wanted, max);
    }
}
