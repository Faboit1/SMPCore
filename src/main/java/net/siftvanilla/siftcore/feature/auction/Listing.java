package net.siftvanilla.siftcore.feature.auction;

import java.util.Objects;
import java.util.UUID;

/**
 * An active auction listing. Immutable: a listing is never edited, only closed (sold, expired or cancelled).
 *
 * @param id         unique id, never reused
 * @param seller     who listed it
 * @param item       the item payload ({@code ItemStack} on the server; any type in tests)
 * @param typeKey    the item type key, e.g. {@code minecraft:diamond_sword}
 * @param searchText lowercase text the search matches against (plain name and type)
 * @param category   the filter category
 * @param amount     how many items the stack holds
 * @param price      the price of the whole listing
 * @param created    when it was listed (epoch millis)
 * @param expires    when it expires (epoch millis)
 * @param <T>        the item payload type
 */
public record Listing<T>(long id, UUID seller, T item, String typeKey, String searchText, Category category,
                         int amount, long price, long created, long expires) {

    public Listing {
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(typeKey, "typeKey");
        Objects.requireNonNull(category, "category");
        searchText = searchText == null ? "" : searchText;
        if (id <= 0) {
            throw new IllegalArgumentException("Listing ids are positive, got " + id);
        }
        if (amount < 1) {
            throw new IllegalArgumentException("A listing holds at least one item, got " + amount);
        }
        if (price < 1) {
            throw new IllegalArgumentException("A listing costs at least 1, got " + price);
        }
        if (expires <= created) {
            throw new IllegalArgumentException("A listing must expire after it was created");
        }
    }

    /** True once its time ran out. */
    public boolean expired(long now) {
        return now >= this.expires;
    }

    /** Milliseconds until it expires, never negative. */
    public long millisLeft(long now) {
        return Math.max(0, this.expires - now);
    }

    /** The reference stored with its ledger rows and claim box deliveries. */
    public String ref() {
        return ref(this.id);
    }

    public static String ref(long id) {
        return "listing:" + id;
    }
}
