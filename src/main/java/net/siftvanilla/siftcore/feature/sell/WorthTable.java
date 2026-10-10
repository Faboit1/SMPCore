package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The generated worth table: what the server pays for one of each item, keyed by item key
 * ({@code minecraft:diamond}). Immutable, so it is safe to read from any thread; a reload swaps in a new table.
 */
public final class WorthTable {

    /** Where a price came from. */
    public enum Origin {
        /** Configured in {@code base-prices}. */
        BASE,
        /** Computed from the cheapest recipe. */
        DERIVED,
        /** Set in {@code overrides}. */
        OVERRIDE
    }

    /**
     * A sellable item's price.
     *
     * @param price    whole dollars paid for one item, at least 1
     * @param origin   how the price was made
     * @param recipe   for derived prices, the recipe it was computed from; otherwise null
     * @param category the sell category (listed, or taken from the most valuable ingredient)
     */
    public record Entry(long price, Origin origin, String recipe, String category) {
        public Entry {
            if (price < 1) {
                throw new IllegalArgumentException("A sellable price is at least 1, got " + price);
            }
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(category, "category");
        }

        /** An entry in the fallback category. */
        public Entry(long price, Origin origin, String recipe) {
            this(price, origin, recipe, SellCategories.OTHER);
        }
    }

    public static final WorthTable EMPTY = new WorthTable(Map.of(), Set.of(), Map.of(), Map.of());

    private final Map<String, Entry> prices;
    private final Set<String> unsellable;
    private final Map<String, Double> belowOne;
    private final Map<String, Long> base;
    private final Map<String, BigDecimal> asIngredient;

    /**
     * @param prices     sellable items and their prices
     * @param unsellable items an override made unsellable
     * @param belowOne   derived items worth less than one dollar (not sellable), with their exact value
     * @param base       the configured base prices after tags were expanded
     */
    public WorthTable(Map<String, Entry> prices, Set<String> unsellable, Map<String, Double> belowOne, Map<String, Long> base) {
        this(prices, unsellable, belowOne, base, Map.of());
    }

    /**
     * @param asIngredient what each item is worth when a recipe uses it (its sell price, or for an item priced from
     *                     a recipe at least that recipe's ingredients per item before the loss), for the no-gain check
     */
    public WorthTable(Map<String, Entry> prices, Set<String> unsellable, Map<String, Double> belowOne, Map<String, Long> base,
                      Map<String, BigDecimal> asIngredient) {
        this.prices = Map.copyOf(prices);
        this.unsellable = Set.copyOf(unsellable);
        this.belowOne = Map.copyOf(belowOne);
        this.base = Map.copyOf(base);
        this.asIngredient = Map.copyOf(asIngredient);
    }

    /** What items are worth as recipe ingredients (absent: nothing), as the no-gain check values them. */
    public Map<String, BigDecimal> asIngredient() {
        return this.asIngredient;
    }

    /** The sellable items as an unsorted, unmodifiable map (cheap; {@link #entries()} sorts). */
    public Map<String, Entry> prices() {
        return this.prices;
    }

    /** What one item sells for, 0 when it can't be sold. */
    public long price(String item) {
        Entry entry = this.prices.get(item);
        return entry == null ? 0L : entry.price();
    }

    /** The entry of a sellable item, or null. */
    public Entry entry(String item) {
        return this.prices.get(item);
    }

    public boolean sellable(String item) {
        return this.prices.containsKey(item);
    }

    /** Every sellable item, sorted by key. */
    public Map<String, Entry> entries() {
        return new TreeMap<>(this.prices);
    }

    /** Items an override turned off, sorted. */
    public Set<String> unsellable() {
        return new TreeSet<>(this.unsellable);
    }

    /** Derived items worth less than one dollar, sorted, with their exact value. */
    public Map<String, Double> belowOne() {
        return new TreeMap<>(this.belowOne);
    }

    /** The configured base prices after tag expansion. */
    public Map<String, Long> base() {
        return this.base;
    }

    public int size() {
        return this.prices.size();
    }

    /** The sell category of a sellable item, or null when it can't be sold. */
    public String category(String item) {
        Entry entry = this.prices.get(item);
        return entry == null ? null : entry.category();
    }

    /** Number of sellable items per category. */
    public Map<String, Integer> categorySizes() {
        Map<String, Integer> sizes = new TreeMap<>();
        for (Entry entry : this.prices.values()) {
            sizes.merge(entry.category(), 1, Integer::sum);
        }
        return sizes;
    }

    /** Number of prices by origin, for logs. */
    public long count(Origin origin) {
        return this.prices.values().stream().filter(e -> e.origin() == origin).count();
    }
}
