package net.siftvanilla.siftcore.api.economy;

/** The two currencies. Both are whole numbers stored as {@code long}. */
public enum Currency {
    /** Money, earned by selling and trading, spent in shops. Shown as $10. */
    MONEY("money"),
    /** Shards, earned in the AFK zone and from rewards, spent in the shard shop. */
    SHARDS("shards");

    private final String id;

    Currency(String id) {
        this.id = id;
    }

    /** Stable lowercase id stored in the database. */
    public String id() {
        return this.id;
    }

    public static Currency byId(String id) {
        for (Currency currency : values()) {
            if (currency.id.equalsIgnoreCase(id)) {
                return currency;
            }
        }
        throw new IllegalArgumentException("Unknown currency " + id);
    }
}
