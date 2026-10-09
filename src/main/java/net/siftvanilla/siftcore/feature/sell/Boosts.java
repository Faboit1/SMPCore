package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The math of server sell boosters. A booster of {@code p} percent multiplies what a sale pays by {@code 1 + p/100}, on
 * top of the player's own multiplier (rank plus mastery): a $400 diamond at 1.25x with a +10% booster pays
 * {@code 400 x 1.25 x 1.1 = $550}. Exact decimal math, so a booster raises a payout by exactly its percent before the
 * usual rounding down. Pure logic.
 */
public final class Boosts {

    private Boosts() {
    }

    /** {@code 1 + percent/100}, exactly: 10 becomes 1.10. */
    public static BigDecimal factor(int percent) {
        if (percent < 0) {
            throw new IllegalArgumentException("A booster never lowers prices, got " + percent + "%");
        }
        return BigDecimal.ONE.add(BigDecimal.valueOf(percent, 2));
    }

    /** A multiplier with a booster on top. */
    public static BigDecimal apply(BigDecimal multiplier, int percent) {
        return percent == 0 ? multiplier : multiplier.multiply(factor(percent));
    }

    /** The multiplier before a booster ({@link #apply} undone), for showing a player their own bonus. */
    public static BigDecimal remove(BigDecimal boosted, int percent) {
        return percent == 0 ? boosted : boosted.divide(factor(percent), MathContext.DECIMAL64).stripTrailingZeros();
    }

    /**
     * The most anyone can be paid per unit of worth: the best multiplier anyone can reach with the largest booster
     * allowed on top. The shop prices itself above this, so buying and selling back never pays, even while a booster
     * runs.
     */
    public static double guard(double highestMultiplier, int maxPercent) {
        return BigDecimal.valueOf(highestMultiplier).multiply(factor(maxPercent)).doubleValue();
    }
}
