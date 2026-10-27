package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Rank sell multipliers. A tier {@code vip: 1.1} is granted by {@code siftcore.sell.multiplier.vip}; a player with
 * several tiers gets the highest one, and a player with none sells at 1.0. Pure logic.
 */
public final class Multipliers {

    public static final String NODE_PREFIX = "siftcore.sell.multiplier.";

    private Multipliers() {
    }

    /** The permission node of a tier. */
    public static String node(String tier) {
        return NODE_PREFIX + tier;
    }

    /**
     * The highest multiplier among the tiers {@code granted} accepts (tested by tier name), or 1.0.
     * Tiers are tried from the best down and the first granted one wins, so permission checks stay few.
     */
    public static double select(Map<String, Double> tiers, Predicate<String> granted) {
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(tiers.entrySet());
        ordered.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
            .thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<String, Double> tier : ordered) {
            if (tier.getValue() <= 1.0) {
                break;
            }
            if (granted.test(tier.getKey())) {
                return tier.getValue();
            }
        }
        return 1.0;
    }

    /** The best multiplier anyone can have (at least 1.0); the shop prices itself above it. */
    public static double highest(Map<String, Double> tiers) {
        double best = 1.0;
        for (double value : tiers.values()) {
            best = Math.max(best, value);
        }
        return best;
    }

    /** A multiplier for display: {@code 1.5}, {@code 1.25}, {@code 2}. */
    public static String format(double multiplier) {
        if (!Double.isFinite(multiplier)) {
            return "1";
        }
        BigDecimal value = BigDecimal.valueOf(multiplier).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }
}
