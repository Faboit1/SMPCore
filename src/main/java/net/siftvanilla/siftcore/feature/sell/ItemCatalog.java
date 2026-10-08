package net.siftvanilla.siftcore.feature.sell;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server's items, item tags and recipes as plain data, captured once at startup (the server cannot reload
 * data packs, so they do not change while it runs). Config parsing and the worth math only ever see this snapshot,
 * which keeps them pure and testable.
 *
 * @param items   every item key, e.g. {@code minecraft:diamond}
 * @param tags    item tags by key ({@code minecraft:logs}) to their item keys
 * @param recipes every recipe pricing can use
 */
public record ItemCatalog(Set<String> items, Map<String, Set<String>> tags, List<RecipeDef> recipes) {

    public static final ItemCatalog EMPTY = new ItemCatalog(Set.of(), Map.of(), List.of());

    public ItemCatalog {
        items = Set.copyOf(items);
        Map<String, Set<String>> copy = new HashMap<>();
        tags.forEach((key, members) -> copy.put(key, Set.copyOf(members)));
        tags = Map.copyOf(copy);
        recipes = List.copyOf(recipes);
    }

    public boolean isItem(String key) {
        return this.items.contains(key);
    }

    /** The members of an item tag, or null when there is no such tag. */
    public Set<String> tag(String key) {
        return this.tags.get(key);
    }
}
