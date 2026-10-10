package net.siftvanilla.siftcore.feature.shop;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.entity.Player;

/**
 * The shop's first screen: one icon per category at its configured slot, the search button and the player's
 * balance in the bottom row, and (with five rows or more) the player's last purchases as a "Buy again" row.
 */
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
        if (settings.searchSlot() >= 0 && settings.searchSlot() < size()) {
            set(settings.searchSlot(), this.shop.searchIcon(), click -> {
                click(Feedback.CLICK);
                this.shop.openSearch(this.viewer, null);
            });
        }
        int balance = settings.balanceSlot();
        if (balance < size()) {
            set(balance, this.shop.balanceIcon(this.viewer), null);
        }
        List<Integer> slots = settings.recentSlots();
        List<RecentPurchases.Recent> recent = this.shop.recent(this.viewer);
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            if (slot >= size()) {
                continue;
            }
            if (i < recent.size()) {
                RecentPurchases.Recent purchase = recent.get(i);
                set(slot, this.shop.recentIcon(purchase), click -> {
                    click(Feedback.CLICK);
                    this.shop.buyAgain(this.viewer, purchase, this::open);
                });
            } else {
                clear(slot);
            }
        }
    }
}
