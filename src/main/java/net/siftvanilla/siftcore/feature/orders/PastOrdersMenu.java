package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
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
 * A player's finished orders (complete, cancelled or ended), newest first, as read from storage when the menu opened:
 * what was delivered, paid out and refunded, and when it ended. The owner clicks one to place it again with the same
 * item, quantity and price (through every normal check). The bottom row switches to the deliveries the player made.
 */
final class PastOrdersMenu extends PagedMenu<OrderStore.Past> implements OrdersView {

    private final OrderMenus menus;
    private final OrderService service;
    private final UUID owner;
    private final List<OrderStore.Past> rows;
    private final Runnable back;

    PastOrdersMenu(OrderMenus menus, Player viewer, UUID owner, List<OrderStore.Past> rows, Runnable back) {
        super(menus.service().services().menus(), viewer, title(menus.service(), viewer, owner), null, null, back);
        this.menus = menus;
        this.service = menus.service();
        this.owner = owner;
        this.rows = List.copyOf(rows);
        this.back = back;
    }

    private static Component title(OrderService service, Player viewer, UUID owner) {
        Lang lang = service.services().lang();
        return Component.text(owner.equals(viewer.getUniqueId()) ? lang.plain(OrdersMessages.PAST_TITLE)
            : lang.plain(OrdersMessages.PAST_TITLE_OTHER, Arg.text("name", service.name(owner))));
    }

    private boolean self() {
        return this.owner.equals(this.viewer.getUniqueId());
    }

    @Override
    protected List<OrderStore.Past> entries() {
        return this.rows;
    }

    @Override
    protected ItemStack icon(OrderStore.Past past) {
        Lang lang = this.ctx.lang();
        Order order = past.order();
        OrderItem item = this.service.items().of(order);
        long now = System.currentTimeMillis();
        long endedAt = order.ended() > 0 ? order.ended() : order.expires();
        List<Component> lore = new ArrayList<>(lang.lines(OrdersMessages.PAST_LORE_ENTRY,
            Arg.component("state", lang.get(this.service.stateLabel(order.state()))), Arg.number("filled", order.filled()),
            Arg.number("quantity", order.quantity()), Arg.money("price", order.priceEach()), Arg.money("paid", past.paidOut()),
            Arg.time("ago", Duration.ofMillis(Math.max(0, now - endedAt)))));
        if (order.refunded() > 0) {
            lore.addAll(lang.lines(OrdersMessages.PAST_REFUNDED, Arg.money("refunded", order.refunded())));
        }
        if (order.waiting() > 0) {
            lore.addAll(lang.lines(OrdersMessages.ENTRY_WAITING, Arg.number("waiting", order.waiting())));
        }
        if (self() && item != null) {
            lore.add(Component.empty());
            lore.addAll(lang.lines(OrdersMessages.PAST_AGAIN));
        }
        return Items.display(OrderItem.icon(item, order.quantity()), lore);
    }

    @Override
    protected void clicked(OrderStore.Past past, ClickContext click) {
        if (!self() || this.service.items().of(past.order()) == null) {
            return;
        }
        click(Feedback.CLICK);
        this.menus.dialogs().orderAgain(this.viewer, past.order(), this::open);
    }

    @Override
    protected String searchText(OrderStore.Past past) {
        return this.service.items().searchText(past.order().key());
    }

    @Override
    protected void drawExtras() {
        Lang lang = this.ctx.lang();
        set(SLOT_EXTRA_1, Items.icon(Material.EMERALD, lang.get(OrdersMessages.DELIVERIES_BUTTON), lang.lines(OrdersMessages.DELIVERIES_BUTTON_LORE)),
            click -> {
                click(Feedback.CLICK);
                this.menus.deliveries(this.viewer, this.owner, this.back);
            });
    }
}
