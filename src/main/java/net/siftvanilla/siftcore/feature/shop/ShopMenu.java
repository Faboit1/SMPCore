package net.siftvanilla.siftcore.feature.shop;

import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.entity.Player;

/** The shop's first screen: one icon per category at its configured slot, and the player's balance. */
final class ShopMenu extends Menu {

    private final ShopMenus shop;

    ShopMenu(MenuContext ctx, Player viewer, ShopMenus shop, int rows) {
        super(ctx, viewer, Component.text(ctx.lang().plain(ShopMessages.MENU_TITLE)), rows);
        this.shop = shop;
    }

    @Override
    protected void draw() {
        ShopSettings settings = this.shop.settings();
        for (ShopSettings.Category category : this.shop.visibleCategories()) {
            if (category.slot() >= size()) {
                continue;
            }
            int count = this.shop.visibleEntries(category.id()).size();
            String id = category.id();
            set(category.slot(), this.shop.categoryIcon(category, count), click -> {
                click(Feedback.CLICK);
                this.shop.openCategory(this.viewer, id);
            });
        }
        int balance = settings.balanceSlot();
        if (balance < size()) {
            set(balance, this.shop.balanceIcon(this.viewer), null);
        }
    }
}
