package net.siftvanilla.siftcore.feature.auction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory set of active listings: the auction house's domain state.
 * <p>
 * A listing is active exactly while it is in the book. {@link #check} validates a closing transition and
 * {@link #close} performs it by removing the listing, so a listing can be closed once: the first closing transaction
 * that passes its check under the economy lock wins and every later one finds the listing gone. Mutating methods are
 * only called from {@code LedgerTx} checks, applies and reverts (which run under the economy lock) or during startup;
 * reads are safe from any thread.
 * <p>
 * A new listing is <em>unsaved</em> until its row is committed. Unsaved listings occupy their seller's slot but
 * cannot be bought, cancelled or expired, so no closing transaction can ever depend on a row that might still be
 * rolled back.
 *
 * @param <T> the item payload type
 */
public final class ListingBook<T> {

    /**
     * A closing transition to validate. Each one leads from {@link ListingState#ACTIVE} to exactly one final state,
     * so the stored state always matches what happened.
     */
    public sealed interface Closing permits Sale, Cancellation, Expiry {

        /** The state the listing ends in. */
        ListingState result();
    }

    /** A purchase by {@code buyer} at the price the buyer confirmed. */
    public record Sale(UUID buyer, long expectedPrice, long now) implements Closing {
        public Sale {
            Objects.requireNonNull(buyer, "buyer");
        }

        @Override
        public ListingState result() {
            return ListingState.SOLD;
        }
    }

    /** A cancellation by the seller, or by staff when {@code by} is null. */
    public record Cancellation(UUID by) implements Closing {
        @Override
        public ListingState result() {
            return ListingState.CANCELLED;
        }
    }

    /** The end of the listing's time. */
    public record Expiry(long now) implements Closing {
        @Override
        public ListingState result() {
            return ListingState.EXPIRED;
        }
    }

    private final Map<Long, Listing<T>> listings = new ConcurrentHashMap<>();
    private final Set<Long> unsaved = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> perSeller = new ConcurrentHashMap<>();

    /** Adds a new listing. {@code saved} is false for listings whose row is not committed yet. */
    public void open(Listing<T> listing, boolean saved) {
        if (this.listings.putIfAbsent(listing.id(), listing) != null) {
            throw new IllegalStateException("Listing " + listing.id() + " is already open");
        }
        if (!saved) {
            this.unsaved.add(listing.id());
        }
        this.perSeller.merge(listing.seller(), 1, Integer::sum);
    }

    /** Marks a listing's row as committed, which makes it available to buyers, cancellation and expiry. */
    public void markSaved(long id) {
        this.unsaved.remove(id);
    }

    /**
     * Validates a closing transition. Returns null when it may happen, otherwise why not. Pure read; the caller
     * runs it under the economy lock right before {@link #close}.
     */
    public Refusal check(long id, Closing closing) {
        Listing<T> listing = this.listings.get(id);
        if (listing == null) {
            return Refusal.GONE;
        }
        if (this.unsaved.contains(id)) {
            return Refusal.PENDING;
        }
        return switch (closing) {
            case Sale sale -> {
                if (sale.buyer().equals(listing.seller())) {
                    yield Refusal.OWN_LISTING;
                }
                if (sale.expectedPrice() != listing.price()) {
                    yield Refusal.PRICE_CHANGED;
                }
                yield listing.expired(sale.now()) ? Refusal.EXPIRED : null;
            }
            case Cancellation cancellation -> cancellation.by() == null || cancellation.by().equals(listing.seller())
                ? null : Refusal.NOT_OWNER;
            case Expiry expiry -> listing.expired(expiry.now()) ? null : Refusal.NOT_EXPIRED;
        };
    }

    /** Closes (removes) a listing. Returns it, or null when it was already closed. */
    public Listing<T> close(long id) {
        Listing<T> removed = this.listings.remove(id);
        if (removed == null) {
            return null;
        }
        this.unsaved.remove(id);
        this.perSeller.computeIfPresent(removed.seller(), (seller, count) -> count <= 1 ? null : count - 1);
        return removed;
    }

    /**
     * Undoes {@link #close} after its transaction could not be stored. The listing was saved before it was closed
     * (unsaved listings cannot be closed), so it comes back saved.
     */
    public void reopen(Listing<T> listing) {
        if (this.listings.putIfAbsent(listing.id(), listing) == null) {
            this.perSeller.merge(listing.seller(), 1, Integer::sum);
        }
    }

    /** Undoes {@link #open} after the new listing's row could not be stored. */
    public void discard(long id) {
        close(id);
    }

    public Listing<T> get(long id) {
        return this.listings.get(id);
    }

    public boolean saved(long id) {
        return this.listings.containsKey(id) && !this.unsaved.contains(id);
    }

    /** Listings the seller has open, including unsaved ones (they hold a slot). */
    public int count(UUID seller) {
        return this.perSeller.getOrDefault(seller, 0);
    }

    public int size() {
        return this.listings.size();
    }

    public int unsavedCount() {
        return this.unsaved.size();
    }

    /** Number of different sellers with open listings. */
    public int sellers() {
        return this.perSeller.size();
    }

    /** A snapshot of every open listing (unordered). */
    public List<Listing<T>> all() {
        return List.copyOf(this.listings.values());
    }

    /** A snapshot of the listings buyers can see: every saved listing that has not expired yet. */
    public List<Listing<T>> available(long now) {
        List<Listing<T>> result = new ArrayList<>(this.listings.size());
        for (Listing<T> listing : this.listings.values()) {
            if (!listing.expired(now) && !this.unsaved.contains(listing.id())) {
                result.add(listing);
            }
        }
        return result;
    }

    /** A snapshot of one seller's listings, including unsaved ones. */
    public List<Listing<T>> of(UUID seller) {
        List<Listing<T>> result = new ArrayList<>();
        for (Listing<T> listing : this.listings.values()) {
            if (listing.seller().equals(seller)) {
                result.add(listing);
            }
        }
        return result;
    }

    /** Saved listings whose time ran out. */
    public List<Listing<T>> due(long now) {
        List<Listing<T>> result = new ArrayList<>();
        for (Listing<T> listing : this.listings.values()) {
            if (listing.expired(now) && !this.unsaved.contains(listing.id())) {
                result.add(listing);
            }
        }
        return result;
    }

    /** The earliest expiry among open listings, or {@link Long#MAX_VALUE} when there are none. */
    public long nextExpiry() {
        long next = Long.MAX_VALUE;
        for (Listing<T> listing : this.listings.values()) {
            next = Math.min(next, listing.expires());
        }
        return next;
    }

    /** Removes everything (startup reload only). */
    public void clear() {
        this.listings.clear();
        this.unsaved.clear();
        this.perSeller.clear();
    }
}
