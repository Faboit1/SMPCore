package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Parsed {@code features/sell.yml}, including the worth table generated from it. Generating the table while
 * parsing means a reload either applies a complete, valid table or nothing at all. Keys added after the first
 * release are optional and fall back to their documented defaults, so older files keep working.
 *
 * @param craftLoss         factor applied to every recipe step
 * @param recipeKinds       recipe families prices are derived from
 * @param multipliers       rank tier name to sell multiplier (each at least 1.0)
 * @param sellAll           how {@code /sell all} (and {@code /sell hand all} and category selling) behaves
 * @param shulkerContents   the sell menu and {@code /sell hand} sell what is inside shulker boxes
 * @param bundleContents    the sell menu and {@code /sell hand} sell what is inside bundles
 * @param blockInCombat     combat-tagged players can't sell
 * @param markTrades        items from villager and wandering trader trades are marked so they can't be sold
 * @param actionBarTotal    a successful sale also shows {@code +$total} on the action bar
 * @param categories        the sell categories
 * @param mastery           sell mastery levels and bonus
 * @param topRefresh        how often the top sellers are read from storage
 * @param table             the generated worth table
 * @param recipes           the recipes the table was derived from
 * @param tableProblems     problems found while generating the table (also reported as config problems)
 */
public record SellSettings(
    double craftLoss,
    Set<RecipeDef.Kind> recipeKinds,
    Map<String, Double> multipliers,
    SellAll sellAll,
    boolean shulkerContents,
    boolean bundleContents,
    boolean blockInCombat,
    boolean markTrades,
    boolean actionBarTotal,
    SellCategories categories,
    Mastery mastery,
    Duration topRefresh,
    WorthTable table,
    List<RecipeDef> recipes,
    List<String> tableProblems) {

    private static final String TIER = "[a-z0-9_-]{1,32}";

    /** When selling everything asks first. */
    public enum Confirm {
        /** Always asks, whatever the player's setting. */
        ALWAYS,
        /** Asks when the total is at least {@code confirm-above} and the player didn't turn asking off. */
        ABOVE,
        /** Never asks. */
        NEVER
    }

    /**
     * {@code sell-all} options.
     *
     * @param skipUnstackable leave items that don't stack (tools, armor) where they are
     * @param skipHotbar      leave the hotbar alone
     * @param confirm         when to ask before selling
     * @param confirmAbove    the total from which {@link Confirm#ABOVE} asks
     * @param shulkerContents also sell what is inside shulker boxes (never the box itself)
     */
    public record SellAll(boolean skipUnstackable, boolean skipHotbar, Confirm confirm, long confirmAbove,
                          boolean shulkerContents) {

        public static final SellAll DEFAULT = new SellAll(true, false, Confirm.ABOVE, 10_000, true);

        /** Whether a sale of {@code total} asks first, for a player who has asking {@code on} or off. */
        public boolean asks(long total, boolean on) {
            return switch (this.confirm) {
                case ALWAYS -> true;
                case NEVER -> false;
                case ABOVE -> on && total >= this.confirmAbove;
            };
        }
    }

    public SellSettings {
        recipeKinds = Set.copyOf(recipeKinds);
        multipliers = Map.copyOf(multipliers);
        recipes = List.copyOf(recipes);
        tableProblems = List.copyOf(tableProblems);
    }

    /** The best rank multiplier (at least 1.0). */
    public double highestRankMultiplier() {
        return Multipliers.highest(this.multipliers);
    }

    /** The best multiplier anyone can have: the best rank plus the highest mastery bonus. */
    public double highestMultiplier() {
        return BigDecimal.valueOf(highestRankMultiplier()).add(this.mastery.maxBonus()).doubleValue();
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

        SellAll sellAll = sellAll(r.section("sell-all"), money);
        boolean shulkerContents = optionalBool(r, "shulker-contents", true);
        boolean bundleContents = optionalBool(r, "bundle-contents", false);
        boolean blockInCombat = optionalBool(r, "block-in-combat", true);
        boolean markTrades = optionalBool(r, "mark-villager-trades", true);
        boolean actionBar = r.has("feedback") && optionalBool(r.section("feedback"), "action-bar", false);
        Duration topRefresh = Duration.ofMinutes(5);
        if (r.has("top")) {
            ConfigReader top = r.section("top");
            if (top.has("refresh")) {
                topRefresh = top.duration("refresh", Duration.ofSeconds(30), Duration.ofHours(24), Duration.ofMinutes(5));
            }
        }

        SellCategories categories = SellCategories.parse(r, catalog);
        Mastery mastery = Mastery.parse(r, money);

        Map<String, Long> base = prices(r.section("base-prices"), catalog, money, false);
        Map<String, Long> overrides = prices(r.section("overrides", false), catalog, money, true);

        List<RecipeDef> recipes = new ArrayList<>();
        for (RecipeDef recipe : catalog.recipes()) {
            if (kinds.contains(recipe.kind())) {
                recipes.add(recipe);
            }
        }
        WorthCalculator.Result result = WorthCalculator.calculate(base, overrides, recipes, craftLoss, money.maxAmount(),
            categories::listed);
        for (String problem : result.problems()) {
            r.problem("base-prices", problem);
        }
        return new SellSettings(craftLoss, kinds, tiers, sellAll, shulkerContents, bundleContents, blockInCombat,
            markTrades, actionBar, categories, mastery, topRefresh, result.table(), recipes, result.problems());
    }

    private static SellAll sellAll(ConfigReader s, MoneyFormat money) {
        boolean skipUnstackable = s.bool("skip-unstackable", true);
        boolean skipHotbar = s.bool("skip-hotbar", false);
        Confirm confirm = Confirm.ABOVE;
        if (s.has("confirm")) {
            String raw = s.string("confirm", "above").strip().toLowerCase(Locale.ROOT);
            switch (raw) {
                case "always" -> confirm = Confirm.ALWAYS;
                case "above" -> confirm = Confirm.ABOVE;
                case "never" -> confirm = Confirm.NEVER;
                default -> s.problem("confirm", "must be always, above or never, got '" + raw + "'");
            }
        }
        long confirmAbove = s.has("confirm-above") ? s.money("confirm-above", money, true, 10_000) : 10_000;
        boolean shulkerContents = optionalBool(s, "shulker-contents", true);
        return new SellAll(skipUnstackable, skipHotbar, confirm, confirmAbove, shulkerContents);
    }

    private static boolean optionalBool(ConfigReader r, String path, boolean fallback) {
        return r.has(path) ? r.bool(path, fallback) : fallback;
    }

    /**
     * Reads a price section. Keys are item ids ({@code diamond}, {@code minecraft:diamond}), item tags
     * ({@code #minecraft:logs}) or {@code *} patterns ({@code music_disc_*}). An item listed by id wins over every
     * tag, a tag wins over every pattern, and when two tags (or two patterns) price the same item the lower price is
     * used. Every tag and pattern must name at least one item.
     */
    static Map<String, Long> prices(ConfigReader section, ItemCatalog catalog, MoneyFormat money, boolean allowZero) {
        Map<String, Long> byItem = new LinkedHashMap<>();
        Map<String, Long> byTag = new HashMap<>();
        Map<String, Long> byPattern = new HashMap<>();
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
            if (raw.contains("*")) {
                List<String> matched = SellCategories.matching(raw, catalog);
                if (matched == null) {
                    section.problem(raw, "is not a pattern like music_disc_* or *_coral");
                    continue;
                }
                if (matched.isEmpty()) {
                    section.problem(raw, "matches no item of this Minecraft version");
                    continue;
                }
                for (String member : matched) {
                    byPattern.merge(member, value, Math::min);
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
        Map<String, Long> result = new HashMap<>(byPattern);
        result.putAll(byTag);
        result.putAll(byItem);
        return result;
    }
}
