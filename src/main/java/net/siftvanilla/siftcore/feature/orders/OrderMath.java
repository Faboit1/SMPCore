package net.siftvanilla.siftcore.feature.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money arithmetic of orders. Pure and exact: every result is a whole amount, nothing can overflow silently. */
final class OrderMath {

    /** 100% in basis points. */
    static final int FULL = 10_000;

    private OrderMath() {
    }

    /** {@code quantity * priceEach}, or -1 when it does not fit in a long. */
    static long total(long quantity, long priceEach) {
        if (quantity < 0 || priceEach < 0) {
            throw new IllegalArgumentException("Negative quantity or price");
        }
        try {
            return Math.multiplyExact(quantity, priceEach);
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    /**
     * The tax on {@code paid}, rounded down so the seller is never charged a fraction:
     * {@code floor(paid * basisPoints / 10000)}, computed without overflow for any non-negative amount.
     */
    static long tax(long paid, int basisPoints) {
        if (paid < 0 || basisPoints < 0 || basisPoints > FULL) {
            throw new IllegalArgumentException("Bad tax input " + paid + " at " + basisPoints);
        }
        return paid / FULL * basisPoints + paid % FULL * basisPoints / FULL;
    }

    /** What the seller keeps of {@code paid} after tax. */
    static long payout(long paid, int basisPoints) {
        return paid - tax(paid, basisPoints);
    }

    /** What the seller keeps per item before rounding: {@code priceEach * (1 - tax)}, exact. */
    static BigDecimal netEach(long priceEach, int basisPoints) {
        return BigDecimal.valueOf(priceEach).multiply(BigDecimal.valueOf(FULL - basisPoints))
            .divide(BigDecimal.valueOf(FULL), 4, RoundingMode.UNNECESSARY);
    }

    /** {@code worth * multiplier}, exact (the multiplier as written in the config, e.g. 1.5). */
    static BigDecimal serverEach(long worth, double multiplier) {
        if (worth <= 0 || !Double.isFinite(multiplier) || multiplier <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(worth).multiply(BigDecimal.valueOf(multiplier));
    }

    /** True when the server pays at least as much per item as the order leaves the seller after tax. */
    static boolean serverPaysMore(long worth, double multiplier, long priceEach, int basisPoints) {
        return worth > 0 && serverEach(worth, multiplier).compareTo(netEach(priceEach, basisPoints)) >= 0;
    }

    /**
     * The price each to suggest so that sellers get {@code margin} more than the server pays after the tax:
     * {@code ceil(worth * (1 + margin) / (1 - tax))}. 0 when the item has no worth.
     */
    static long suggestedPrice(long worth, int marginBasisPoints, int taxBasisPoints) {
        if (worth <= 0 || taxBasisPoints >= FULL) {
            return 0;
        }
        BigDecimal top = BigDecimal.valueOf(worth).multiply(BigDecimal.valueOf(FULL + (long) marginBasisPoints));
        BigDecimal bottom = BigDecimal.valueOf(FULL - (long) taxBasisPoints);
        BigDecimal result = top.divide(bottom, 0, RoundingMode.CEILING);
        return result.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? 0 : result.longValueExact();
    }

    /** {@code worth * percent / 100}, rounded up (a floor price each), 0 when off. */
    static long floorFromWorth(long worth, int percentBasisPoints) {
        if (worth <= 0 || percentBasisPoints <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(worth).multiply(BigDecimal.valueOf(percentBasisPoints))
            .divide(BigDecimal.valueOf(FULL), 0, RoundingMode.CEILING).longValueExact();
    }

    /** {@code worth * multiple}, rounded down (a ceiling price each), 0 when off. */
    static long ceilingFromWorth(long worth, double multiple) {
        if (worth <= 0 || !(multiple > 0) || !Double.isFinite(multiple)) {
            return 0;
        }
        BigDecimal value = BigDecimal.valueOf(worth).multiply(BigDecimal.valueOf(multiple)).setScale(0, RoundingMode.FLOOR);
        return value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : value.longValueExact();
    }

    /**
     * The extra money an edit holds: {@code (newQuantity - filled) * newPrice - (quantity - filled) * price}, or -1 when
     * it does not fit in a long.
     */
    static long editExtra(int quantity, int filled, long price, int newQuantity, long newPrice) {
        try {
            long after = Math.multiplyExact((long) newQuantity - filled, newPrice);
            long before = Math.multiplyExact((long) quantity - filled, price);
            return Math.subtractExact(after, before);
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    /** A percentage such as 2.5 as basis points (250), rounded to the nearest basis point. */
    static int basisPoints(double percent) {
        if (!Double.isFinite(percent) || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Percent out of range: " + percent);
        }
        return (int) Math.round(percent * 100);
    }

    /** A basis point value as a short percent text: 200 is {@code 2}, 250 is {@code 2.5}, 5 is {@code 0.05}. */
    static String percent(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString();
    }
}
