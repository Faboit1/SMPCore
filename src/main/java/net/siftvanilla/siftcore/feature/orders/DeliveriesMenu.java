package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
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
 * The deliveries a player made to other players' orders, newest first: the item, how many, what they earned after tax,
 * whose order it was and when. The header shows what they earned over all their deliveries.
 */
final class DeliveriesMenu extends PagedMenu<OrderStore.Delivery> implements OrdersView {

    /** What the menu shows: the latest deliveries and the totals over all of them. */
    record Loaded(List<OrderStore.Delivery> rows, OrderStore.DeliveryTotals totals) {
    }

    private final OrderMenus menus;
    private final OrderService service;
    private final UUID seller;
    private final Loaded loaded;
    private final Runnable back;

    DeliveriesMenu(OrderMenus menus, Player viewer, UUID seller, Loaded loaded, Runnable back) {
        super(menus.service().services().menus(), viewer, title(menus.service(), viewer, seller), null, null, back);
        this.menus = menus;
        this.service = menus.service();
        this.seller = seller;
        this.loaded = loaded;
        this.back = back;
    }

    private static Component title(OrderService service, Player viewer, UUID seller) {
        Lang lang = service.services().lang();
        return Component.text(seller.equals(viewer.getUniqueId()) ? lang.plain(OrdersMessages.DELIVERIES_TITLE)
            : lang.plain(OrdersMessages.DELIVERIES_TITLE_OTHER, Arg.text("name", service.name(seller))));
    }

    @Override
    protected List<OrderStore.Delivery> entries() {
        return this.loaded.rows();
    }

    @Override
    protected ItemStack icon(OrderStore.Delivery delivery) {
        Lang lang = this.ctx.lang();
        String key = OrderKeys.key(delivery.itemType(), delivery.variant());
        OrderItem item = this.service.items().resolve(key);
        List<Component> lore = lang.lines(OrdersMessages.DELIVERIES_ENTRY, Arg.number("amount", delivery.quantity()),
            Arg.money("earned", delivery.earned()), Arg.text("buyer", this.service.name(delivery.buyer())),
            Arg.time("ago", Duration.ofMillis(Math.max(0, System.currentTimeMillis() - delivery.timestamp()))));
        return Items.display(OrderItem.icon(item, delivery.quantity()), lore);
    }

    @Override
    protected void clicked(OrderStore.Delivery delivery, ClickContext click) {
        // Information only.
    }

    @Override
    protected String searchText(OrderStore.Delivery delivery) {
        return this.service.items().searchText(OrderKeys.key(delivery.itemType(), delivery.variant()));
    }

    @Override
    protected void drawExtras() {
        Lang lang = this.ctx.lang();
        set(SLOT_EXTRA_1, Items.icon(Material.SUNFLOWER, lang.get(OrdersMessages.DELIVERIES_HEADER),
            lang.lines(OrdersMessages.DELIVERIES_HEADER_LORE, Arg.money("total", this.loaded.totals().earned()),
                Arg.number("count", this.loaded.totals().count()))), null);
        set(SLOT_EXTRA_2, Items.icon(Material.BOOK, lang.get(OrdersMessages.PAST_BUTTON), lang.lines(OrdersMessages.PAST_LORE)), click -> {
            click(Feedback.CLICK);
            this.menus.past(this.viewer, this.seller, this.back);
        });
    }
}
