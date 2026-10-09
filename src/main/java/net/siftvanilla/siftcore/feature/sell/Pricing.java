package net.siftvanilla.siftcore.feature.sell;

import java.util.List;

/**
 * What the server pays for items, as the shop needs it to price itself safely: the worth table, the best sell
 * multiplier any rank can have, the largest server sell booster allowed, and the recipes that can turn one item into
 * another.
 *
 * @param table             the worth table
 * @param highestMultiplier the highest configured sell multiplier (rank plus mastery), at least 1.0
 * @param highestBoost      the largest sell booster allowed, in percent (0 without boosters)
 * @param recipes           the recipes the table was derived from
 */
public record Pricing(WorthTable table, double highestMultiplier, int highestBoost, List<RecipeDef> recipes) {

    public static final Pricing EMPTY = new Pricing(WorthTable.EMPTY, 1.0, List.of());

    public Pricing {
        if (!(highestMultiplier >= 1.0)) {
            throw new IllegalArgumentException("The highest multiplier is at least 1.0");
        }
        if (highestBoost < 0) {
            throw new IllegalArgumentException("A booster never lowers prices");
        }
        recipes = List.copyOf(recipes);
    }

    /** Pricing without boosters. */
    public Pricing(WorthTable table, double highestMultiplier, List<RecipeDef> recipes) {
        this(table, highestMultiplier, 0, recipes);
    }

    /** The same pricing with the largest booster allowed. */
    public Pricing withBoost(int percent) {
        return percent == this.highestBoost ? this : new Pricing(this.table, this.highestMultiplier, percent, this.recipes);
    }

    /**
     * The most a player can be paid per unit of worth: the best multiplier with the largest booster on top. What the
     * shop's arbitrage check prices against.
     */
    public double guardMultiplier() {
        return Boosts.guard(this.highestMultiplier, this.highestBoost);
    }

    /** Pricing as seen by the shop: the current table and, during a reload, the one about to be applied. */
    public interface Source {

        /** The pricing in effect now. */
        Pricing current();

        /**
         * The most recently parsed pricing. Config files are parsed in registration order and the sell and booster
         * configs are registered before the shop's, so while {@code /sift reload} validates the shop this is the
         * pricing that will apply together with it. At any other time it equals {@link #current()} or a parse that was
         * rejected.
         */
        Pricing latest();
    }
}
