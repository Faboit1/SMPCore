package net.siftvanilla.siftcore.feature.shop;

import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The items of one shop category, with paging, sorting and search. Entries are read from the current config on
 * every redraw, so a reload shows up the next time the page is drawn. Clicking an item opens its purchase dialog;
 * leaving the dialog comes back to this page as it was.
 */
final class CategoryMenu extends PagedMenu<ShopSettings.Entry> {

    private final ShopMenus shop;
    private final String category;

    CategoryMenu(MenuContext ctx, Player viewer, ShopMenus shop, String category, Component title,
                 Cycle<Comparator<ShopSettings.Entry>> sort, Runnable back) {
        super(ctx, viewer, title, sort, null, back);
        this.shop = shop;
        this.category = category;
    }

    @Override
    protected List<ShopSettings.Entry> entries() {
        return this.shop.visibleEntries(this.category);
    }

    @Override
    protected ItemStack icon(ShopSettings.Entry entry) {
        return this.shop.entryIcon(entry);
    }

    @Override
    protected void clicked(ShopSettings.Entry entry, ClickContext click) {
        click(Feedback.CLICK);
        this.shop.buy(this.viewer, entry, this::open);
    }

    @Override
    protected String searchText(ShopSettings.Entry entry) {
        return this.shop.searchText(entry);
    }
}
