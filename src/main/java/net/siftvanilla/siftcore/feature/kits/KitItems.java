package net.siftvanilla.siftcore.feature.kits;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.siftvanilla.siftcore.core.text.Palette;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

/**
 * Turns kit items from the config into real items: material and amount, a white custom name and gray lore with
 * italics off, enchantments (stored in the book for enchanted books) and unbreakable.
 */
final class KitItems {

    private final Supplier<Palette> palette;

    KitItems(Supplier<Palette> palette) {
        this.palette = palette;
    }

    /**
     * What this Minecraft version offers, for validating {@code features/kits.yml}.
     *
     * @param crates the crates whose keys kits may give
     */
    static KitsSettings.Catalog catalog(Set<String> crates) {
        Set<String> items = new HashSet<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && !material.isAir() && material.isItem()) {
                items.add(material.getKey().asString());
            }
        }
        Map<String, Integer> enchantments = new HashMap<>();
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (Enchantment enchantment : registry) {
            enchantments.put(registry.getKeyOrThrow(enchantment).asString(), enchantment.getMaxLevel());
        }
        return new KitsSettings.Catalog(items, enchantments, crates);
    }

    /** The item, with its full amount (more than a stack is split when it is handed out). */
    ItemStack build(KitItem item) {
        Material material = material(item.material(), Material.STONE);
        ItemStack stack = ItemStack.of(material, 1);
        Palette colors = this.palette.get();
        if (item.name() != null) {
            stack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(item.name(), colors.primary())
                .decoration(TextDecoration.ITALIC, false));
        }
        if (!item.lore().isEmpty()) {
            List<Component> lines = new ArrayList<>(item.lore().size());
            for (String line : item.lore()) {
                lines.add(Component.text(line, colors.secondary()).decoration(TextDecoration.ITALIC, false));
            }
            stack.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
        if (!item.enchantments().isEmpty()) {
            Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
            Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            item.enchantments().forEach((key, level) -> {
                Enchantment enchantment = registry.get(Key.key(key));
                if (enchantment != null) {
                    enchants.put(enchantment, level);
                }
            });
            if (!enchants.isEmpty()) {
                stack.setData(material == Material.ENCHANTED_BOOK ? DataComponentTypes.STORED_ENCHANTMENTS : DataComponentTypes.ENCHANTMENTS,
                    ItemEnchantments.itemEnchantments(enchants));
            }
        }
        if (item.unbreakable()) {
            stack.setData(DataComponentTypes.UNBREAKABLE);
        }
        stack.setAmount(item.amount());
        return stack;
    }

    /** Every item of a kit, built. */
    List<ItemStack> build(Kit kit) {
        List<ItemStack> stacks = new ArrayList<>(kit.items().size());
        for (KitItem item : kit.items()) {
            stacks.add(build(item));
        }
        return stacks;
    }

    /** The kit's icon. */
    static ItemStack icon(Kit kit) {
        return ItemStack.of(material(kit.icon(), Material.CHEST));
    }

    static Material material(String key, Material fallback) {
        Material material = Material.matchMaterial(key);
        return material == null || !material.isItem() || material.isAir() ? fallback : material;
    }
}
