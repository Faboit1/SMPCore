package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Parsed {@code features/sell.yml}, including the worth table generated from it. Generating the table while
 * parsing means a reload either applies a complete, valid table or nothing at all.
 *
 * @param craftLoss         factor applied to every recipe step
 * @param recipeKinds       recipe families prices are derived from
 * @param multipliers       rank tier name to sell multiplier (each at least 1.0)
 * @param sellAllSkipUnstackable /sell all leaves items that do not stack (tools, weapons, armor)
 * @param sellAllSkipHotbar      /sell all leaves the hotbar alone
 * @param table             the generated worth table
 * @param recipes           the recipes the table was derived from
 * @param tableProblems     problems found while generating the table (also reported as config problems)
 */
public record SellSettings(
    double craftLoss,
    Set<RecipeDef.Kind> recipeKinds,
    Map<String, Double> multipliers,
    boolean sellAllSkipUnstackable,
    boolean sellAllSkipHotbar,
    WorthTable table,
    List<RecipeDef> recipes,
    List<String> tableProblems) {

    private static final String TIER = "[a-z0-9_-]{1,32}";

    public SellSettings {
        recipeKinds = Set.copyOf(recipeKinds);
        multipliers = Map.copyOf(multipliers);
        recipes = List.copyOf(recipes);
        tableProblems = List.copyOf(tableProblems);
    }

    /** The best multiplier any rank can have. */
    public double highestMultiplier() {
        return Multipliers.highest(this.multipliers);
    }

    /** The pricing view the shop validates against. */
    public Pricing pricing() {
        return new Pricing(this.table, highestMultiplier(), this.recipes);
    }

    public static SellSettings parse(ConfigReader r, ItemCatalog catalog, MoneyFormat money) {
        double craftLoss = r.decimal("craft-loss", 0.5, 1.0, 0.9);

        Set<RecipeDef.Kind> kinds = EnumSet.noneOf(RecipeDef.Kind.class);
        List<String> kindNames = r.stringList("recipe-types", List.of());
        for (String name : kindNames) {
            RecipeDef.Kind kind = RecipeDef.Kind.byId(name.strip());
            if (kind == null) {
                r.problem("recipe-types", "contains '" + name + "'; use crafting, smelting, blasting, smoking, "
                    + "campfire, stonecutting or smithing");
            } else {
                kinds.add(kind);
            }
        }

        Map<String, Double> tiers = new TreeMap<>();
        ConfigReader multipliers = r.section("multipliers", false);
        for (String tier : multipliers.keys()) {
            double value = multipliers.decimal(tier, 1.0, 100.0, 1.0);
            if (!tier.matches(TIER)) {
                multipliers.problem(tier, "tier names are lowercase letters, digits, - or _ (they become the permission "
                    + Multipliers.NODE_PREFIX + "<tier>)");
                continue;
            }
            tiers.put(tier, value);
        }

        ConfigReader sellAll = r.section("sell-all");
        boolean skipUnstackable = sellAll.bool("skip-unstackable", true);
        boolean skipHotbar = sellAll.bool("skip-hotbar", false);

        Map<String, Long> base = prices(r.section("base-prices"), catalog, money, false);
        Map<String, Long> overrides = prices(r.section("overrides", false), catalog, money, true);

        List<RecipeDef> recipes = new ArrayList<>();
        for (RecipeDef recipe : catalog.recipes()) {
            if (kinds.contains(recipe.kind())) {
                recipes.add(recipe);
            }
        }
        WorthCalculator.Result result = WorthCalculator.calculate(base, overrides, recipes, craftLoss, money.maxAmount());
        for (String problem : result.problems()) {
            r.problem("base-prices", problem);
        }
        return new SellSettings(craftLoss, kinds, tiers, skipUnstackable, skipHotbar, result.table(), recipes,
            result.problems());
    }

    /**
     * Reads a price section. Keys are item ids ({@code diamond}, {@code minecraft:diamond}) or item tags
     * ({@code #minecraft:logs}); an item listed by id wins over every tag, and when two tags price the same item
     * the lower price is used.
     */
    static Map<String, Long> prices(ConfigReader section, ItemCatalog catalog, MoneyFormat money, boolean allowZero) {
        Map<String, Long> byItem = new LinkedHashMap<>();
        Map<String, Long> byTag = new HashMap<>();
        Map<String, String> seen = new HashMap<>();
        for (String raw : section.keys()) {
            long value = section.money(raw, money, allowZero, -1);
            if (value < 0) {
                continue;
            }
            if (raw.startsWith("#")) {
                String tag = ItemKeys.normalize(raw.substring(1));
                Set<String> members = tag == null ? null : catalog.tag(tag);
                if (members == null) {
                    section.problem(raw, "is not an item tag of this Minecraft version");
                    continue;
                }
                if (members.isEmpty()) {
                    section.problem(raw, "is an empty item tag");
                    continue;
                }
                for (String member : members) {
                    byTag.merge(member, value, Math::min);
                }
                continue;
            }
            String item = ItemKeys.normalize(raw);
            if (item == null || !catalog.isItem(item)) {
                section.problem(raw, "is not an item of this Minecraft version");
                continue;
            }
            String previous = seen.putIfAbsent(item, raw);
            if (previous != null) {
                section.problem(raw, "lists " + item + " again (also listed as '" + previous + "')");
                continue;
            }
            byItem.put(item, value);
        }
        Map<String, Long> result = new HashMap<>(byTag);
        result.putAll(byItem);
        return result;
    }
}
