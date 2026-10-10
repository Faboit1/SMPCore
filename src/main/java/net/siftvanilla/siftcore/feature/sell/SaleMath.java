package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact money math for sales. Pure logic; every overflow is reported, never wrapped. */
public final class SaleMath {

    private SaleMath() {
    }

    /** Adds {@code unit * amount} to {@code total}; throws {@link ArithmeticException} on overflow. */
    public static long add(long total, long unit, long amount) {
        if (unit < 0 || amount < 0) {
            throw new IllegalArgumentException("Prices and amounts are never negative");
        }
        return Math.addExact(total, Math.multiplyExact(unit, amount));
    }

    /**
     * What a player receives for items worth {@code base} with their {@code multiplier}: {@code base * multiplier}
     * rounded down to whole dollars, computed exactly (no floating point drift on large totals).
     *
     * @throws ArithmeticException if the result does not fit in a long
     */
    public static long withMultiplier(long base, double multiplier) {
        if (!Double.isFinite(multiplier)) {
            throw new IllegalArgumentException("A multiplier is at least 1.0, got " + multiplier);
        }
        return withMultiplier(base, BigDecimal.valueOf(multiplier));
    }

    /** {@link #withMultiplier(long, double)} with an exact multiplier (rank plus mastery bonus). */
    public static long withMultiplier(long base, BigDecimal multiplier) {
        if (base < 0) {
            throw new IllegalArgumentException("A sale total is never negative");
        }
        if (multiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("A multiplier is at least 1.0, got " + multiplier);
        }
        if (base == 0) {
            return 0;
        }
        return BigDecimal.valueOf(base).multiply(multiplier)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact();
    }

    /** What the server pays for one unit worth {@code worth} at {@code multiplier}, exactly (not rounded). */
    public static BigDecimal unit(long worth, BigDecimal multiplier) {
        return BigDecimal.valueOf(worth).multiply(multiplier);
    }
}
