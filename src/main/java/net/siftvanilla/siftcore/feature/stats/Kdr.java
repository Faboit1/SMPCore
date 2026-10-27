package net.siftvanilla.siftcore.feature.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Kill/death ratio: kills divided by deaths, where a player with no deaths counts as having one (so 7 kills and no
 * deaths is 7.00, not infinite). Shown with exactly two decimals, rounded half up. Leaderboards compare ratios
 * exactly by cross-multiplying, never through rounded or floating values.
 */
public final class Kdr {

    private Kdr() {
    }

    /** The divisor actually used: at least one. */
    public static long divisor(long deaths) {
        return Math.max(1, deaths);
    }

    /** The ratio as a double (for sorting hints only; use {@link #compare} for exact order). */
    public static double value(long kills, long deaths) {
        return (double) Math.max(0, kills) / divisor(deaths);
    }

    /** The ratio with exactly two decimals, e.g. {@code 1.50} or {@code 7.00}. */
    public static String format(long kills, long deaths) {
        return BigDecimal.valueOf(Math.max(0, kills))
            .divide(BigDecimal.valueOf(divisor(deaths)), 2, RoundingMode.HALF_UP)
            .toPlainString();
    }

    /**
     * Compares two ratios exactly: negative when {@code a} is the smaller ratio. Kills and deaths are stored as
     * 32-bit numbers, so the cross products fit in a long; larger inputs fall back to exact big-number math.
     */
    public static int compare(long killsA, long deathsA, long killsB, long deathsB) {
        long da = divisor(deathsA);
        long db = divisor(deathsB);
        long ka = Math.max(0, killsA);
        long kb = Math.max(0, killsB);
        try {
            return Long.compare(Math.multiplyExact(ka, db), Math.multiplyExact(kb, da));
        } catch (ArithmeticException overflow) {
            return BigDecimal.valueOf(ka).multiply(BigDecimal.valueOf(db))
                .compareTo(BigDecimal.valueOf(kb).multiply(BigDecimal.valueOf(da)));
        }
    }
}
