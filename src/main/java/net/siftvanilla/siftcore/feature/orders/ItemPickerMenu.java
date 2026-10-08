package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Choosing the item of a new order: every item that can be ordered (the registry minus blocked items), plus the
 * variant families (enchanted books, potions, spawners) that open a list to choose the exact one. Sort by name or by
 * how often it was ordered in the last 30 days, filter by category, search by name. Each icon shows what the server
 * pays and the open orders for it. Clicking goes back to the form with the item set; what the player typed is kept.
 */
final class ItemPickerMenu extends PagedMenu<String> implements OrdersView {

    private final OrderMenus menus;
    private final OrderService service;
    /** Where the new-order form goes back to (the screen that opened the form), or null to close. */
    private final Runnable formBack;
    /** Open orders of a key: how many, and the best price each. */
    private record Open(int count, long best) {
    }

    private Map<String, Open> open = Map.of();

    private ItemPickerMenu(OrderMenus menus, Player viewer, Cycle<Comparator<String>> sort, Cycle<Predicate<String>> filter,
                           Runnable formBack) {
        super(menus.service().services().menus(), viewer, Component.text(menus.service().services().lang().plain(OrdersMessages.PICKER_TITLE)),
            sort, filter, () -> menus.dialogs().createForm(viewer, formBack));
        this.menus = menus;
        this.service = menus.service();
        this.formBack = formBack;
    }

    /**
     * The picker of a new-order form. Its back button returns to the form with what was typed; choosing an item returns
     * to the form with the item set. {@code formBack} is where the form itself goes back to.
     */
    static ItemPickerMenu create(OrderMenus menus, Player viewer, Runnable formBack) {
        OrderService service = menus.service();
        OrderItems items = service.items();
        Lang lang = service.services().lang();
        Map<String, Integer> popularity = service.popularity();
        Comparator<String> byName = Comparator.comparing(items::plainName, String.CASE_INSENSITIVE_ORDER).thenComparing(key -> key);
        Comparator<String> byPopularity = Comparator.<String>comparingInt(key -> popularity(popularity, key)).reversed().thenComparing(byName);
        Cycle<Comparator<String>> sort = new Cycle<>(List.of(
            new Cycle.Option<>("name", lang.get(OrdersMessages.PICKER_SORT_NAME), byName),
            new Cycle.Option<>("popular", lang.get(OrdersMessages.PICKER_SORT_POPULAR), byPopularity)), "name");
        List<Cycle.Option<Predicate<String>>> filters = new ArrayList<>();
        filters.add(new Cycle.Option<>(BrowserSort.ALL, lang.get(OrdersMessages.FILTER_ALL), key -> true));
        for (ItemCategory category : BrowserSort.filters(items.orderableCategories())) {
            filters.add(new Cycle.Option<>(category.id(), lang.get(OrdersMenu.categoryLabel(category)),
                key -> items.category(OrderKeys.itemType(key)) == category));
        }
        return new ItemPickerMenu(menus, viewer, sort, new Cycle<>(filters, BrowserSort.ALL), formBack);
    }

    /** How often an item (or a whole variant family) was ordered lately. */
    private static int popularity(Map<String, Integer> popularity, String key) {
        if (!OrderItems.variantOnly(key)) {
            return popularity.getOrDefault(key, 0);
        }
        int total = 0;
        for (Map.Entry<String, Integer> entry : popularity.entrySet()) {
            if (OrderKeys.itemType(entry.getKey()).equals(key)) {
                total += entry.getValue();
            }
        }
        return total;
    }

    @Override
    protected List<String> entries() {
        // Open orders per key; a variant family adds up all of its variants.
        long now = this.service.engine().now();
        Map<String, Open> counts = new HashMap<>();
        this.service.book().bidsByKey().forEach((key, bids) -> {
            String target = OrderItems.variantOnly(OrderKeys.itemType(key)) ? OrderKeys.itemType(key) : key;
            for (Order order : bids) {
                if (!order.expiredAt(now)) {
                    counts.merge(target, new Open(1, order.priceEach()),
                        (a, b) -> new Open(a.count() + b.count(), Math.max(a.best(), b.best())));
                }
            }
        });
        this.open = counts;
        List<String> keys = new ArrayList<>(this.service.items().plainOrderable());
        keys.addAll(this.service.items().families());
        return keys;
    }

    @Override
    protected ItemStack icon(String key) {
        Lang lang = this.ctx.lang();
        OrderItems items = this.service.items();
        List<Component> lore = new ArrayList<>();
        boolean family = OrderItems.variantOnly(key);
        if (family) {
            lore.addAll(lang.lines(OrdersMessages.PICKER_FAMILY));
        } else {
            OrderItem item = items.resolve(key);
            long worth = item == null ? 0 : this.service.worthEach(item);
            if (worth > 0) {
                lore.addAll(lang.lines(OrdersMessages.PICKER_WORTH, Arg.money("price", worth)));
            }
        }
        Open open = this.open.get(key);
        if (open != null && open.count() > 0) {
            lore.addAll(lang.lines(OrdersMessages.PICKER_OPEN, Arg.number("count", open.count()), Arg.money("price", open.best())));
        }
        lore.add(Component.empty());
        lore.addAll(lang.lines(OrdersMessages.PICKER_HINT));
        return Items.display(ItemStack.of(items.material(OrderKeys.itemType(key))), lore);
    }

    @Override
    protected void clicked(String key, ClickContext click) {
        click(Feedback.CLICK);
        OrderDialogs dialogs = this.menus.dialogs();
        Runnable reopen = this::open;
        if (OrderItems.variantOnly(key)) {
            dialogs.variants(this.viewer, key, reopen, this.formBack);
        } else {
            dialogs.chosen(this.viewer, key, this.formBack);
        }
    }

    @Override
    protected String searchText(String key) {
        return this.service.items().searchText(key);
    }
}
