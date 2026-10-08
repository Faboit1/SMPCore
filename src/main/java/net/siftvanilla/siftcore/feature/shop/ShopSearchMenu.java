package net.siftvanilla.siftcore.feature.shop;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Every item the shop sells right now, across all categories: searchable by name, filtered by category and sorted
 * like a category page. Opened from the search button of the shop screen (with the search prompt first) or with
 * {@code /shop search <text>}. Clicking works like on a category page.
 */
final class ShopSearchMenu extends PagedMenu<ShopSettings.Entry> {

    private final ShopMenus shop;

    ShopSearchMenu(MenuContext ctx, Player viewer, ShopMenus shop, Component title, Cycle<Comparator<ShopSettings.Entry>> sort,
                   Cycle<Predicate<ShopSettings.Entry>> filter, Runnable back) {
        super(ctx, viewer, title, sort, filter, back);
        this.shop = shop;
    }

    /** Asks what to search for first, then opens the results (cancelling opens the full list). */
    void prompt() {
        this.ctx.dialogs().show(this.viewer, this.ctx.templates().form(
            this.ctx.lang().get(CoreMessages.UI_SEARCH_TITLE),
            List.of(),
            List.of(Templates.text("query", this.ctx.lang().get(CoreMessages.UI_SEARCH_INPUT), query(), 48)),
            submission -> {
                query(submission.values().text("query"));
                submission.close();
                open();
            },
            submission -> {
                submission.close();
                open();
            }).waiting());
    }

    @Override
    protected List<ShopSettings.Entry> entries() {
        return this.shop.allVisibleEntries();
    }

    @Override
    protected ItemStack icon(ShopSettings.Entry entry) {
        return this.shop.entryIcon(entry, this.viewer, this.shop.categoryName(entry));
    }

    @Override
    protected void clicked(ShopSettings.Entry entry, ClickContext click) {
        click(Feedback.CLICK);
        if (click.right() && !click.shift()) {
            this.shop.sell(this.viewer, entry, this::open);
            return;
        }
        this.shop.buy(this.viewer, entry, this::open);
    }

    @Override
    protected String searchText(ShopSettings.Entry entry) {
        return this.shop.searchText(entry);
    }
}
