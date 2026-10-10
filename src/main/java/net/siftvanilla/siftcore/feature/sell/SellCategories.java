package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.item.ItemPatterns;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Sell categories ({@code categories} in {@code features/sell.yml}): groups of items with their own mastery, a
 * filter in the worth browser and "sell everything of this kind". Pure logic.
 * <p>
 * An item listed in a category (by id, item tag or {@code *} pattern) is in that category; an item listed in two
 * categories is a config problem. An unlisted item whose price comes from a recipe takes the category of its most
 * valuable ingredient (worked out once, when the worth table is generated), so an iron block is mining, bread is
 * farming and planks are wood. Everything else is in the fallback category {@value #OTHER}.
 *
 * @param categories every category in file order, the fallback last
 * @param explicit   item key to the id of the category that lists it
 */
public record SellCategories(Map<String, Category> categories, Map<String, String> explicit) {

    /** The id of the fallback category. */
    public static final String OTHER = "other";
    private static final String ID = "[a-z0-9_]{1,32}";

    /**
     * One category.
     *
     * @param id   stable id (stored with mastery, used in placeholders like {@code sell_mastery_<id>})
     * @param name display name
     * @param icon item key of its icon
     */
    public record Category(String id, String name, String icon) {
    }

    /** The defaults, used when {@code features/sell.yml} has no {@code categories} section (older files). */
    static final String DEFAULTS = """
        categories:
          farming:
            name: "Farming"
            icon: wheat
            items: [wheat, wheat_seeds, carrot, potato, beetroot, beetroot_seeds, melon_slice, melon, melon_seeds,
              pumpkin, pumpkin_seeds, torchflower_seeds, pitcher_pod, sugar_cane, cactus, bamboo, kelp, sweet_berries,
              glow_berries, cocoa_beans, apple, nether_wart, chorus_fruit, honeycomb, honey_bottle, honey_block,
              brown_mushroom, red_mushroom]
          wood:
            name: "Wood"
            icon: oak_log
            items: ["#minecraft:logs"]
          mining:
            name: "Mining"
            icon: diamond
            items: [coal, "raw_*", "*_ingot", "*_ore", diamond, emerald, lapis_lazuli, redstone, quartz, amethyst_shard,
              glowstone_dust, ancient_debris, netherite_scrap, obsidian, crying_obsidian]
          mob_drops:
            name: "Mob drops"
            icon: bone
            items: [rotten_flesh, bone, string, spider_eye, gunpowder, ender_pearl, blaze_rod, breeze_rod, ghast_tear,
              slime_ball, magma_cream, leather, rabbit_hide, rabbit_foot, feather, egg, blue_egg, brown_egg, beef,
              porkchop, chicken, mutton, rabbit, ink_sac, glow_ink_sac, phantom_membrane, turtle_scute, armadillo_scute,
              shulker_shell, arrow, wither_skeleton_skull, zombie_head, skeleton_skull, creeper_head, piglin_head,
              dragon_head, nether_star, totem_of_undying, echo_shard]
          fishing:
            name: "Fishing"
            icon: cod
            items: [cod, salmon, tropical_fish, pufferfish, nautilus_shell, heart_of_the_sea, "prismarine_*"]
          other:
            name: "Blocks and other"
            icon: grass_block
            items: []
        """;

    public SellCategories {
        categories = Collections.unmodifiableMap(new LinkedHashMap<>(categories));
        explicit = Map.copyOf(explicit);
        if (!categories.containsKey(OTHER)) {
            throw new IllegalArgumentException("The fallback category " + OTHER + " is missing");
        }
    }

    /** The category that lists an item, or null when no category lists it. */
    public String listed(String item) {
        return this.explicit.get(item);
    }

    /** The category with this id, or null. */
    public Category category(String id) {
        return this.categories.get(id);
    }

    /** The category's display name; the id itself for a category that no longer exists. */
    public String name(String id) {
        Category category = this.categories.get(id);
        return category == null ? id : category.name();
    }

    /** Category ids in file order, the fallback last. */
    public List<String> ids() {
        return List.copyOf(this.categories.keySet());
    }

    /** Only the fallback category (used when nothing could be parsed). */
    public static SellCategories fallbackOnly() {
        return new SellCategories(Map.of(OTHER, new Category(OTHER, "Blocks and other", "minecraft:grass_block")), Map.of());
    }

    /**
     * Reads the {@code categories} section of {@code parent}, or the built-in defaults when it has none.
     *
     * @param catalog the server's items and tags
     */
    public static SellCategories parse(ConfigReader parent, ItemCatalog catalog) {
        if (!parent.has("categories")) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(DEFAULTS);
            } catch (InvalidConfigurationException e) {
                throw new IllegalStateException("The built-in sell categories are broken", e);
            }
            ConfigReader defaults = new ConfigReader(parent.file(), yaml);
            SellCategories parsed = read(defaults, catalog);
            // The defaults only name vanilla items; anything they can't resolve on this server is simply skipped.
            return parsed;
        }
        return read(parent, catalog);
    }

    private static SellCategories read(ConfigReader parent, ItemCatalog catalog) {
        Map<String, Category> categories = new LinkedHashMap<>();
        Map<String, String> explicit = new HashMap<>();
        Map<String, ConfigReader> sections = parent.children("categories");
        for (Map.Entry<String, ConfigReader> entry : sections.entrySet()) {
            String id = entry.getKey();
            ConfigReader c = entry.getValue();
            if (!id.matches(ID)) {
                parent.problem("categories." + id, "category ids are lowercase letters, digits or _ (up to 32)");
                continue;
            }
            if (id.equals("reset")) {
                parent.problem("categories." + id, "reset is taken by /sell admin mastery <player> reset; pick another id");
                continue;
            }
            String name = c.has("name") ? c.string("name", id).strip() : id.replace('_', ' ');
            if (name.isEmpty() || name.length() > 32) {
                c.problem("name", "must be 1 to 32 characters");
                name = id;
            }
            String icon = "minecraft:paper";
            if (c.has("icon")) {
                String key = ItemKeys.normalize(c.string("icon", "paper"));
                if (key == null || !catalog.isItem(key)) {
                    c.problem("icon", "is not an item of this Minecraft version");
                } else {
                    icon = key;
                }
            }
            for (String item : members(c, catalog)) {
                String previous = explicit.putIfAbsent(item, id);
                if (previous != null && !previous.equals(id)) {
                    c.problem("items", ItemKeys.shortKey(item) + " is also in the category " + previous
                        + "; an item can only be in one category");
                }
            }
            categories.put(id, new Category(id, name, icon));
        }
        Category other = categories.remove(OTHER);
        categories.put(OTHER, other == null ? new Category(OTHER, "Blocks and other", "minecraft:grass_block") : other);
        return new SellCategories(categories, explicit);
    }

    /** The items a category lists: ids, {@code #tags} and {@code *} patterns, each of which must name something. */
    private static Set<String> members(ConfigReader c, ItemCatalog catalog) {
        Set<String> members = new TreeSet<>();
        List<String> entries = c.has("items") ? c.stringList("items", List.of()) : List.of();
        for (String raw : entries) {
            String text = raw.strip();
            if (text.startsWith("#")) {
                String tag = ItemKeys.normalize(text.substring(1));
                Set<String> tagged = tag == null ? null : catalog.tag(tag);
                if (tagged == null) {
                    c.problem("items", "'" + text + "' is not an item tag of this Minecraft version");
                } else {
                    members.addAll(tagged);
                }
            } else if (text.contains("*")) {
                List<String> matched = matching(text, catalog);
                if (matched == null) {
                    c.problem("items", "'" + text + "' is not a pattern like raw_* or *_ingot");
                } else if (matched.isEmpty()) {
                    c.problem("items", "'" + text + "' matches no item of this Minecraft version");
                } else {
                    members.addAll(matched);
                }
            } else {
                String item = ItemKeys.normalize(text);
                if (item == null || !catalog.isItem(item)) {
                    c.problem("items", "'" + text + "' is not an item of this Minecraft version");
                } else {
                    members.add(item);
                }
            }
        }
        return members;
    }

    /** The catalog items a {@code *} pattern matches (sorted), or null when the pattern is malformed. */
    static List<String> matching(String pattern, ItemCatalog catalog) {
        ItemPatterns compiled;
        try {
            compiled = ItemPatterns.compile(List.of(pattern));
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<String> matched = new ArrayList<>();
        for (String item : new TreeSet<>(catalog.items())) {
            if (compiled.matches(item)) {
                matched.add(item);
            }
        }
        return matched;
    }
}
