package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A server recipe reduced to what pricing needs: which items go in, how many of each, and how many come out. Pure
 * data, no server types, so the worth math can be tested without a server.
 *
 * @param id          the recipe key, e.g. {@code minecraft:iron_block}
 * @param kind        what made it (crafting, smelting, ...)
 * @param output      the result item key, e.g. {@code minecraft:iron_block}
 * @param outputCount how many items one craft makes (at least 1)
 * @param ingredients what one craft consumes
 */
public record RecipeDef(String id, Kind kind, String output, int outputCount, List<Ingredient> ingredients) {

    /** The recipe families, also the names used in {@code features/sell.yml}. */
    public enum Kind {
        CRAFTING("crafting"),
        SMELTING("smelting"),
        BLASTING("blasting"),
        SMOKING("smoking"),
        CAMPFIRE("campfire"),
        STONECUTTING("stonecutting"),
        SMITHING("smithing");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        /** The kind with this config name, or null. */
        public static Kind byId(String id) {
            for (Kind kind : values()) {
                if (kind.id.equalsIgnoreCase(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * One ingredient slot group: {@code count} slots that each accept any of {@code options} (item keys). Options a
     * server reports but pricing cannot use (custom items) are simply absent; an empty option list makes the recipe
     * unusable for pricing.
     */
    public record Ingredient(List<String> options, int count) {
        public Ingredient {
            options = List.copyOf(new TreeSet<>(options));
            if (count < 1) {
                throw new IllegalArgumentException("An ingredient is used at least once");
            }
        }
    }

    public RecipeDef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(output, "output");
        if (outputCount < 1) {
            throw new IllegalArgumentException("A recipe makes at least one item: " + id);
        }
        ingredients = merge(ingredients);
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("A recipe needs ingredients: " + id);
        }
    }

    /** Combines slots that accept exactly the same options, so a shaped recipe of 8 planks is one entry of 8. */
    private static List<Ingredient> merge(List<Ingredient> ingredients) {
        Map<List<String>, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            counts.merge(ingredient.options(), ingredient.count(), Integer::sum);
        }
        List<Ingredient> merged = new ArrayList<>(counts.size());
        counts.forEach((options, count) -> merged.add(new Ingredient(options, count)));
        return List.copyOf(merged);
    }

    /** Builds an ingredient list from one option list per slot (one entry per slot, repeats allowed). */
    public static List<Ingredient> slots(List<List<String>> slots) {
        List<Ingredient> list = new ArrayList<>(slots.size());
        for (List<String> options : slots) {
            list.add(new Ingredient(options, 1));
        }
        return list;
    }
}
