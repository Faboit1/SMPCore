package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /orders}: every open order, best price each first by default. Sort (price each, total, most wanted, newest,
 * ending soon) and the category filter are remembered per player. Clicking someone else's order opens its delivery
 * menu, a right click delivers straight from the inventory, clicking your own order manages it, and staff open the
 * staff actions with a shift right click. The bottom row adds a new order, your orders and your history.
 */
final class OrdersMenu extends PagedMenu<Order> implements OrdersView {

    static final String SORT_SETTING = "orders_sort";
    static final String FILTER_SETTING = "orders_filter";

    private final OrderMenus menus;
    private final OrderService service;
    private final Cycle<Comparator<Order>> sort;
    private final Cycle<Predicate<Order>> filter;
    private String savedSort;
    private String savedFilter;
    private Map<String, Integer> carried = Map.of();

    private OrdersMenu(OrderMenus menus, Player viewer, Cycle<Comparator<Order>> sort, Cycle<Predicate<Order>> filter) {
        super(menus.service().services().menus(), viewer, Component.text(menus.service().services().lang().plain(OrdersMessages.MENU_TITLE)),
            sort, filter, null);
        this.menus = menus;
        this.service = menus.service();
        this.sort = sort;
        this.filter = filter;
        this.savedSort = sort.selected().id();
        this.savedFilter = filter.selected().id();
    }

    static OrdersMenu create(OrderMenus menus, Player viewer, String query) {
        OrderService service = menus.service();
        Lang lang = service.services().lang();
        PlayerSettings settings = service.services().settings();
        List<Cycle.Option<Comparator<Order>>> sorts = new ArrayList<>();
        for (BrowserSort.Sort sort : BrowserSort.Sort.values()) {
            sorts.add(new Cycle.Option<>(sort.id(), lang.get(sortLabel(sort)), sort.comparator()));
        }
        List<Cycle.Option<Predicate<Order>>> filters = new ArrayList<>();
        filters.add(new Cycle.Option<>(BrowserSort.ALL, lang.get(OrdersMessages.FILTER_ALL), order -> true));
        for (ItemCategory category : BrowserSort.filters(service.items().orderableCategories())) {
            filters.add(new Cycle.Option<>(category.id(), lang.get(categoryLabel(category)),
                BrowserSort.filter(category.id(), service.items()::category)));
        }
        String sortId = BrowserSort.Sort.byId(settings.raw(viewer.getUniqueId(), SORT_SETTING, BrowserSort.Sort.PRICE.id())).id();
        String filterId = settings.raw(viewer.getUniqueId(), FILTER_SETTING, BrowserSort.ALL);
        OrdersMenu menu = new OrdersMenu(menus, viewer, new Cycle<>(sorts, sortId), new Cycle<>(filters, filterId));
        if (query != null && !query.isBlank()) {
            menu.query(query);
        }
        return menu;
    }

    static MessageKey sortLabel(BrowserSort.Sort sort) {
        return switch (sort) {
            case PRICE -> OrdersMessages.SORT_PRICE;
            case TOTAL -> OrdersMessages.SORT_TOTAL;
            case WANTED -> OrdersMessages.SORT_WANTED;
            case NEWEST -> OrdersMessages.SORT_NEWEST;
            case ENDING -> OrdersMessages.SORT_ENDING;
        };
    }

    static MessageKey categoryLabel(ItemCategory category) {
        return switch (category) {
            case BLOCKS -> OrdersMessages.CATEGORY_BLOCKS;
            case TOOLS -> OrdersMessages.CATEGORY_TOOLS;
            case COMBAT -> OrdersMessages.CATEGORY_COMBAT;
            case FOOD -> OrdersMessages.CATEGORY_FOOD;
            case POTIONS -> OrdersMessages.CATEGORY_POTIONS;
            case BOOKS -> OrdersMessages.CATEGORY_BOOKS;
            case SPAWNERS -> OrdersMessages.CATEGORY_SPAWNERS;
            case MISC -> OrdersMessages.CATEGORY_MISC;
        };
    }

    /** Remembers the sort and filter when the player changed them. */
    private void remember() {
        PlayerSettings settings = this.service.services().settings();
        String sortId = this.sort.selected().id();
        if (!sortId.equals(this.savedSort)) {
            settings.setRaw(this.viewer.getUniqueId(), SORT_SETTING, sortId);
            this.savedSort = sortId;
        }
        String filterId = this.filter.selected().id();
        if (!filterId.equals(this.savedFilter)) {
            settings.setRaw(this.viewer.getUniqueId(), FILTER_SETTING, filterId);
            this.savedFilter = filterId;
        }
    }

    @Override
    protected List<Order> entries() {
        remember();
        this.carried = this.service.items().carried(this.viewer.getInventory().getStorageContents());
        long now = this.service.engine().now();
        List<Order> list = new ArrayList<>();
        for (Order order : this.service.book().active()) {
            if (!order.expiredAt(now)) {
                list.add(order);
            }
        }
        return list;
    }

    @Override
    protected ItemStack icon(Order order) {
        Lang lang = this.ctx.lang();
        OrderItem item = this.service.items().of(order);
        long now = this.service.engine().now();
        boolean own = order.owner().equals(this.viewer.getUniqueId());
        int tax = this.service.settings().taxBasisPoints();
        List<Component> lore = new ArrayList<>();
        lore.addAll(lang.lines(OrdersMessages.ENTRY_PRICE, Arg.money("price", order.priceEach())));
        if (!own) {
            lore.addAll(lang.lines(OrdersMessages.ENTRY_NET, Arg.money("net", OrderMath.payout(order.priceEach(), tax)),
                Arg.text("tax", OrderMath.percent(tax))));
        }
        lore.addAll(lang.lines(OrdersMessages.ENTRY_DELIVERED, Arg.number("filled", order.filled()), Arg.number("quantity", order.quantity())));
        lore.addAll(lang.lines(OrdersMessages.ENTRY_OWNER, Arg.text("owner", this.service.name(order.owner()))));
        lore.addAll(lang.lines(OrdersMessages.ENTRY_ENDS, Arg.time("time", Duration.ofMillis(order.millisLeft(now)))));
        if (item != null) {
            long worth = this.service.worthEach(item);
            if (worth > 0) {
                lore.addAll(lang.lines(OrdersMessages.ENTRY_WORTH, Arg.money("worth", worth)));
            }
            int carry = this.carried.getOrDefault(order.key(), 0);
            if (carry > 0 && !own) {
                lore.addAll(lang.lines(OrdersMessages.ENTRY_CARRY, Arg.number("count", carry)));
            }
        } else {
            lore.addAll(lang.lines(OrdersMessages.ENTRY_UNAVAILABLE));
        }
        lore.add(Component.empty());
        if (own) {
            lore.addAll(lang.lines(OrdersMessages.HINT_OWN));
        } else {
            lore.addAll(lang.lines(OrdersMessages.HINT_DELIVER));
            lore.addAll(lang.lines(OrdersMessages.HINT_QUICK));
        }
        if (this.viewer.hasPermission(OrderService.PERMISSION_ADMIN)) {
            lore.addAll(lang.lines(OrdersMessages.HINT_STAFF));
        }
        return Items.display(OrderItem.icon(item, order.remaining()), lore);
    }

    @Override
    protected void clicked(Order order, ClickContext click) {
        Order current = this.service.book().get(order.id());
        if (current == null || !current.active()) {
            this.ctx.messenger().send(this.viewer, OrdersMessages.DELIVER_GONE);
            redraw();
            return;
        }
        click(Feedback.CLICK);
        Runnable back = this::open;
        if (click.shift() && click.right() && this.viewer.hasPermission(OrderService.PERMISSION_ADMIN)) {
            this.menus.dialogs().staff(this.viewer, current, back);
        } else if (current.owner().equals(this.viewer.getUniqueId())) {
            this.menus.dialogs().own(this.viewer, current, back);
        } else if (click.right()) {
            this.menus.dialogs().quick(this.viewer, current, back);
        } else {
            this.menus.delivery(this.viewer, current, back);
        }
    }

    @Override
    protected String searchText(Order order) {
        return this.service.items().searchText(order.key());
    }

    @Override
    protected void drawExtras() {
        Lang lang = this.ctx.lang();
        int limit = this.service.limit(this.viewer);
        int count = this.service.book().activeCount(this.viewer.getUniqueId());
        List<Component> newLore = limit <= 0 ? lang.lines(OrdersMessages.NEW_LORE_NONE)
            : limit == Limits.UNLIMITED ? lang.lines(OrdersMessages.NEW_LORE_UNLIMITED, Arg.number("count", count))
            : lang.lines(OrdersMessages.NEW_LORE, Arg.number("count", count), Arg.number("limit", limit));
        if (this.viewer.hasPermission(OrderService.PERMISSION_CREATE)) {
            set(SLOT_EXTRA_1, Items.icon(Material.WRITABLE_BOOK, lang.get(OrdersMessages.NEW_BUTTON), newLore), click -> {
                click(Feedback.CLICK);
                this.menus.dialogs().createForm(this.viewer, this::open);
            });
        } else {
            clear(SLOT_EXTRA_1);
        }
        long waiting = this.service.book().waiting(this.viewer.getUniqueId());
        set(SLOT_EXTRA_2, Items.icon(Material.CHEST, lang.get(OrdersMessages.YOURS_BUTTON),
            lang.lines(OrdersMessages.YOURS_LORE, Arg.number("waiting", waiting), Arg.number("count", count))), click -> {
                click(Feedback.CLICK);
                this.menus.own(this.viewer, null, this::open);
            });
        set(SLOT_EXTRA_3, Items.icon(Material.BOOK, lang.get(OrdersMessages.HISTORY_BUTTON), lang.lines(OrdersMessages.HISTORY_LORE)),
            click -> {
                click(Feedback.CLICK);
                this.menus.past(this.viewer, null, this::open);
            });
    }
}
