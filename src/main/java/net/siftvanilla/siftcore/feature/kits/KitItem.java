package net.siftvanilla.siftcore.feature.kits;

import java.util.List;
import java.util.Map;

/**
 * One item of a kit, as written in {@code features/kits.yml}. Pure data; {@link KitItems} turns it into an item.
 *
 * @param material     the item id ({@code minecraft:iron_sword})
 * @param amount       how many (more than a stack is handed out as several stacks)
 * @param name         a custom name in plain text, or null for the vanilla name
 * @param lore         lore lines in plain text
 * @param enchantments enchantment id to level, in file order
 * @param unbreakable  whether the item never breaks
 */
public record KitItem(String material, int amount, String name, List<String> lore, Map<String, Integer> enchantments,
               boolean unbreakable) {

    public KitItem {
        lore = List.copyOf(lore);
        enchantments = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(enchantments));
    }
}
