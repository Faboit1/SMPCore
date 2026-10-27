package net.siftvanilla.siftcore.ui.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Builds GUI icons. Names and lore always have italics explicitly off (vanilla italicises custom lore by default)
 * and lines without a colour get the palette colour passed in by the caller.
 */
public final class Items {

    private Items() {
    }

    private static Component plain(Component line) {
        return line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static List<Component> plain(List<Component> lines) {
        List<Component> result = new ArrayList<>(lines.size());
        for (Component line : lines) {
            result.add(plain(line));
        }
        return result;
    }

    /** A button icon: material, white name, gray description lines. */
    public static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.CUSTOM_NAME, plain(name));
        if (!lore.isEmpty()) {
            item.setData(DataComponentTypes.LORE, ItemLore.lore(plain(lore)));
        }
        hideDetails(item);
        return item;
    }

    /** A copy of a real item (keeping its name, enchantments and data) with extra lore lines appended. */
    public static ItemStack display(ItemStack original, List<Component> extraLore) {
        ItemStack item = original.clone();
        List<Component> lore = new ArrayList<>();
        ItemLore existing = item.getData(DataComponentTypes.LORE);
        if (existing != null) {
            lore.addAll(existing.lines());
        }
        if (!extraLore.isEmpty()) {
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.addAll(plain(extraLore));
        }
        if (!lore.isEmpty()) {
            item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        }
        return item;
    }

    /** Hides attribute and other technical tooltip lines on pure UI icons. */
    public static void hideDetails(ItemStack item) {
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
            .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS,
                DataComponentTypes.STORED_ENCHANTMENTS, DataComponentTypes.POTION_CONTENTS, DataComponentTypes.TRIM,
                DataComponentTypes.DYED_COLOR, DataComponentTypes.BANNER_PATTERNS, DataComponentTypes.JUKEBOX_PLAYABLE)
            .build());
    }

    /** The item's display name: custom name, else item name, else the translatable vanilla name. */
    public static Component name(ItemStack item) {
        Component custom = item.getData(DataComponentTypes.CUSTOM_NAME);
        if (custom != null) {
            return custom;
        }
        return item.effectiveName();
    }
}
