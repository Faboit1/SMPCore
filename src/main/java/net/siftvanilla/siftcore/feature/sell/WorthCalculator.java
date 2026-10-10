package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Builds the worth table from base prices and recipes. Pure and deterministic (same input, same table).
 * <p>
 * An item without a base price is worth its cheapest recipe: the ingredients (each slot at its cheapest priced
 * option) divided by how many the recipe makes, times the craft-loss factor, rounded down to whole dollars.
 * Ingredients count at their whole-dollar price (an item worth less than a dollar counts as zero), so crafting from
 * priced items can never turn them into more money than selling them, even after rounding. The math is exact
 * decimal arithmetic, never floating point.
 * <p>
 * When every recipe of an item rounds down to $0 at whole-dollar ingredient prices because some ingredients are
 * worth less than a dollar each (sticks, slabs), those ingredients count at their exact value instead and the item
 * takes its cheapest recipe if that is worth at least a dollar (an armor stand from six sticks and a smooth stone
 * slab is $2). It is never priced above its cheapest recipe, so this can't make crafting pay.
 * <p>
 * Items are priced in dependency order: everything an item is made from is settled before the item itself.
 * Recipes can form loops (an ingot and its block, a shulker box re-dyed to any colour, a template that copies
 * itself). The items of one loop are priced in layers: first those a recipe makes from items outside the loop (or
 * from base-priced members), then those one recipe away from them, and so on, each item taking the first layer that
 * can price it. So prices never chain around a loop: every colour of shulker box is priced from the plain box, and
 * the loss factor is not applied again and again by going round and round. Base prices are fixed inputs and are
 * never replaced by a recipe; overrides are applied last and do not change other items.
 * <p>
 * Every item also gets a sell category: the one that lists it, else (for a price from a recipe) the category of the
 * recipe's most valuable ingredient, else the fallback category.
 * <p>
 * Finally the table is checked for recipes that gain value ({@link #gains}): a crafting, stonecutting or smithing
 * recipe whose results sell for more than its ingredients is reported as a problem, which refuses the table. Each
 * ingredient counts at its cheapest option that is worth something (like the pricing, options that neither sell nor
 * are made from anything that sells are left out; a slot of only such options leaves the recipe unjudged). That
 * catches a base price or override set above what an item is made from, and recipes that copy their own ingredient
 * (a smithing template priced above what copying it costs). Furnace-type recipes may add value on purpose (raw iron
 * smelts into a more valuable ingot): they cost fuel and time.
 */
public final class WorthCalculator {

    private static final long UNKNOWN = -1L;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000001");
    /** Recipe kinds that must never gain value. */
    static final Set<RecipeDef.Kind> NO_GAIN_KINDS = EnumSet.of(RecipeDef.Kind.CRAFTING, RecipeDef.Kind.STONECUTTING,
        RecipeDef.Kind.SMITHING);

    /**
     * The calculation result.
     *
     * @param table    the generated table
     * @param problems precise problems found (empty when everything is fine)
     * @param passes   the most layers any loop of recipes needed (1 when there are no loops)
     */
    public record Result(WorthTable table, List<String> problems, int passes) {
    }

    private record Compiled(String id, RecipeDef.Kind kind, int output, BigDecimal outputCount, int[][] options,
                            long[] counts) {
    }

    /**
     * One recipe's price for its output.
     *
     * @param price        whole dollars (0 when below one)
     * @param exact        the value before rounding down, for the generated file
     * @param undiscounted the ingredients' value per item made, without the loss factor
     * @param recipe       the recipe index
     * @param via          the item index of the most valuable ingredient (its category is inherited)
     */
    private record Candidate(long price, double exact, BigDecimal undiscounted, int recipe, int via) {
    }

    private WorthCalculator() {
    }

    /** {@link #calculate(Map, Map, List, double, long, Function)} with every item in the fallback category. */
    public static Result calculate(Map<String, Long> base, Map<String, Long> overrides, List<RecipeDef> recipes,
                                   double craftLoss, long maxPrice) {
        return calculate(base, overrides, recipes, craftLoss, maxPrice, item -> null);
    }

    /**
     * @param base      base prices by item key (each at least 1)
     * @param overrides final prices by item key; 0 makes an item unsellable
     * @param recipes   recipes to derive prices from
     * @param craftLoss factor applied to every recipe step, in (0, 1]
     * @param maxPrice  the highest price any item may have (the money limit)
     * @param listed    the category that lists an item, or null when none does
     */
    public static Result calculate(Map<String, Long> base, Map<String, Long> overrides, List<RecipeDef> recipes,
                                   double craftLoss, long maxPrice, Function<String, String> listed) {
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
        // What an item counts for as an ingredient of a sub-dollar chain: its price, or its exact value below $1.
        BigDecimal[] fractional = new BigDecimal[count];
        BigDecimal[] undiscounted = new BigDecimal[count];
        boolean[] fixed = new boolean[count];
        String[] category = new String[count];
        int[] via = new int[count];
        Arrays.fill(via, -1);
        base.forEach((item, value) -> {
            if (value < 1) {
                throw new IllegalArgumentException("Base price of " + item + " must be at least 1");
            }
            int i = index.get(item);
            price[i] = value;
            exact[i] = value;
            fractional[i] = BigDecimal.valueOf(value);
            fixed[i] = true;
        });
        for (int i = 0; i < count; i++) {
            String listedCategory = listed.apply(names[i]);
            if (listedCategory != null) {
                category[i] = listedCategory;
            } else if (fixed[i]) {
                category[i] = SellCategories.OTHER;
            }
        }

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
                    Candidate cheapestExact = null;
                    for (int r : byOutput.get(item)) {
                        Candidate whole = cost(compiled[r], r, price, fractional, component, c, usable, loss, limit,
                            overLimit, false);
                        if (whole != null && (best == null || whole.price() < best.price())) {
                            best = whole;
                        }
                        Candidate partial = cost(compiled[r], r, price, fractional, component, c, usable, loss, limit,
                            overLimit, true);
                        if (partial != null && (cheapestExact == null || partial.exact() < cheapestExact.exact())) {
                            cheapestExact = partial;
                        }
                    }
                    // Every recipe rounds to $0 at whole-dollar ingredient prices: count ingredients worth less than a
                    // dollar at their exact value instead, and use the cheapest recipe if that is worth a dollar.
                    if (best != null && best.price() == 0 && cheapestExact != null
                        && (cheapestExact.price() >= 1 || cheapestExact.exact() > best.exact())) {
                        // Below a dollar either way, the exact value is what the cheapest recipe is really worth.
                        best = cheapestExact;
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
                    Candidate candidate = entry.getValue();
                    price[item] = candidate.price();
                    exact[item] = candidate.exact();
                    fractional[item] = candidate.price() >= 1 ? BigDecimal.valueOf(candidate.price())
                        : BigDecimal.valueOf(candidate.exact());
                    undiscounted[item] = candidate.undiscounted();
                    via[item] = candidate.recipe();
                    if (category[item] == null) {
                        category[item] = candidate.via() >= 0 && category[candidate.via()] != null
                            ? category[candidate.via()] : SellCategories.OTHER;
                    }
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
            String itemCategory = category[i] == null ? SellCategories.OTHER : category[i];
            if (fixed[i]) {
                prices.put(names[i], new WorthTable.Entry(price[i], WorthTable.Origin.BASE, null, itemCategory));
            } else if (price[i] < 1) {
                belowOne.put(names[i], exact[i]);
            } else {
                prices.put(names[i], new WorthTable.Entry(price[i], WorthTable.Origin.DERIVED, compiled[via[i]].id(), itemCategory));
            }
        }
        Set<String> unsellable = new HashSet<>();
        overrides.forEach((item, value) -> {
            belowOne.remove(item);
            if (value <= 0) {
                prices.remove(item);
                unsellable.add(item);
            } else {
                Integer i = index.get(item);
                String itemCategory = i == null || category[i] == null ? SellCategories.OTHER : category[i];
                String listedCategory = listed.apply(item);
                prices.put(item, new WorthTable.Entry(value, WorthTable.Origin.OVERRIDE, null,
                    listedCategory != null ? listedCategory : itemCategory));
            }
        });

        // What each item is worth as an ingredient: its sell price, or (when its price came from a recipe) at least
        // what that recipe's ingredients are worth per item before the loss, so taking a block apart is never a gain.
        Map<String, BigDecimal> worthAsIngredient = new HashMap<>();
        for (int i = 0; i < count; i++) {
            BigDecimal value = BigDecimal.ZERO;
            WorthTable.Entry entry = prices.get(names[i]);
            if (entry != null) {
                value = BigDecimal.valueOf(entry.price());
            }
            if (!fixed[i] && undiscounted[i] != null && undiscounted[i].compareTo(value) > 0) {
                value = undiscounted[i];
            }
            if (value.signum() > 0) {
                worthAsIngredient.put(names[i], value);
            }
        }
        problems.addAll(gains(sorted, prices, worthAsIngredient));
        WorthTable table = new WorthTable(prices, unsellable, belowOne, base, worthAsIngredient);
        return new Result(table, List.copyOf(problems), passes);
    }

    /** The no-gain check of a parsed configuration, from its table and recipes (the self-test runs it again). */
    public static List<String> gains(SellSettings settings) {
        List<RecipeDef> sorted = new ArrayList<>(settings.recipes());
        sorted.sort(Comparator.comparing(RecipeDef::id).thenComparing(RecipeDef::output));
        return gains(sorted, settings.table().prices(), settings.table().asIngredient());
    }

    /**
     * Recipes that turn ingredients into results that sell for more (crafting, stonecutting and smithing only).
     *
     * @param recipes            the recipes in use
     * @param prices             the final sell prices
     * @param worthAsIngredient  what each item is worth as an ingredient (absent: nothing)
     * @return one problem per recipe that gains value
     */
    static List<String> gains(List<RecipeDef> recipes, Map<String, WorthTable.Entry> prices,
                              Map<String, BigDecimal> worthAsIngredient) {
        List<String> problems = new ArrayList<>();
        for (RecipeDef recipe : recipes) {
            if (!NO_GAIN_KINDS.contains(recipe.kind())) {
                continue;
            }
            WorthTable.Entry out = prices.get(recipe.output());
            if (out == null) {
                continue;
            }
            BigDecimal inputs = BigDecimal.ZERO;
            BigDecimal others = BigDecimal.ZERO;
            long selfCount = 0;
            boolean usable = true;
            for (RecipeDef.Ingredient ingredient : recipe.ingredients()) {
                if (ingredient.options().isEmpty()) {
                    usable = false;
                    break;
                }
                // Like the pricing itself, options that are worth nothing (they neither sell nor come from anything
                // that sells: stripped logs, a flower without a price) are left out; a slot with only such options
                // makes the recipe one this check can't judge.
                BigDecimal cheapest = null;
                for (String option : ingredient.options()) {
                    BigDecimal value = worthAsIngredient.get(option);
                    if (value != null && (cheapest == null || value.compareTo(cheapest) < 0)) {
                        cheapest = value;
                    }
                }
                if (cheapest == null) {
                    usable = false;
                    break;
                }
                BigDecimal part = cheapest.multiply(BigDecimal.valueOf(ingredient.count()));
                inputs = inputs.add(part);
                if (ingredient.options().size() == 1 && ingredient.options().getFirst().equals(recipe.output())) {
                    selfCount += ingredient.count();
                } else {
                    others = others.add(part);
                }
            }
            if (!usable) {
                continue;
            }
            BigDecimal output = BigDecimal.valueOf(out.price()).multiply(BigDecimal.valueOf(recipe.outputCount()));
            // Values per item are kept to 12 decimals; anything within a millionth of a dollar is equal.
            if (output.compareTo(inputs.add(TOLERANCE)) <= 0) {
                continue;
            }
            long most;
            if (selfCount > 0 && recipe.outputCount() > selfCount) {
                // A copy recipe: n made from a of themselves plus other ingredients. Safe while n*P <= a*P + others.
                most = others.divide(BigDecimal.valueOf(recipe.outputCount() - selfCount), 0, RoundingMode.FLOOR).longValueExact();
            } else {
                most = inputs.divide(BigDecimal.valueOf(recipe.outputCount()), 0, RoundingMode.FLOOR).longValueExact();
            }
            problems.add("the " + recipe.kind().id() + " recipe " + recipe.id() + " turns ingredients worth "
                + money(inputs) + " into " + recipe.outputCount() + " " + ItemKeys.shortKey(recipe.output())
                + " that sell for " + money(output) + "; " + ItemKeys.shortKey(recipe.output())
                + " may sell for at most " + most + " each, or its ingredients must be worth more");
        }
        return problems;
    }

    private static String money(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.FLOOR).stripTrailingZeros();
        return "$" + (rounded.scale() < 0 ? rounded.setScale(0).toPlainString() : rounded.toPlainString())
            .toLowerCase(Locale.ROOT);
    }

    /**
     * What one recipe prices its output at, or null when it can't: an ingredient slot has no priced option it may
     * use. Options outside the output's loop are usable when priced; options inside it only once {@code usable}.
     * With {@code partial}, ingredients worth less than a dollar count at their exact value instead of zero.
     */
    private static Candidate cost(Compiled recipe, int r, long[] price, BigDecimal[] fractional, int[] component, int loop,
                                  boolean[] usable, BigDecimal loss, BigDecimal limit, Set<Integer> overLimit,
                                  boolean partial) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal top = null;
        int topItem = -1;
        for (int g = 0; g < recipe.options().length; g++) {
            BigDecimal best = null;
            int bestItem = -1;
            for (int option : recipe.options()[g]) {
                long value = price[option];
                if (value == UNKNOWN || (component[option] == loop && !usable[option])) {
                    continue;
                }
                BigDecimal worth = partial ? fractional[option] : BigDecimal.valueOf(value);
                if (best == null || worth.compareTo(best) < 0) {
                    best = worth;
                    bestItem = option;
                }
            }
            if (best == null) {
                return null;
            }
            BigDecimal part = best.multiply(BigDecimal.valueOf(recipe.counts()[g]));
            total = total.add(part);
            if (top == null || part.compareTo(top) > 0) {
                top = part;
                topItem = bestItem;
            }
        }
        BigDecimal value = total.multiply(loss).divide(recipe.outputCount(), 6, RoundingMode.FLOOR);
        BigDecimal whole = value.setScale(0, RoundingMode.FLOOR);
        if (whole.compareTo(limit) > 0) {
            overLimit.add(recipe.output());
            return null;
        }
        BigDecimal perItem = total.divide(recipe.outputCount(), 12, RoundingMode.FLOOR);
        return new Candidate(whole.longValueExact(), value.doubleValue(), perItem, r, topItem);
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
            compiled[r] = new Compiled(recipe.id(), recipe.kind(), index.get(recipe.output()),
                BigDecimal.valueOf(recipe.outputCount()), options, counts);
        }
        return compiled;
    }
}
