package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the worth table from base prices and recipes. Pure and deterministic (same input, same table).
 * <p>
 * An item without a base price is worth its cheapest recipe: the ingredients (each slot at its cheapest priced
 * option) divided by how many the recipe makes, times the craft-loss factor, rounded down to whole dollars.
 * Ingredients count at their whole-dollar price (an item worth less than a dollar counts as zero), so crafting from
 * priced items can never turn them into more money than selling them, even after rounding. The math is exact
 * decimal arithmetic, never floating point.
 * <p>
 * Items are priced in dependency order: everything an item is made from is settled before the item itself.
 * Recipes can form loops (an ingot and its block, a shulker box re-dyed to any colour, a template that copies
 * itself). The items of one loop are priced in layers: first those a recipe makes from items outside the loop (or
 * from base-priced members), then those one recipe away from them, and so on, each item taking the first layer that
 * can price it. So prices never chain around a loop: every colour of shulker box is priced from the plain box, and
 * the loss factor is not applied again and again by going round and round. Base prices are fixed inputs and are
 * never replaced by a recipe; overrides are applied last and do not change other items.
 */
public final class WorthCalculator {

    private static final long UNKNOWN = -1L;

    /**
     * The calculation result.
     *
     * @param table    the generated table
     * @param problems precise problems found (empty when everything is fine)
     * @param passes   the most layers any loop of recipes needed (1 when there are no loops)
     */
    public record Result(WorthTable table, List<String> problems, int passes) {
    }

    private record Compiled(String id, int output, BigDecimal outputCount, int[][] options, long[] counts) {
    }

    private record Candidate(long price, double exact, int recipe) {
    }

    private WorthCalculator() {
    }

    /**
     * @param base      base prices by item key (each at least 1)
     * @param overrides final prices by item key; 0 makes an item unsellable
     * @param recipes   recipes to derive prices from
     * @param craftLoss factor applied to every recipe step, in (0, 1]
     * @param maxPrice  the highest price any item may have (the money limit)
     */
    public static Result calculate(Map<String, Long> base, Map<String, Long> overrides, List<RecipeDef> recipes,
                                   double craftLoss, long maxPrice) {
        if (!(craftLoss > 0 && craftLoss <= 1)) {
            throw new IllegalArgumentException("craft-loss must be in (0, 1], got " + craftLoss);
        }
        BigDecimal loss = BigDecimal.valueOf(craftLoss);
        BigDecimal limit = BigDecimal.valueOf(maxPrice);
        List<RecipeDef> sorted = new ArrayList<>(recipes);
        sorted.sort(Comparator.comparing(RecipeDef::id).thenComparing(RecipeDef::output));
        TreeSet<String> universe = new TreeSet<>();
        universe.addAll(base.keySet());
        universe.addAll(overrides.keySet());
        for (RecipeDef recipe : sorted) {
            universe.add(recipe.output());
            for (RecipeDef.Ingredient ingredient : recipe.ingredients()) {
                universe.addAll(ingredient.options());
            }
        }
        String[] names = universe.toArray(new String[0]);
        Map<String, Integer> index = new HashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++) {
            index.put(names[i], i);
        }

        int count = names.length;
        long[] price = new long[count];
        Arrays.fill(price, UNKNOWN);
        double[] exact = new double[count];
        boolean[] fixed = new boolean[count];
        int[] via = new int[count];
        Arrays.fill(via, -1);
        base.forEach((item, value) -> {
            if (value < 1) {
                throw new IllegalArgumentException("Base price of " + item + " must be at least 1");
            }
            int i = index.get(item);
            price[i] = value;
            exact[i] = value;
            fixed[i] = true;
        });

        Compiled[] compiled = compile(sorted, index);
        List<List<Integer>> byOutput = new ArrayList<>(count);
        List<Set<Integer>> uses = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byOutput.add(new ArrayList<>());
            uses.add(new LinkedHashSet<>());
        }
        for (int r = 0; r < compiled.length; r++) {
            Compiled recipe = compiled[r];
            byOutput.get(recipe.output()).add(r);
            for (int[] group : recipe.options()) {
                for (int option : group) {
                    uses.get(option).add(recipe.output());
                }
            }
        }
        int[][] successors = new int[count][];
        for (int i = 0; i < count; i++) {
            successors[i] = uses.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        int[] component = components(count, successors);
        int components = 0;
        for (int c : component) {
            components = Math.max(components, c + 1);
        }
        List<List<Integer>> members = new ArrayList<>(components);
        for (int c = 0; c < components; c++) {
            members.add(new ArrayList<>());
        }
        for (int i = 0; i < count; i++) {
            members.get(component[i]).add(i);
        }

        Set<Integer> overLimit = new HashSet<>();
        int passes = 1;
        // Which members of the loop being priced may be used as ingredients (only read for that loop's members).
        boolean[] usable = new boolean[count];
        // A component completes after everything made from it, so the highest number holds the most basic items.
        for (int c = components - 1; c >= 0; c--) {
            List<Integer> pending = new ArrayList<>();
            for (int item : members.get(c)) {
                usable[item] = fixed[item];
                if (!fixed[item]) {
                    pending.add(item);
                }
            }
            int layers = 0;
            while (!pending.isEmpty()) {
                Map<Integer, Candidate> found = new HashMap<>();
                for (int item : pending) {
                    Candidate best = null;
                    for (int r : byOutput.get(item)) {
                        Candidate candidate = cost(compiled[r], r, price, component, c, usable, loss, limit, overLimit);
                        if (candidate != null && (best == null || candidate.price() < best.price())) {
                            best = candidate;
                        }
                    }
                    if (best != null) {
                        found.put(item, best);
                    }
                }
                if (found.isEmpty()) {
                    break;
                }
                layers++;
                for (Map.Entry<Integer, Candidate> entry : found.entrySet()) {
                    int item = entry.getKey();
                    price[item] = entry.getValue().price();
                    exact[item] = entry.getValue().exact();
                    via[item] = entry.getValue().recipe();
                    usable[item] = true;
                }
                pending.removeAll(found.keySet());
            }
            passes = Math.max(passes, layers);
        }

        List<String> problems = new ArrayList<>();
        Map<String, WorthTable.Entry> prices = new HashMap<>();
        Map<String, Double> belowOne = new HashMap<>();
        for (int i = 0; i < count; i++) {
            if (price[i] == UNKNOWN) {
                if (overLimit.contains(i) && !overrides.containsKey(names[i])) {
                    problems.add("the derived price of " + names[i] + " is above the money limit of " + maxPrice
                        + "; lower the base prices it is made from or override it");
                }
                continue;
            }
            if (fixed[i]) {
                prices.put(names[i], new WorthTable.Entry(price[i], WorthTable.Origin.BASE, null));
            } else if (price[i] < 1) {
                belowOne.put(names[i], exact[i]);
            } else {
                prices.put(names[i], new WorthTable.Entry(price[i], WorthTable.Origin.DERIVED, compiled[via[i]].id()));
            }
        }
        Set<String> unsellable = new HashSet<>();
        overrides.forEach((item, value) -> {
            belowOne.remove(item);
            if (value <= 0) {
                prices.remove(item);
                unsellable.add(item);
            } else {
                prices.put(item, new WorthTable.Entry(value, WorthTable.Origin.OVERRIDE, null));
            }
        });
        return new Result(new WorthTable(prices, unsellable, belowOne, base), List.copyOf(problems), passes);
    }

    /**
     * What one recipe prices its output at, or null when it can't: an ingredient slot has no priced option it may
     * use. Options outside the output's loop are usable when priced; options inside it only once {@code usable}.
     */
    private static Candidate cost(Compiled recipe, int r, long[] price, int[] component, int loop, boolean[] usable,
                                  BigDecimal loss, BigDecimal limit, Set<Integer> overLimit) {
        BigDecimal total = BigDecimal.ZERO;
        for (int g = 0; g < recipe.options().length; g++) {
            long best = Long.MAX_VALUE;
            for (int option : recipe.options()[g]) {
                long value = price[option];
                if (value == UNKNOWN || (component[option] == loop && !usable[option])) {
                    continue;
                }
                best = Math.min(best, value);
            }
            if (best == Long.MAX_VALUE) {
                return null;
            }
            total = total.add(BigDecimal.valueOf(best).multiply(BigDecimal.valueOf(recipe.counts()[g])));
        }
        BigDecimal value = total.multiply(loss).divide(recipe.outputCount(), 6, RoundingMode.FLOOR);
        BigDecimal whole = value.setScale(0, RoundingMode.FLOOR);
        if (whole.compareTo(limit) > 0) {
            overLimit.add(recipe.output());
            return null;
        }
        return new Candidate(whole.longValueExact(), value.doubleValue(), r);
    }

    /**
     * Strongly connected components of the graph "ingredient to what it makes" (Tarjan's algorithm, iterative). A
     * component gets its number when it completes, which is after every component reachable from it, so items are
     * numbered after the things they are made into.
     */
    static int[] components(int count, int[][] successors) {
        int[] order = new int[count];
        Arrays.fill(order, -1);
        int[] low = new int[count];
        boolean[] onStack = new boolean[count];
        int[] component = new int[count];
        Arrays.fill(component, -1);
        int[] stack = new int[count];
        int stackSize = 0;
        int[] frameNode = new int[count];
        int[] frameEdge = new int[count];
        int next = 0;
        int components = 0;
        for (int start = 0; start < count; start++) {
            if (order[start] != -1) {
                continue;
            }
            int depth = 0;
            frameNode[0] = start;
            frameEdge[0] = 0;
            order[start] = next;
            low[start] = next;
            next++;
            stack[stackSize++] = start;
            onStack[start] = true;
            while (depth >= 0) {
                int node = frameNode[depth];
                int[] edges = successors[node];
                if (frameEdge[depth] < edges.length) {
                    int target = edges[frameEdge[depth]++];
                    if (order[target] == -1) {
                        order[target] = next;
                        low[target] = next;
                        next++;
                        stack[stackSize++] = target;
                        onStack[target] = true;
                        depth++;
                        frameNode[depth] = target;
                        frameEdge[depth] = 0;
                    } else if (onStack[target]) {
                        low[node] = Math.min(low[node], order[target]);
                    }
                    continue;
                }
                if (low[node] == order[node]) {
                    int member;
                    do {
                        member = stack[--stackSize];
                        onStack[member] = false;
                        component[member] = components;
                    } while (member != node);
                    components++;
                }
                depth--;
                if (depth >= 0) {
                    int parent = frameNode[depth];
                    low[parent] = Math.min(low[parent], low[node]);
                }
            }
        }
        return component;
    }

    private static Compiled[] compile(List<RecipeDef> recipes, Map<String, Integer> index) {
        Compiled[] compiled = new Compiled[recipes.size()];
        for (int r = 0; r < recipes.size(); r++) {
            RecipeDef recipe = recipes.get(r);
            List<RecipeDef.Ingredient> ingredients = recipe.ingredients();
            int[][] options = new int[ingredients.size()][];
            long[] counts = new long[ingredients.size()];
            for (int g = 0; g < ingredients.size(); g++) {
                RecipeDef.Ingredient ingredient = ingredients.get(g);
                List<String> keys = ingredient.options();
                int[] indexes = new int[keys.size()];
                for (int o = 0; o < keys.size(); o++) {
                    indexes[o] = index.get(keys.get(o));
                }
                options[g] = indexes;
                counts[g] = ingredient.count();
            }
            compiled[r] = new Compiled(recipe.id(), index.get(recipe.output()), BigDecimal.valueOf(recipe.outputCount()),
                options, counts);
        }
        return compiled;
    }
}
