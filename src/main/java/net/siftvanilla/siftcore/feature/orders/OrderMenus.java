package net.siftvanilla.siftcore.feature.orders;

import java.util.UUID;
import java.util.logging.Level;
import net.siftvanilla.siftcore.core.CoreMessages;
import org.bukkit.entity.Player;

/**
 * Opens the orders chest menus with consistent back buttons. Every method runs on the viewer's thread; histories are
 * read off-thread first and the menu opens once they arrived.
 */
final class OrderMenus {

    private final OrderService service;
    private OrderDialogs dialogs;

    OrderMenus(OrderService service) {
        this.service = service;
    }

    /** Wires the dialogs (they and the menus open each other). */
    void dialogs(OrderDialogs dialogs) {
        this.dialogs = dialogs;
    }

    OrderDialogs dialogs() {
        return this.dialogs;
    }

    OrderService service() {
        return this.service;
    }

    /** The orders browser, optionally searching. */
    void browser(Player player, String query) {
        if (!this.service.usable(player)) {
            return;
        }
        this.service.limit(player);
        OrdersMenu.create(this, player, query).open();
    }

    /** The orders of {@code owner} (the viewer's own when null or the viewer). */
    void own(Player viewer, UUID owner, Runnable back) {
        if (!this.service.usable(viewer)) {
            return;
        }
        UUID target = owner == null ? viewer.getUniqueId() : owner;
        new OwnOrdersMenu(this, viewer, target, back).open();
    }

    /** The past orders of {@code owner}, read from storage first. */
    void past(Player viewer, UUID owner, Runnable back) {
        UUID target = owner == null ? viewer.getUniqueId() : owner;
        this.service.services().messenger().send(viewer, OrdersMessages.HISTORY_LOADING);
        this.service.store().history(target, this.service.settings().historyEntries()).whenComplete((rows, error) -> {
            if (error != null) {
                this.service.services().plugin().getLogger().log(Level.WARNING, "Loading the order history of " + target + " failed", error);
                this.service.services().messenger().send(viewer, CoreMessages.ACTION_FAILED);
                return;
            }
            this.service.services().scheduler().entity(viewer, () -> new PastOrdersMenu(this, viewer, target, rows, back).open(), null);
        });
    }

    /** The deliveries {@code seller} made to other players' orders, read from storage first. */
    void deliveries(Player viewer, UUID seller, Runnable back) {
        UUID target = seller == null ? viewer.getUniqueId() : seller;
        this.service.services().messenger().send(viewer, OrdersMessages.HISTORY_LOADING);
        this.service.store().deliveries(target, this.service.settings().historyEntries())
            .thenCombine(this.service.store().deliveryTotals(target), DeliveriesMenu.Loaded::new)
            .whenComplete((loaded, error) -> {
                if (error != null) {
                    this.service.services().plugin().getLogger().log(Level.WARNING, "Loading the deliveries of " + target + " failed", error);
                    this.service.services().messenger().send(viewer, CoreMessages.ACTION_FAILED);
                    return;
                }
                this.service.services().scheduler().entity(viewer, () -> new DeliveriesMenu(this, viewer, target, loaded, back).open(), null);
            });
    }

    /** The delivery menu of someone's order. */
    void delivery(Player player, Order order, Runnable back) {
        if (!this.service.usable(player)) {
            return;
        }
        OrderService.Problem problem = this.service.deliverable(player, order, order.priceEach());
        if (problem != null) {
            this.service.send(player, problem);
            return;
        }
        DeliveryMenu menu = DeliveryMenu.create(this.service, player, order, back);
        if (menu == null) {
            this.service.services().messenger().send(player, OrdersMessages.DELIVER_UNAVAILABLE);
            return;
        }
        menu.open();
    }

    /** The item picker of the new-order form; {@code formBack} is where the form goes back to. */
    void picker(Player player, Runnable formBack) {
        if (!this.service.usable(player)) {
            return;
        }
        ItemPickerMenu.create(this, player, formBack).open();
    }
}
