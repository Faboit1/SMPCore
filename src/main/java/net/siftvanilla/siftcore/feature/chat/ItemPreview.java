package net.siftvanilla.siftcore.feature.chat;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.WrittenBookContent;
import io.papermc.paper.text.Filtered;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/**
 * The item shown when an {@code [item]} is hovered: a copy that keeps only what a tooltip shows (name, lore,
 * enchantments, durability, trims, potion effects, the items in a shulker box or bundle...) and drops everything a
 * tooltip never shows but that can be huge, such as book pages, block entity data and plugin data. Without this a
 * player could hold a shulker box full of written books, type {@code [item]}, and send every reader a chat packet
 * too large for their client.
 * <p>
 * Names players can choose (anvil names, book titles), on the item and on the items inside it, pass through the link
 * check and the word filter like chat text: a renamed item can't carry an address or a word chat would replace. Run on
 * the thread that owns the item (or on a private copy).
 */
final class ItemPreview {

    /** Components copied as they are: shown in tooltips and small. */
    private static final Set<DataComponentType> SHOWN = Set.of(
        DataComponentTypes.CUSTOM_NAME, DataComponentTypes.ITEM_NAME, DataComponentTypes.LORE, DataComponentTypes.RARITY,
        DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,
        DataComponentTypes.DAMAGE, DataComponentTypes.MAX_DAMAGE, DataComponentTypes.UNBREAKABLE,
        DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.TOOLTIP_DISPLAY, DataComponentTypes.TOOLTIP_STYLE,
        DataComponentTypes.ITEM_MODEL, DataComponentTypes.CUSTOM_MODEL_DATA, DataComponentTypes.TRIM, DataComponentTypes.DYED_COLOR,
        DataComponentTypes.POTION_CONTENTS, DataComponentTypes.POTION_DURATION_SCALE, DataComponentTypes.SUSPICIOUS_STEW_EFFECTS,
        DataComponentTypes.FIREWORKS, DataComponentTypes.FIREWORK_EXPLOSION, DataComponentTypes.BANNER_PATTERNS,
        DataComponentTypes.BASE_COLOR, DataComponentTypes.PROFILE, DataComponentTypes.INSTRUMENT, DataComponentTypes.JUKEBOX_PLAYABLE,
        DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER, DataComponentTypes.MAP_ID, DataComponentTypes.POT_DECORATIONS);

    private ItemPreview() {
    }

    /**
     * The preview of a non-empty item.
     *
     * @param names cleans a name a player chose (link check and word filter); returns the text unchanged when it is fine
     */
    static ItemStack of(ItemStack item, UnaryOperator<String> names) {
        return of(item, false, names);
    }

    private static ItemStack of(ItemStack item, boolean inside, UnaryOperator<String> names) {
        ItemStack preview = ItemStack.of(item.getType(), Math.max(1, item.getAmount()));
        preview.copyDataFrom(item, SHOWN::contains);
        Component custom = item.getData(DataComponentTypes.CUSTOM_NAME);
        if (custom != null) {
            String typed = ChatText.plain(custom);
            String cleaned = names.apply(typed);
            if (!cleaned.equals(typed)) {
                preview.setData(DataComponentTypes.CUSTOM_NAME, Component.text(cleaned));
            }
        }
        WrittenBookContent book = item.getData(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (book != null) {
            String title = book.title().raw();
            String cleaned = names.apply(title);
            preview.setData(DataComponentTypes.WRITTEN_BOOK_CONTENT, WrittenBookContent.writtenBookContent(
                    cleaned.equals(title) ? book.title() : Filtered.of(cleaned, null), book.author())
                .generation(book.generation())
                .build());
        }
        if (inside) {
            return preview;
        }
        ItemContainerContents container = item.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            preview.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(inner(container.contents(), names)));
        }
        BundleContents bundle = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            preview.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(inner(bundle.contents(), names)));
        }
        return preview;
    }

    /** Items inside a container or bundle, previewed one level deep (their own contents are not shown). */
    private static List<ItemStack> inner(List<ItemStack> items, UnaryOperator<String> names) {
        List<ItemStack> previews = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            previews.add(item == null || item.isEmpty() ? ItemStack.empty() : of(item, true, names));
        }
        return previews;
    }
}
