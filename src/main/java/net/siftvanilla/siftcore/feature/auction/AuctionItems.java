package net.siftvanilla.siftcore.feature.auction;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.item.ItemCategories;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Server-side facts about item stacks: type key, category, search text, display name, inventory space. */
final class AuctionItems {

    /** The planner for real inventories. */
    static final StackPlanner<ItemStack> PLANNER = new StackPlanner<>(new StackPlanner.Stacks<>() {
        @Override
        public boolean empty(ItemStack stack) {
            return stack == null || stack.isEmpty();
        }

        @Override
        public boolean similar(ItemStack a, ItemStack b) {
            return a.isSimilar(b);
        }

        @Override
        public int amount(ItemStack stack) {
            return stack.getAmount();
        }

        @Override
        public int maxStack(ItemStack stack) {
            return stack.getMaxStackSize();
        }
    });

    private static final int MAX_LORE_LINES = 256;

    private final Map<String, Tag<Material>> tags;

    AuctionItems() {
        Map<String, Tag<Material>> map = new LinkedHashMap<>();
        map.put("swords", Tag.ITEMS_SWORDS);
        map.put("spears", Tag.ITEMS_SPEARS);
        map.put("axes", Tag.ITEMS_AXES);
        map.put("pickaxes", Tag.ITEMS_PICKAXES);
        map.put("shovels", Tag.ITEMS_SHOVELS);
        map.put("hoes", Tag.ITEMS_HOES);
        map.put("head_armor", Tag.ITEMS_HEAD_ARMOR);
        map.put("chest_armor", Tag.ITEMS_CHEST_ARMOR);
        map.put("leg_armor", Tag.ITEMS_LEG_ARMOR);
        map.put("foot_armor", Tag.ITEMS_FOOT_ARMOR);
        map.put("arrows", Tag.ITEMS_ARROWS);
        map.put("compasses", Tag.ITEMS_COMPASSES);
        map.put("bundles", Tag.ITEMS_BUNDLES);
        map.put("boats", Tag.ITEMS_BOATS);
        map.put("chest_boats", Tag.ITEMS_CHEST_BOATS);
        map.values().removeIf(java.util.Objects::isNull);
        this.tags = Map.copyOf(map);
    }

    /** The item type key, e.g. {@code minecraft:diamond_sword}. */
    static String typeKey(ItemStack item) {
        return item.getType().getKey().asString();
    }

    /** The category of a stack. */
    ItemCategory category(ItemStack item) {
        Material type = item.getType();
        Set<String> member = new HashSet<>();
        this.tags.forEach((name, tag) -> {
            if (tag.isTagged(type)) {
                member.add(name);
            }
        });
        boolean food = type.isEdible() || item.hasData(DataComponentTypes.FOOD);
        return ItemCategories.classify(new ItemCategories.Traits(typeKey(item), member, type.isBlock(), food));
    }

    /** The name players see, as plain text (custom name, else the effective vanilla name in English). */
    static String plainName(ItemStack item) {
        return TextStyle.plain(Items.name(item));
    }

    /** Lowercase text searches match: the plain name, the type id with spaces, and enchantment names. */
    static String searchText(ItemStack item) {
        StringBuilder text = new StringBuilder();
        text.append(plainName(item).toLowerCase(Locale.ROOT));
        String path = ItemCategories.path(typeKey(item));
        text.append(' ').append(path.replace('_', ' '));
        List<String> enchantments = new ArrayList<>();
        addEnchantments(item.getData(DataComponentTypes.ENCHANTMENTS), enchantments);
        addEnchantments(item.getData(DataComponentTypes.STORED_ENCHANTMENTS), enchantments);
        for (String enchantment : enchantments) {
            text.append(' ').append(enchantment);
        }
        return text.toString();
    }

    private static void addEnchantments(ItemEnchantments enchantments, List<String> into) {
        if (enchantments == null) {
            return;
        }
        for (Enchantment enchantment : enchantments.enchantments().keySet()) {
            into.add(enchantment.key().value().replace('_', ' '));
        }
    }

    /** True for shulker boxes, bundles and other containers that hold items. */
    static boolean holdsItems(ItemStack item) {
        ItemContainerContents container = item.getData(DataComponentTypes.CONTAINER);
        if (container != null && container.contents().stream().anyMatch(stack -> stack != null && !stack.isEmpty())) {
            return true;
        }
        BundleContents bundle = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
        return bundle != null && bundle.contents().stream().anyMatch(stack -> stack != null && !stack.isEmpty());
    }

    /**
     * The uncompressed size of the item's stored data (one item, as clients receive it), or {@code limit + 1} once it
     * is known to be larger. Items that cannot be stored at all count as too large.
     */
    static long dataSize(ItemStack item, long limit) {
        ItemStack one = item.asOne();
        try {
            return ItemData.uncompressedSize(one.serializeAsBytes(), limit);
        } catch (RuntimeException e) {
            return limit + 1;
        }
    }

    /** True when the player is in a game mode that can create items. */
    static boolean creative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    /**
     * A copy of the item with every tooltip line shown. A seller can hide parts of the tooltip (enchantments, curses,
     * container contents, even the whole tooltip); buyers always see the full item, and the auction lore with it.
     */
    static ItemStack revealed(ItemStack item) {
        ItemStack copy = item.clone();
        copy.resetData(DataComponentTypes.TOOLTIP_DISPLAY);
        return copy;
    }

    /**
     * A {@linkplain #revealed revealed} copy of the real item with extra lore lines below its own. Items that already
     * carry nearly the maximum lore keep only as much of their own lore as fits.
     */
    static ItemStack display(ItemStack item, List<Component> extra) {
        ItemStack copy = revealed(item);
        var lore = copy.getData(DataComponentTypes.LORE);
        if (lore != null && lore.lines().size() + extra.size() + 1 > MAX_LORE_LINES) {
            int keep = Math.max(0, MAX_LORE_LINES - extra.size() - 1);
            copy.setData(DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(lore.lines().subList(0, keep)));
        }
        return Items.display(copy, extra);
    }

    /** The player's storage slots (main inventory and hotbar), copied. */
    static List<ItemStack> storage(Player player) {
        return Arrays.asList(player.getInventory().getStorageContents());
    }

    /** True when the whole stack fits into the player's inventory right now. */
    static boolean fits(Player player, List<ItemStack> stacks) {
        return PLANNER.fitsAll(storage(player), stacks);
    }
}
