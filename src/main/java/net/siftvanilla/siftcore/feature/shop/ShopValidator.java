package net.siftvanilla.siftcore.feature.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.feature.sell.ItemKeys;
import net.siftvanilla.siftcore.feature.sell.Multipliers;
import net.siftvanilla.siftcore.feature.sell.Pricing;
import net.siftvanilla.siftcore.feature.sell.RecipeDef;
import net.siftvanilla.siftcore.feature.sell.WorthTable;

/**
 * Keeps the shop from ever paying out: buying an item and selling it back, or buying it and crafting it into
 * something that sells, must always cost more than it returns, even for the rank with the best sell multiplier while
 * the largest server sell booster allowed runs ({@link Pricing#guardMultiplier()}). Pure logic.
 * <p>
 * For every item it computes the most one unit can be turned into ("liquidation value"): its own sell price, or
 * the sell value of anything a recipe makes from it (following chains of recipes), minus what the other
 * ingredients of those recipes cost. An ingredient costs what the player gives up by using it: its sell price when
 * it sells, otherwise what making it from its cheapest recipe costs; items that neither sell nor come from a recipe
 * (flowers, ores) cost nothing. Smelting fuel is ignored. Every choice errs on the side of a higher value. A shop
 * price must be more than that value times the highest multiplier (with the largest booster) times {@link #MARGIN}.
 */
public final class ShopValidator {

    /** How far above the best possible sale a shop price must be. */
    public static final BigDecimal MARGIN = new BigDecimal("1.1");
    /** Upper bound on passes when following recipe chains. */
    public static final int MAX_PASSES = 64;
    private static final double RELATIVE_EPSILON = 1e-9;

    /**
     * The most one unit of an item can be sold for, directly or after crafting.
     *
     * @param value  dollars per unit, before any multiplier
     * @param via    the item it is best turned into (the item itself when selling it directly is best)
     * @param recipe the recipe of the first crafting step, or null when selling directly is best
     */
    public record Liquidation(double value, String via, String recipe) {
    }

    /**
     * Liquidation values of every item in the pricing.
     *
     * @param values   item key to its liquidation value; items absent from it are worth nothing
     * @param settled  false when recipe chains kept raising values (a loop that multiplies value), in which case
     *                 the values are a lower bound and every shop price is suspect
     * @param loopItems when not settled, items whose value was still rising
     */
    public record Analysis(Map<String, Liquidation> values, boolean settled, List<String> loopItems) {

        public Liquidation of(String item) {
            return this.values.get(item);
        }
    }

    private ShopValidator() {
    }

    /** The lowest whole price that is more than {@code value * multiplier * 1.1}. */
    public static long minimumPrice(double value, double multiplier) {
        if (!(value > 0)) {
            return 1;
        }
        BigDecimal limit = new BigDecimal(value).multiply(BigDecimal.valueOf(multiplier)).multiply(MARGIN);
        return Math.addExact(limit.setScale(0, RoundingMode.FLOOR).longValueExact(), 1);
    }

    /** Computes liquidation values for every item in {@code pricing}. */
    public static Analysis analyze(Pricing pricing) {
        WorthTable table = pricing.table();
        Map<String, Liquidation> values = new HashMap<>();
        table.entries().forEach((item, entry) -> values.put(item, new Liquidation(entry.price(), item, null)));
        List<RecipeDef> recipes = new ArrayList<>(pricing.recipes());
        recipes.sort(Comparator.comparing(RecipeDef::id).thenComparing(RecipeDef::output));
        Map<String, Double> costs = costs(table, recipes);
        boolean settled = false;
        List<String> rising = new ArrayList<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            rising.clear();
            for (RecipeDef recipe : recipes) {
                Liquidation out = values.get(recipe.output());
                if (out == null || out.value() <= 0) {
                    continue;
                }
                double outValue = out.value() * recipe.outputCount();
                List<RecipeDef.Ingredient> ingredients = recipe.ingredients();
                double[] cheapest = new double[ingredients.size()];
                double others = 0;
                for (int g = 0; g < ingredients.size(); g++) {
                    cheapest[g] = cheapestCost(costs, ingredients.get(g));
                    others += cheapest[g] * ingredients.get(g).count();
                }
                for (int g = 0; g < ingredients.size(); g++) {
                    RecipeDef.Ingredient ingredient = ingredients.get(g);
                    double rest = others - cheapest[g] * ingredient.count();
                    double candidate = (outValue - rest) / ingredient.count();
                    if (!(candidate > 0)) {
                        continue;
                    }
                    for (String option : ingredient.options()) {
                        Liquidation current = values.get(option);
                        double now = current == null ? 0 : current.value();
                        if (candidate > now * (1 + RELATIVE_EPSILON) && candidate - now > 1e-9) {
                            values.put(option, new Liquidation(candidate, out.via(), recipe.id()));
                            rising.add(option);
                        }
                    }
                }
            }
            if (rising.isEmpty()) {
                settled = true;
                break;
            }
        }
        List<String> loop = settled ? List.of() : rising.stream().distinct().sorted().toList();
        return new Analysis(Map.copyOf(values), settled, loop);
    }

    /**
     * What a player gives up by using one of each item in a recipe. An item that sells costs its sell price (using
     * it means not selling it, however it was obtained). An item that can't be sold costs what making it from its
     * cheapest recipe costs (no loss factor). Items that can't be sold and that no recipe makes (flowers, ores,
     * flint) cost nothing and are absent from the map, like items whose recipes can't be costed.
     */
    static Map<String, Double> costs(WorthTable table, List<RecipeDef> recipes) {
        Map<String, Double> costs = new HashMap<>();
        table.entries().forEach((item, entry) -> costs.put(item, (double) entry.price()));
        Set<String> sellable = Set.copyOf(costs.keySet());
        Set<String> made = new HashSet<>();
        for (RecipeDef recipe : recipes) {
            made.add(recipe.output());
        }
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (RecipeDef recipe : recipes) {
                if (sellable.contains(recipe.output())) {
                    continue;
                }
                double total = 0;
                boolean known = true;
                for (RecipeDef.Ingredient ingredient : recipe.ingredients()) {
                    double best = Double.POSITIVE_INFINITY;
                    for (String option : ingredient.options()) {
                        Double cost = costs.get(option);
                        if (cost == null && !made.contains(option)) {
                            cost = 0.0;
                        }
                        if (cost != null) {
                            best = Math.min(best, cost);
                        }
                    }
                    if (best == Double.POSITIVE_INFINITY) {
                        known = false;
                        break;
                    }
                    total += best * ingredient.count();
                }
                if (!known) {
                    continue;
                }
                double candidate = total / recipe.outputCount();
                Double current = costs.get(recipe.output());
                if (current == null || (candidate < current * (1 - RELATIVE_EPSILON) && current - candidate > 1e-9)) {
                    costs.put(recipe.output(), candidate);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return costs;
    }

    private static double cheapestCost(Map<String, Double> costs, RecipeDef.Ingredient ingredient) {
        double best = Double.POSITIVE_INFINITY;
        for (String option : ingredient.options()) {
            best = Math.min(best, costs.getOrDefault(option, 0.0));
        }
        return best == Double.POSITIVE_INFINITY ? 0 : best;
    }

    /**
     * Checks one shop price. Returns null when it is safe, otherwise a precise explanation with the lowest safe
     * price.
     */
    public static String check(String item, long price, Pricing pricing, Analysis analysis) {
        double multiplier = pricing.guardMultiplier();
        long worth = pricing.table().price(item);
        String bonus = Multipliers.format(multiplier) + "x with the best sell bonus"
            + (pricing.highestBoost() > 0 ? " and a +" + pricing.highestBoost() + "% sell booster" : "");
        if (worth > 0) {
            long minimum = minimumPrice(worth, multiplier);
            if (price < minimum) {
                return "costs " + price + " but sells back for " + worth + " each (" + bonus
                    + "), so buying and selling it would pay out; the price must be at least " + minimum;
            }
        }
        Liquidation liquidation = analysis.of(item);
        if (liquidation != null && liquidation.recipe() != null && liquidation.value() > worth) {
            long minimum = minimumPrice(liquidation.value(), multiplier);
            if (price < minimum) {
                return "costs " + price + " but can be crafted into " + ItemKeys.shortKey(liquidation.via())
                    + " (recipe " + liquidation.recipe() + "), which sells for more (" + bonus
                    + "); the price must be at least " + minimum + ", or lower the worth of "
                    + ItemKeys.shortKey(liquidation.via());
            }
        }
        if (!analysis.settled()) {
            return "can't be priced safely: crafting " + String.join(", ", analysis.loopItems())
                + " into each other keeps gaining value; fix their base prices or overrides in features/sell.yml";
        }
        return null;
    }
}
