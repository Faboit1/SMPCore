package net.siftvanilla.siftcore.ui.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** A click on a menu button. */
public record ClickContext(Player player, ClickType type, int slot) {

    public boolean left() {
        return this.type.isLeftClick();
    }

    public boolean right() {
        return this.type.isRightClick();
    }

    public boolean shift() {
        return this.type.isShiftClick();
    }
}
