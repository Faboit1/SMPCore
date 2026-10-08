package net.siftvanilla.siftcore.feature.shop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.feature.sell.ItemKeys;
import net.siftvanilla.siftcore.feature.sell.Pricing;

/**
 * Parsed {@code features/shop.yml}. Every item price is checked against the worth table while parsing (see
 * {@link ShopValidator}); an unsafe entry is reported as a config problem and left out of the shop, so a reload
 * with an unsafe price is refused and a startup with one never sells it.
 *
 * @param confirmAbove purchases costing at least this much ask for confirmation (0 never asks)
 * @param defaultMax   the most of one entry a single purchase may buy, unless the entry says otherwise
 * @param rows         rows of the category menu
 * @param categories   categories in file order
 */
public record ShopSettings(long confirmAbove, int defaultMax, int rows, List<Category> categories) {

    /** The most any single purchase may buy. */
    public static final int MAX_PER_PURCHASE = 6400;
    /** Category ids; with entry ids, {@code category/entry} refs fit the 64 characters storage keeps. */
    private static final String CATEGORY_ID = "[a-z0-9_-]{1,24}";
    private static final String ENTRY_ID = "[a-z0-9_-]{1,39}";

    /**
     * A category of the shop.
     *
     * @param id          stable id (config key), used by {@code /shop <category>}
     * @param name        plain display name
     * @param description plain one-line description (may be empty)
     * @param icon        item key of the menu icon
     * @param slot        slot in the category menu
     * @param entries     what it sells, in file order
     */
    public record Category(String id, String name, String description, String icon, int slot, List<Entry> entries) {
        public Category {
            entries = List.copyOf(entries);
        }
    }

    /**
     * Something the shop sells: an item, or a spawner of a mob.
     *
     * @param category the category id
     * @param id       the entry id (config key)
     * @param item     item key, or null for a spawner
     * @param mob      entity type key of a spawner, or null for an item
     * @param price    price per unit
     * @param max      most units per purchase
     */
    public record Entry(String category, String id, String item, String mob, long price, int max) {

        /** {@code category/id}, unique in the shop; used as the ledger reference. */
        public String ref() {
            return this.category + "/" + this.id;
        }

        public boolean spawner() {
            return this.mob != null;
        }
    }

    public ShopSettings {
        categories = List.copyOf(categories);
    }

    /** The slot of the balance display in the category menu (middle of the bottom row). */
    public int balanceSlot() {
        return (this.rows - 1) * 9 + 4;
    }

    public Category category(String id) {
        for (Category category : this.categories) {
            if (category.id().equals(id)) {
                return category;
            }
        }
        return null;
    }

    /** The entry with this {@link Entry#ref()}, or null. */
    public Entry entry(String ref) {
        int slash = ref.indexOf('/');
        if (slash < 0) {
            return null;
        }
        Category category = category(ref.substring(0, slash));
        if (category == null) {
            return null;
        }
        String id = ref.substring(slash + 1);
        for (Entry entry : category.entries()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /** What the shop may refer to: item keys and entity type keys of this Minecraft version. */
    public record Catalog(Set<String> items, Set<String> entityTypes) {
        public Catalog {
            items = Set.copyOf(items);
            entityTypes = Set.copyOf(entityTypes);
        }
    }

    public static ShopSettings parse(ConfigReader r, Catalog catalog, Pricing pricing, MoneyFormat money) {
        long confirmAbove = r.money("confirm-above", money, true, 50_000);
        int defaultMax = r.integer("default-max", 1, MAX_PER_PURCHASE, 640);
        int rows = r.section("menu").integer("rows", 2, 6, 4);
        int balanceSlot = (rows - 1) * 9 + 4;
        ShopValidator.Analysis analysis = ShopValidator.analyze(pricing);

        List<Category> categories = new ArrayList<>();
        Set<Integer> usedSlots = new HashSet<>();
        Map<String, ConfigReader> sections = r.children("categories");
        if (sections.isEmpty()) {
            r.problem("categories", "has no categories");
        }
        for (Map.Entry<String, ConfigReader> categoryEntry : sections.entrySet()) {
            String id = categoryEntry.getKey();
            ConfigReader c = categoryEntry.getValue();
            if (!id.matches(CATEGORY_ID)) {
                r.problem("categories." + id, "category ids are lowercase letters, digits, - or _ (up to 24)");
                continue;
            }
            String name = c.string("name", id).strip();
            if (name.isEmpty() || name.length() > 32) {
                c.problem("name", "must be 1 to 32 characters");
                name = id;
            }
            String description = c.optionalString("description", "").strip();
            if (description.length() > 64) {
                c.problem("description", "must be at most 64 characters");
                description = description.substring(0, 64);
            }
            String icon = item(c, "icon", catalog, "minecraft:chest");
            int slot = c.integer("slot", 0, rows * 9 - 1, 0);
            if (slot == balanceSlot) {
                c.problem("slot", "is where the balance is shown (slot " + balanceSlot + "); pick another slot");
            } else if (!usedSlots.add(slot)) {
                c.problem("slot", "is used by another category");
            }
            int categoryMax = c.has("default-max") ? c.integer("default-max", 1, MAX_PER_PURCHASE, defaultMax) : defaultMax;
            List<Entry> entries = new ArrayList<>();
            Map<String, ConfigReader> items = c.children("items");
            for (Map.Entry<String, ConfigReader> itemEntry : items.entrySet()) {
                Entry entry = entry(c, id, itemEntry.getKey(), itemEntry.getValue(), catalog, pricing, analysis, money,
                    categoryMax);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            if (items.isEmpty()) {
                c.problem("items", "is empty; a category needs at least one item");
            }
            categories.add(new Category(id, name, description, icon, slot, entries));
        }
        return new ShopSettings(confirmAbove, defaultMax, rows, categories);
    }

    private static Entry entry(ConfigReader c, String category, String id, ConfigReader e, Catalog catalog,
                               Pricing pricing, ShopValidator.Analysis analysis, MoneyFormat money, int defaultMax) {
        String path = "items." + id;
        if (!id.matches(ENTRY_ID)) {
            c.problem(path, "entry ids are lowercase letters, digits, - or _ (up to 39)");
            return null;
        }
        long price = e.money("price", money, false, -1);
        int max = e.has("max") ? e.integer("max", 1, MAX_PER_PURCHASE, defaultMax) : defaultMax;
        if (price > 0 && PurchaseMath.total(price, max, money.maxAmount()).isEmpty()) {
            e.problem("max", "buying " + max + " at " + price + " each costs more than the money limit of "
                + money.maxAmount() + "; lower max or the price");
            return null;
        }
        if (e.has("item") && e.has("spawner")) {
            c.problem(path, "has both item and spawner; an entry sells one or the other");
            return null;
        }
        if (e.has("spawner")) {
            String mob = ItemKeys.normalize(e.string("spawner", ""));
            if (mob == null || !catalog.entityTypes().contains(mob)) {
                e.problem("spawner", "is not a mob of this Minecraft version (use ids like zombie or cave_spider)");
                return null;
            }
            return price < 0 ? null : new Entry(category, id, null, mob, price, max);
        }
        String item = e.has("item") ? item(e, "item", catalog, null) : itemFromId(c, path, id, catalog);
        if (item == null || price < 0) {
            return null;
        }
        String problem = ShopValidator.check(item, price, pricing, analysis);
        if (problem != null) {
            e.problem("price", problem + " (an item with an unsafe price is never sold)");
            return null;
        }
        return new Entry(category, id, item, null, price, max);
    }

    private static String item(ConfigReader r, String path, Catalog catalog, String fallback) {
        String raw = r.string(path, fallback == null ? "" : fallback);
        String key = ItemKeys.normalize(raw);
        if (key == null || !catalog.items().contains(key) || key.equals("minecraft:air")) {
            r.problem(path, "is not an item of this Minecraft version");
            return fallback;
        }
        return key;
    }

    private static String itemFromId(ConfigReader c, String path, String id, Catalog catalog) {
        String key = ItemKeys.normalize(id);
        if (key == null || !catalog.items().contains(key) || key.equals("minecraft:air")) {
            c.problem(path, "is not an item of this Minecraft version; add item: (or spawner:) to say what it sells");
            return null;
        }
        return key;
    }

    /** Entries by ref, for lookups (a fresh map; the record itself stays a plain value). */
    public Map<String, Entry> entriesByRef() {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Category category : this.categories) {
            for (Entry entry : category.entries()) {
                map.put(entry.ref(), entry);
            }
        }
        return map;
    }
}
