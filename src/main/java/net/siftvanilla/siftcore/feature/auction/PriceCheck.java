package net.siftvanilla.siftcore.feature.auction;

import java.math.BigInteger;
import java.util.List;

/**
 * The low price warning of the listing confirmation ({@code auction-price-warning}). Pure, so it is unit tested; the
 * dialog gathers what /sell pays and the similar listings. A warning never blocks the listing.
 */
final class PriceCheck {

    /** A listing asking less than this share of the cheapest similar listing's price per item is "far below" it. */
    static final int FAR_BELOW_PERCENT = 50;

    private PriceCheck() {
    }

    /** True when the price for the whole listing is below what /sell pays for the same items ({@code sellValue}, 0: unknown). */
    static boolean belowSell(long price, long sellValue) {
        return sellValue > 0 && price < sellValue;
    }

    /**
     * True when {@code price} for {@code amount} items is less than {@link #FAR_BELOW_PERCENT}% of
     * {@code otherPrice} for {@code otherAmount} per item. Exact for any amounts.
     */
    static boolean farBelow(long price, int amount, long otherPrice, int otherAmount) {
        if (amount < 1 || otherAmount < 1 || otherPrice < 1) {
            return false;
        }
        BigInteger mine = BigInteger.valueOf(price).multiply(BigInteger.valueOf(otherAmount)).multiply(BigInteger.valueOf(100));
        BigInteger theirs = BigInteger.valueOf(otherPrice).multiply(BigInteger.valueOf(amount)).multiply(BigInteger.valueOf(FAR_BELOW_PERCENT));
        return mine.compareTo(theirs) < 0;
    }

    /** The listing with the lowest price per item (the first of equals), or null when there is none. */
    static <T> Listing<T> cheapest(List<Listing<T>> listings) {
        Listing<T> best = null;
        for (Listing<T> listing : listings) {
            if (best == null || cheaper(listing, best)) {
                best = listing;
            }
        }
        return best;
    }

    private static boolean cheaper(Listing<?> a, Listing<?> b) {
        BigInteger left = BigInteger.valueOf(a.price()).multiply(BigInteger.valueOf(b.amount()));
        BigInteger right = BigInteger.valueOf(b.price()).multiply(BigInteger.valueOf(a.amount()));
        return left.compareTo(right) < 0;
    }

    /** The price of one item, rounded down (for "from $X each" lines). */
    static long each(long price, int amount) {
        return amount < 1 ? price : price / amount;
    }
}
