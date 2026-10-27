package net.siftvanilla.siftcore.ui.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/** A dialog body element: a block of text or an item with an optional description. */
public sealed interface Body {

    /** Text; {@code error} marks a validation error line so a newer error replaces it. */
    record Text(Component text, int width, boolean error) implements Body {
    }

    record Item(ItemStack item, Component description, boolean showTooltip) implements Body {
    }

    static Body text(Component text) {
        return new Text(text, 250, false);
    }

    static Body error(Component text) {
        return new Text(text, 250, true);
    }

    static Body item(ItemStack item, Component description) {
        return new Item(item.clone(), description, true);
    }
}
