package net.siftvanilla.siftcore.feature.auction;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure arithmetic of the auction house: tax, percentages and price limits. Exact and overflow-safe. */
public final class AuctionMath {

    /** 100% in basis points. */
    public static final int FULL = 10_000;

    private AuctionMath() {
    }

    /** The tax on a sale: {@code price * basisPoints / 10000}, rounded down. Exact for every non-negative long. */
    public static long tax(long price, int basisPoints) {
        if (price < 0) {
            throw new IllegalArgumentException("Negative price " + price);
        }
        if (basisPoints < 0 || basisPoints > FULL) {
            throw new IllegalArgumentException("Tax must be between 0 and 10000 basis points, got " + basisPoints);
        }
        // Split so no intermediate product can overflow: price = q * 10000 + r.
        long whole = price / FULL;
        long rest = price % FULL;
        return whole * basisPoints + rest * basisPoints / FULL;
    }

    /** What the seller keeps after tax. */
    public static long proceeds(long price, int basisPoints) {
        return price - tax(price, basisPoints);
    }

    /**
     * Parses a percentage such as {@code 5}, {@code 5%}, {@code 2.5} or {@code 0.25%} into basis points.
     *
     * @throws IllegalArgumentException with a short reason when it is not 0 to 100 with at most two decimals
     */
    public static int parsePercent(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("is empty");
        }
        String trimmed = text.trim();
        if (trimmed.endsWith("%")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        BigDecimal value;
        try {
            value = new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("is not a percentage");
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("must be between 0 and 100");
        }
        try {
            return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("may have at most two decimals");
        }
    }

    /** Formats basis points as a percentage without trailing zeros: {@code 5%}, {@code 2.5%}, {@code 0.25%}. */
    public static String formatPercent(int basisPoints) {
        BigDecimal value = BigDecimal.valueOf(basisPoints).movePointLeft(2).stripTrailingZeros();
        return (value.scale() < 0 ? value.setScale(0, RoundingMode.UNNECESSARY) : value).toPlainString() + "%";
    }

    /** {@code a * b} for non-negative values, or {@link Long#MAX_VALUE} when it would overflow. */
    static long saturatedMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        if (high != 0 || low < 0) {
            return Long.MAX_VALUE;
        }
        return low;
    }

    /**
     * Price limits of a listing.
     *
     * @param minimum        the lowest price of a whole listing (at least 1)
     * @param maximum        the highest price of a whole listing
     * @param minimumPerItem the lowest price per item, 0 for none
     * @param maximumPerItem the highest price per item, 0 for none
     */
    public record PriceRules(long minimum, long maximum, long minimumPerItem, long maximumPerItem) {

        public PriceRules {
            if (minimum < 1 || maximum < minimum || minimumPerItem < 0 || maximumPerItem < 0) {
                throw new IllegalArgumentException("Invalid price rules " + minimum + ".." + maximum
                    + " per item " + minimumPerItem + ".." + maximumPerItem);
            }
        }

        /** The lowest allowed price for a listing of {@code amount} items. */
        public long lowest(int amount) {
            return Math.max(this.minimum, saturatedMultiply(this.minimumPerItem, Math.max(1, amount)));
        }

        /** The highest allowed price for a listing of {@code amount} items. */
        public long highest(int amount) {
            long perItem = this.maximumPerItem == 0 ? Long.MAX_VALUE : saturatedMultiply(this.maximumPerItem, Math.max(1, amount));
            return Math.min(this.maximum, perItem);
        }

        /** True when {@code price} is allowed for a listing of {@code amount} items. */
        public boolean allows(long price, int amount) {
            long low = lowest(amount);
            long high = highest(amount);
            return low <= high && price >= low && price <= high;
        }
    }
}
