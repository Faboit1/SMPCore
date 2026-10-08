package net.siftvanilla.siftcore.feature.auction;

import java.util.Locale;
import java.util.Set;

/**
 * Sorts an item type into an auction category. Pure: the server-side caller gathers the facts (the type key, the
 * vanilla item tags the type is in, whether it places a block and whether it is food) and this decides. The first
 * matching rule wins:
 * <ol>
 *   <li>spawners: {@code spawner}, {@code trial_spawner};</li>
 *   <li>potions: {@code potion}, {@code splash_potion}, {@code lingering_potion}, {@code ominous_bottle};</li>
 *   <li>books: {@code book}, {@code writable_book}, {@code written_book}, {@code enchanted_book},
 *       {@code knowledge_book};</li>
 *   <li>combat: swords, spears and armour (item tags {@code swords}, {@code spears}, {@code head_armor},
 *       {@code chest_armor}, {@code leg_armor}, {@code foot_armor}), arrows (tag {@code arrows}), {@code mace},
 *       {@code trident}, {@code bow}, {@code crossbow}, {@code shield}, {@code totem_of_undying},
 *       {@code end_crystal}, {@code wind_charge}, {@code wolf_armor} and every {@code *_horse_armor} and
 *       {@code *_nautilus_armor};</li>
 *   <li>tools: axes, pickaxes, shovels, hoes, compasses and bundles (item tags), every bucket, boat and minecart,
 *       {@code shears}, {@code flint_and_steel}, {@code fire_charge}, {@code fishing_rod}, {@code carrot_on_a_stick},
 *       {@code warped_fungus_on_a_stick}, {@code brush}, {@code spyglass}, {@code clock}, {@code lead},
 *       {@code name_tag}, {@code saddle}, {@code elytra}, {@code firework_rocket}, {@code ender_pearl},
 *       {@code ender_eye}, {@code map}, {@code filled_map}, {@code goat_horn};</li>
 *   <li>food: anything edible (it has the food component);</li>
 *   <li>blocks: anything that places a block;</li>
 *   <li>misc: everything else.</li>
 * </ol>
 */
public final class ItemCategories {

    /**
     * What the server knows about an item.
     *
     * @param key   the item type key, e.g. {@code minecraft:diamond_sword}
     * @param tags  names of the vanilla item tags the type belongs to, without namespace (e.g. {@code swords})
     * @param block whether the item places a block
     * @param food  whether the item is edible
     */
    public record Traits(String key, Set<String> tags, boolean block, boolean food) {
        public Traits {
            key = key == null ? "" : key.toLowerCase(Locale.ROOT);
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }
    }

    /** The item tags the classifier looks at; the server-side caller checks exactly these. */
    public static final Set<String> TAGS = Set.of("swords", "spears", "axes", "pickaxes", "shovels", "hoes",
        "head_armor", "chest_armor", "leg_armor", "foot_armor", "arrows", "compasses", "bundles", "boats", "chest_boats");

    private static final Set<String> SPAWNERS = Set.of("spawner", "trial_spawner");
    private static final Set<String> POTIONS = Set.of("potion", "splash_potion", "lingering_potion", "ominous_bottle");
    private static final Set<String> BOOKS = Set.of("book", "writable_book", "written_book", "enchanted_book", "knowledge_book");
    private static final Set<String> COMBAT_TAGS = Set.of("swords", "spears", "head_armor", "chest_armor", "leg_armor",
        "foot_armor", "arrows");
    private static final Set<String> COMBAT = Set.of("mace", "trident", "bow", "crossbow", "shield", "totem_of_undying",
        "end_crystal", "wind_charge", "wolf_armor");
    private static final Set<String> TOOL_TAGS = Set.of("axes", "pickaxes", "shovels", "hoes", "compasses", "bundles",
        "boats", "chest_boats");
    private static final Set<String> TOOLS = Set.of("shears", "flint_and_steel", "fire_charge", "fishing_rod",
        "carrot_on_a_stick", "warped_fungus_on_a_stick", "brush", "spyglass", "clock", "lead", "name_tag", "saddle",
        "elytra", "firework_rocket", "ender_pearl", "ender_eye", "map", "filled_map", "goat_horn");

    private ItemCategories() {
    }

    public static Category classify(Traits traits) {
        String path = path(traits.key());
        if (SPAWNERS.contains(path)) {
            return Category.SPAWNERS;
        }
        if (POTIONS.contains(path)) {
            return Category.POTIONS;
        }
        if (BOOKS.contains(path)) {
            return Category.BOOKS;
        }
        if (intersects(traits.tags(), COMBAT_TAGS) || COMBAT.contains(path)
            || path.endsWith("_horse_armor") || path.endsWith("_nautilus_armor")) {
            return Category.COMBAT;
        }
        if (intersects(traits.tags(), TOOL_TAGS) || TOOLS.contains(path)
            || path.endsWith("bucket") || path.endsWith("minecart") || path.endsWith("_boat") || path.endsWith("_raft")) {
            return Category.TOOLS;
        }
        if (traits.food()) {
            return Category.FOOD;
        }
        if (traits.block()) {
            return Category.BLOCKS;
        }
        return Category.MISC;
    }

    /** The part of a key after the namespace. */
    static String path(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(colon + 1);
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String value : a) {
            if (b.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
