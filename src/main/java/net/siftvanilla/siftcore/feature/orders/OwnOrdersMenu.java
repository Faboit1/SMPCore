package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A player's orders: the active ones and the ended ones that still hold delivered items, newest first. The owner
 * manages an order by clicking it; the bottom row places a new order, collects everything that fits from every order
 * at once, and opens the past orders. Staff open the same list for any player (their view of it, with staff actions).
 */
final class OwnOrdersMenu extends PagedMenu<Order> implements OrdersView {

    private final OrderMenus menus;
    private final OrderService service;
    private final UUID owner;

    OwnOrdersMenu(OrderMenus menus, Player viewer, UUID owner, Runnable back) {
        super(menus.service().services().menus(), viewer, title(menus.service(), viewer, owner), null, null, back);
        this.menus = menus;
        this.service = menus.service();
        this.owner = owner;
    }

    private static Component title(OrderService service, Player viewer, UUID owner) {
        Lang lang = service.services().lang();
        return Component.text(owner.equals(viewer.getUniqueId()) ? lang.plain(OrdersMessages.OWN_LIST_TITLE)
            : lang.plain(OrdersMessages.OWN_LIST_TITLE_OTHER, Arg.text("name", service.name(owner))));
    }

    private boolean self() {
        return this.owner.equals(this.viewer.getUniqueId());
    }

    @Override
    protected List<Order> entries() {
        List<Order> list = new ArrayList<>(this.service.book().of(this.owner));
        list.sort(Comparator.comparing(Order::active).reversed().thenComparing(Comparator.comparingLong(Order::created).reversed()));
        return list;
    }

    @Override
    protected ItemStack icon(Order order) {
        Lang lang = this.ctx.lang();
        OrderItem item = this.service.items().of(order);
        long now = this.service.engine().now();
        List<Component> lore = new ArrayList<>();
        lore.addAll(lang.lines(OrdersMessages.ENTRY_PRICE, Arg.money("price", order.priceEach())));
        lore.addAll(lang.lines(OrdersMessages.ENTRY_DELIVERED, Arg.number("filled", order.filled()), Arg.number("quantity", order.quantity())));
        if (order.waiting() > 0) {
            lore.addAll(lang.lines(OrdersMessages.ENTRY_WAITING, Arg.number("waiting", order.waiting())));
        }
        if (order.active()) {
            lore.addAll(lang.lines(OrdersMessages.ENTRY_HELD, Arg.money("held", order.escrow())));
            lore.addAll(lang.lines(OrdersMessages.ENTRY_ENDS, Arg.time("time", Duration.ofMillis(order.millisLeft(now)))));
        } else {
            lore.addAll(lang.lines(OrdersMessages.ENTRY_STATE, Arg.component("state", lang.get(this.service.stateLabel(order.state())))));
        }
        if (item == null) {
            lore.addAll(lang.lines(OrdersMessages.ENTRY_UNAVAILABLE));
        }
        lore.add(Component.empty());
        lore.addAll(lang.lines(self() ? OrdersMessages.HINT_MANAGE : OrdersMessages.HINT_STAFF));
        return Items.display(OrderItem.icon(item, order.active() ? order.remaining() : order.waiting()), lore);
    }

    @Override
    protected void clicked(Order order, ClickContext click) {
        Order current = this.service.book().get(order.id());
        if (current == null) {
            redraw();
            return;
        }
        click(Feedback.CLICK);
        if (self()) {
            this.menus.dialogs().own(this.viewer, current, this::open);
        } else if (this.viewer.hasPermission(OrderService.PERMISSION_ADMIN)) {
            this.menus.dialogs().staff(this.viewer, current, this::open);
        }
    }

    @Override
    protected String searchText(Order order) {
        return this.service.items().searchText(order.key());
    }

    @Override
    protected void drawExtras() {
        Lang lang = this.ctx.lang();
        if (self() && this.viewer.hasPermission(OrderService.PERMISSION_CREATE)) {
            int limit = this.service.limit(this.viewer);
            int count = this.service.book().activeCount(this.owner);
            List<Component> newLore = limit <= 0 ? lang.lines(OrdersMessages.NEW_LORE_NONE)
                : limit == Limits.UNLIMITED ? lang.lines(OrdersMessages.NEW_LORE_UNLIMITED, Arg.number("count", count))
                : lang.lines(OrdersMessages.NEW_LORE, Arg.number("count", count), Arg.number("limit", limit));
            set(SLOT_EXTRA_1, Items.icon(Material.WRITABLE_BOOK, lang.get(OrdersMessages.NEW_BUTTON), newLore), click -> {
                click(Feedback.CLICK);
                this.menus.dialogs().createForm(this.viewer, this::open);
            });
        } else {
            clear(SLOT_EXTRA_1);
        }
        if (self()) {
            long waiting = 0;
            int orders = 0;
            for (Order order : this.service.book().of(this.owner)) {
                if (order.waiting() > 0) {
                    waiting += order.waiting();
                    orders++;
                }
            }
            List<Component> lore = waiting > 0
                ? lang.lines(OrdersMessages.COLLECT_ALL_LORE, Arg.number("waiting", waiting), Arg.number("count", orders))
                : lang.lines(OrdersMessages.COLLECT_ALL_EMPTY);
            set(SLOT_EXTRA_2, Items.icon(Material.HOPPER, lang.get(OrdersMessages.COLLECT_ALL_BUTTON), lore), click -> collectAll());
        } else {
            clear(SLOT_EXTRA_2);
        }
        set(SLOT_EXTRA_3, Items.icon(Material.BOOK, lang.get(OrdersMessages.PAST_BUTTON), lang.lines(OrdersMessages.PAST_LORE)), click -> {
            click(Feedback.CLICK);
            this.menus.past(this.viewer, this.owner, this::open);
        });
    }

    private void collectAll() {
        if (!lock()) {
            return;
        }
        OrderService.Problem problem;
        try {
            problem = this.service.collectAll(this.viewer);
        } finally {
            unlock();
        }
        if (problem != null) {
            this.service.send(this.viewer, problem);
        } else {
            click(Feedback.CLICK);
        }
        redraw();
    }
}
