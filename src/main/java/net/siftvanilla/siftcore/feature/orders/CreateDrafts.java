package net.siftvanilla.siftcore.feature.orders;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * What a player typed into the new-order form while they pick an item in a chest menu or a list, so leaving the form
 * never loses input. Kept for a short time per player and forgotten when they quit. Pure apart from the clock.
 */
final class CreateDrafts {

    /** How long a draft is kept. */
    static final long TTL_MILLIS = 10 * 60 * 1000L;

    /**
     * A form in progress.
     *
     * @param itemKey  the chosen order key (plain or variant), or null when none was chosen in a picker
     * @param itemText what the item field shows (the chosen item's name, or what the player typed)
     * @param quantity what the player typed as the quantity
     * @param price    what the player typed as the price each
     */
    record Draft(String itemKey, String itemText, String quantity, String price) {
        static final Draft EMPTY = new Draft(null, "", "", "");

        Draft {
            itemText = itemText == null ? "" : itemText;
            quantity = quantity == null ? "" : quantity;
            price = price == null ? "" : price;
        }

        Draft withItem(String key, String text) {
            return new Draft(key, text, this.quantity, this.price);
        }

        Draft withTyped(String text, String newQuantity, String newPrice) {
            return new Draft(text.equals(this.itemText) ? this.itemKey : null, text, newQuantity, newPrice);
        }
    }

    private record Entry(Draft draft, long saved) {
    }

    private final Map<UUID, Entry> drafts = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    CreateDrafts(LongSupplier clock) {
        this.clock = clock;
    }

    /** The player's draft, or {@link Draft#EMPTY} when none is kept (or it is too old). */
    Draft get(UUID player) {
        Entry entry = this.drafts.get(player);
        if (entry == null) {
            return Draft.EMPTY;
        }
        if (this.clock.getAsLong() - entry.saved() > TTL_MILLIS) {
            this.drafts.remove(player, entry);
            return Draft.EMPTY;
        }
        return entry.draft();
    }

    void put(UUID player, Draft draft) {
        this.drafts.put(player, new Entry(draft, this.clock.getAsLong()));
    }

    void forget(UUID player) {
        this.drafts.remove(player);
    }

    /** Drops drafts older than the time they are kept. */
    void sweep() {
        long now = this.clock.getAsLong();
        this.drafts.values().removeIf(entry -> now - entry.saved() > TTL_MILLIS);
    }
}
