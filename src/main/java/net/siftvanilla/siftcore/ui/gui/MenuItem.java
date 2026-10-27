package net.siftvanilla.siftcore.ui.gui;

import org.bukkit.inventory.ItemStack;

/** An icon in a menu slot and what clicking it does (null handler = decorative). */
public record MenuItem(ItemStack icon, Handler handler) {

    /** Runs on the viewer's thread. */
    @FunctionalInterface
    public interface Handler {
        void click(ClickContext click);
    }
}
