package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Tells owners what happened to their orders: right away when they are online (deliveries as their
 * {@code order-notices} setting says, also when auto-collect put them into the inventory, ending warnings unless they
 * turned {@code order-ending-alerts} off), otherwise
 * as a notice row (V021) that is summed up when they next join ({@code order-join-summary}). Refunds and staff
 * cancellations always reach the owner. Also announces big new orders to players whose {@code orders_announce}
 * filter shows them, unless the owner turned {@code order-announce-mine} off.
 * <p>
 * Notices are best effort: the money is always in the ledger, a lost notice only means a missing chat line.
 */
final class OwnerNotices {

    /** Detail lines of the join summary at most. */
    static final int MAX_DETAILS = 4;
    /** Ticks after joining before the summary shows (after the join messages). */
    private static final long JOIN_DELAY_TICKS = 40L;

    private final Services services;
    private final OrderStore store;
    private final OrderItems items;
    private final Supplier<OrdersSettings> settings;
    private final VanishStatus vanish;
    private final IgnoreLookup ignores;
    private final Logger logger;
    private final Map<UUID, Long> lastAnnounced = new ConcurrentHashMap<>();

    OwnerNotices(Services services, OrderStore store, OrderItems items, Supplier<OrdersSettings> settings, VanishStatus vanish,
                 IgnoreLookup ignores) {
        this.services = services;
        this.store = store;
        this.items = items;
        this.settings = settings;
        this.vanish = vanish;
        this.ignores = ignores;
        this.logger = services.plugin().getLogger();
    }

    private Lang lang() {
        return this.services.lang();
    }

    private Arg item(String key) {
        return Arg.component("item", this.items.name(key));
    }

    private DeliveryAlerts alerts(UUID owner) {
        return this.services.settings().get(owner, OrdersFeature.NOTIFICATIONS);
    }

    /**
     * The line an online owner gets when items arrive for one order, or null for none: every delivery (or the
     * completion, when it was the last of them) for chat and the hotbar, only the completion for "only when complete".
     */
    static MessageKey deliveryLine(DeliveryAlerts alerts, boolean complete, boolean sold) {
        if (complete) {
            return alerts.completions() ? OrdersMessages.NOTIFY_COMPLETE : null;
        }
        if (!alerts.everyDelivery()) {
            return null;
        }
        return sold ? OrdersMessages.NOTIFY_SOLD : OrdersMessages.NOTIFY_DELIVERED;
    }

    private void store(UUID owner, long orderId, String kind, int units, long amount, String detail) {
        this.store.notice(owner, orderId, kind, units, amount, detail, System.currentTimeMillis()).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not store an order notice for " + this.services.directory().name(owner), error);
            }
        });
    }

    // ------------------------------------------------------------------ events of an order

    /**
     * Items that reached one of the owner's orders in one go.
     *
     * @param order    the order (its id, owner, item and quantity)
     * @param units    how many arrived
     * @param paid     what they were paid (before tax)
     * @param complete whether they were the last the order wanted
     */
    record Part(Order order, int units, long paid, boolean complete) {
    }

    /**
     * Items that reached one owner's orders in one go: a delivery to one order, or one sale (sell routing) that filled
     * one or several of their orders.
     *
     * @param sold whether they came from a sale rather than a delivery
     */
    record Arrival(UUID owner, String sellerName, boolean sold, List<Part> parts) {
        Arrival {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("An arrival needs at least one order");
            }
        }

        List<Long> orderIds() {
            return this.parts.stream().map(part -> part.order().id()).toList();
        }

        /** How many items arrived in all. */
        int units() {
            return (int) Math.min(Integer.MAX_VALUE, this.parts.stream().mapToLong(Part::units).sum());
        }

        /** The one item that arrived, or null when they were several kinds. */
        String key() {
            String key = this.parts.getFirst().order().key();
            for (Part part : this.parts) {
                if (!part.order().key().equals(key)) {
                    return null;
                }
            }
            return key;
        }

        /** The orders the arrival completed, in order. */
        List<Order> completed() {
            return this.parts.stream().filter(Part::complete).map(Part::order).toList();
        }
    }

    /**
     * What an online owner is told about an arrival: a main line about the items (null: none) with {@code done}
     * completed orders folded into its end, then one line per completed order (an entry may be null: none), all
     * where their {@code order-notices} setting puts them.
     */
    record Lines(MessageKey main, int done, List<MessageKey> completed) {
    }

    /**
     * The lines for items that reached several of an owner's orders in one sale: who sold how many, then a line per
     * order the sale completed. Above the hotbar a second line would replace the first at once, so there the
     * completions are folded into the one line.
     */
    static Lines saleLines(DeliveryAlerts alerts, boolean oneItem, int completed) {
        MessageKey sold = oneItem ? OrdersMessages.NOTIFY_SOLD : OrdersMessages.NOTIFY_SOLD_MANY;
        List<MessageKey> each = Collections.nCopies(completed, alerts.completions() ? OrdersMessages.NOTIFY_COMPLETE : null);
        return switch (alerts) {
            case OFF, COMPLETE -> new Lines(null, 0, each);
            case CHAT -> new Lines(sold, 0, each);
            case ACTIONBAR -> completed == 0 ? new Lines(sold, 0, List.of())
                : new Lines(OrdersMessages.NOTIFY_SOLD_DONE, completed, Collections.nCopies(completed, null));
        };
    }

    /**
     * The lines for items that arrived and went into the owner's inventory by auto-collect: nothing for "never",
     * only completions for "only when complete", otherwise one line that says the items are in the inventory (and how
     * many still wait when not all fit). A completed order whose items were all collected says so; one whose items
     * partly wait says to collect them. Above the hotbar the completions are folded into the one line.
     *
     * @param waiting          whether items of these orders still wait (not all fit)
     * @param completedWaiting for each completed order, whether some of its items still wait
     */
    static Lines autoLines(DeliveryAlerts alerts, boolean waiting, List<Boolean> completedWaiting) {
        List<MessageKey> each = new ArrayList<>(completedWaiting.size());
        for (boolean left : completedWaiting) {
            each.add(!alerts.completions() || alerts == DeliveryAlerts.ACTIONBAR ? null
                : left ? OrdersMessages.NOTIFY_COMPLETE : OrdersMessages.NOTIFY_AUTO_COMPLETE);
        }
        MessageKey main = !alerts.everyDelivery() ? null
            : waiting ? OrdersMessages.NOTIFY_AUTO_COLLECTED_SOME : OrdersMessages.NOTIFY_AUTO_COLLECTED;
        int done = alerts == DeliveryAlerts.ACTIONBAR ? completedWaiting.size() : 0;
        return new Lines(main, done, each);
    }

    /**
     * Tells the owner about items that arrived for their orders (after the delivery was stored): online, as their
     * {@code order-notices} setting says; away, a notice row per order for their join summary.
     *
     * @param owner the owner when online, or null when they are away (or left before they could be told)
     */
    void tell(Player owner, Arrival arrival) {
        if (owner == null || !owner.isOnline()) {
            for (Part part : arrival.parts()) {
                store(arrival.owner(), part.order().id(), NoticeSummary.DELIVERED, part.units(), part.paid(), arrival.sellerName());
                if (part.complete()) {
                    store(arrival.owner(), part.order().id(), NoticeSummary.COMPLETE, part.order().quantity(), 0, null);
                }
            }
            return;
        }
        DeliveryAlerts alerts = alerts(arrival.owner());
        if (arrival.parts().size() == 1) {
            Part part = arrival.parts().getFirst();
            MessageKey line = deliveryLine(alerts, part.complete(), arrival.sold());
            if (line == OrdersMessages.NOTIFY_COMPLETE) {
                completed(owner, alerts.place(), line, part.order());
            } else if (line != null) {
                this.services.messenger().alert(owner, alerts.place(), line, Arg.text("name", arrival.sellerName()),
                    Arg.number("amount", part.units()), item(part.order().key()));
            }
            return;
        }
        show(owner, alerts, arrival, saleLines(alerts, arrival.key() != null, arrival.completed().size()), 0);
    }

    /**
     * Tells an online owner that items which arrived for their orders went into their inventory (auto-collect), as
     * their {@code order-notices} setting says. Owner's thread, after the hand-over.
     *
     * @param waitingAfter what still waits in each of the arrival's orders now
     */
    void collected(Player owner, Arrival arrival, Map<Long, Integer> waitingAfter) {
        DeliveryAlerts alerts = alerts(arrival.owner());
        List<Boolean> completedWaiting = new ArrayList<>();
        for (Order order : arrival.completed()) {
            completedWaiting.add(waitingAfter.getOrDefault(order.id(), 0) > 0);
        }
        long waiting = waitingAfter.values().stream().mapToLong(Integer::longValue).sum();
        show(owner, alerts, arrival, autoLines(alerts, waiting > 0, completedWaiting), waiting);
    }

    private void show(Player owner, DeliveryAlerts alerts, Arrival arrival, Lines lines, long waiting) {
        if (lines.main() != null) {
            String key = arrival.key();
            Component done = lines.done() <= 0 ? Component.empty()
                : lines.done() == 1 ? lang().get(OrdersMessages.NOTIFY_DONE_ONE)
                : lang().get(OrdersMessages.NOTIFY_DONE_MANY, Arg.number("count", lines.done()));
            this.services.messenger().alert(owner, alerts.place(), lines.main(), Arg.text("name", arrival.sellerName()),
                Arg.number("amount", arrival.units()),
                key == null ? Arg.component("item", lang().get(OrdersMessages.NOTIFY_ITEMS)) : item(key),
                Arg.number("waiting", waiting), Arg.component("done", done));
        }
        List<Order> completed = arrival.completed();
        for (int i = 0; i < completed.size() && i < lines.completed().size(); i++) {
            MessageKey line = lines.completed().get(i);
            if (line != null) {
                completed(owner, alerts.place(), line, completed.get(i));
            }
        }
    }

    private void completed(Player owner, AlertStyle place, MessageKey line, Order order) {
        this.services.messenger().alert(owner, place, line, Arg.number("quantity", order.quantity()), item(order.key()));
    }

    /** An order ran out of time and its money came back (always told). */
    void expired(Order order, long refund) {
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null) {
            store(order.owner(), order.id(), NoticeSummary.EXPIRED, order.quantity(), refund, null);
            return;
        }
        this.services.messenger().send(owner, OrdersMessages.NOTIFY_EXPIRED, Arg.number("quantity", order.quantity()), item(order.key()),
            Arg.money("refund", refund));
    }

    /** Staff cancelled an order and its money came back (always told, with the reason). */
    void staffCancelled(Order order, long refund, String reason) {
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null) {
            store(order.owner(), order.id(), NoticeSummary.CANCELLED, order.quantity(), refund, reason);
            return;
        }
        this.services.messenger().send(owner, OrdersMessages.NOTIFY_STAFF_CANCELLED, Arg.number("quantity", order.quantity()),
            item(order.key()), Arg.money("refund", refund), Arg.text("reason", reason));
    }

    /** The order ends soon (once per order); the message opens the order. Owners can turn it off (order-ending-alerts). */
    void ending(Order order) {
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null) {
            store(order.owner(), order.id(), NoticeSummary.ENDING, order.quantity(), 0, null);
            return;
        }
        if (!this.services.settings().get(order.owner(), OrdersFeature.ENDING_ALERTS)) {
            return;
        }
        Component text = lang().get(OrdersMessages.NOTIFY_ENDING, Arg.number("quantity", order.quantity()), item(order.key()),
            Arg.time("time", Duration.ofMillis(order.millisLeft(System.currentTimeMillis()))))
            .clickEvent(ClickEvent.runCommand("/orders order " + order.id()))
            .hoverEvent(HoverEvent.showText(lang().get(OrdersMessages.NOTIFY_ENDING_HOVER)));
        owner.sendMessage(text);
        this.services.messenger().feedback(owner, Feedback.NOTIFY);
    }

    // ------------------------------------------------------------------ joining

    /** Shows the owner's notices and waiting items a moment after they join, then deletes the notices shown. */
    void joined(Player player, OrderBook book) {
        this.services.scheduler().entityLater(player, () -> this.store.notices(player.getUniqueId()).whenComplete((rows, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not read the order notices of " + player.getName(), error);
                return;
            }
            this.services.scheduler().entity(player, () -> show(player, rows, book.waiting(player.getUniqueId())), null);
        }), null, JOIN_DELAY_TICKS);
    }

    private void show(Player player, List<OrderStore.NoticeRow> rows, long waiting) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean wanted = this.services.settings().get(id, OrdersFeature.JOIN_SUMMARY);
        NoticeSummary.Filter filter = NoticeSummary.Filter.of(wanted, alerts(id), this.services.settings().get(id, OrdersFeature.ENDING_ALERTS));
        NoticeSummary summary = NoticeSummary.of(rows, filter, MAX_DETAILS);
        boolean remind = wanted && waiting > 0 && this.settings.get().joinReminder();
        if (summary.empty() && !remind) {
            if (!rows.isEmpty()) {
                this.store.deleteNotices(player.getUniqueId(), rows);
            }
            return;
        }
        Lang lang = lang();
        List<Component> lines = new ArrayList<>();
        if (!summary.empty()) {
            List<Component> parts = new ArrayList<>();
            if (summary.delivered() > 0) {
                parts.add(lang.get(OrdersMessages.AWAY_DELIVERED, Arg.number("count", summary.delivered())));
            }
            if (summary.complete() > 0) {
                parts.add(lang.get(OrdersMessages.AWAY_COMPLETE, Arg.number("count", summary.complete())));
            }
            if (summary.refunded() > 0) {
                parts.add(lang.get(OrdersMessages.AWAY_REFUNDED, Arg.money("refund", summary.refunded())));
            }
            Component header = lang.get(OrdersMessages.AWAY_HEADER);
            if (!parts.isEmpty()) {
                header = header.append(Component.space())
                    .append(Component.join(JoinConfiguration.separator(lang.get(OrdersMessages.AWAY_SEPARATOR)), parts))
                    .append(lang.get(OrdersMessages.AWAY_END));
            }
            lines.add(header);
            for (OrderStore.NoticeRow row : summary.details()) {
                lines.add(detail(row));
            }
            if (summary.more() > 0) {
                lines.add(lang.get(OrdersMessages.AWAY_MORE, Arg.number("count", summary.more())));
            }
        }
        if (remind) {
            lines.add(lang.get(OrdersMessages.AWAY_WAITING, Arg.number("amount", waiting)));
        }
        lines.add(lang.get(OrdersMessages.AWAY_OPEN)
            .clickEvent(ClickEvent.runCommand("/orders mine"))
            .hoverEvent(HoverEvent.showText(lang.get(OrdersMessages.AWAY_OPEN_HOVER))));
        player.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
        this.services.messenger().feedback(player, Feedback.NOTIFY);
        if (!rows.isEmpty()) {
            this.store.deleteNotices(player.getUniqueId(), rows).whenComplete((ignored, error) -> {
                if (error != null) {
                    this.logger.log(Level.WARNING, "Could not delete the order notices of " + player.getName(), error);
                }
            });
        }
    }

    private Component detail(OrderStore.NoticeRow row) {
        Lang lang = lang();
        Arg item = row.itemType() == null ? Arg.component("item", lang.get(OrdersMessages.AWAY_UNKNOWN_ITEM))
            : item(OrderKeys.key(row.itemType(), row.variant()));
        Arg quantity = Arg.number("quantity", row.quantity() > 0 ? row.quantity() : row.units());
        MessageKey key;
        Arg[] args;
        switch (row.kind()) {
            case NoticeSummary.DELIVERED -> {
                key = OrdersMessages.AWAY_LINE_DELIVERED;
                args = new Arg[] {Arg.number("amount", row.units()), item, Arg.text("name", row.detail() == null ? "" : row.detail())};
            }
            case NoticeSummary.COMPLETE -> {
                key = OrdersMessages.AWAY_LINE_COMPLETE;
                args = new Arg[] {quantity, item};
            }
            case NoticeSummary.EXPIRED -> {
                key = OrdersMessages.AWAY_LINE_EXPIRED;
                args = new Arg[] {quantity, item, Arg.money("refund", row.amount())};
            }
            case NoticeSummary.CANCELLED -> {
                key = OrdersMessages.AWAY_LINE_CANCELLED;
                args = new Arg[] {quantity, item, Arg.money("refund", row.amount()), Arg.text("reason", row.detail() == null ? "" : row.detail())};
            }
            default -> {
                key = OrdersMessages.AWAY_LINE_ENDING;
                args = new Arg[] {quantity, item};
            }
        }
        return lang.get(key, args);
    }

    // ------------------------------------------------------------------ announcements

    /**
     * Announces a new order that holds at least the configured amount, at most once per cooldown per owner, to every
     * online player whose {@code orders_announce} filter shows an order holding that much and who does not ignore the
     * owner. Vanished owners and owners who turned {@code order-announce-mine} off ({@code ownerAllows}, read when
     * they placed it) are never announced, and their cooldown is left alone.
     */
    void announce(Order order, String ownerName, boolean ownerAllows) {
        OrdersSettings s = this.settings.get();
        if (!announced(s.announceMinTotal(), order.escrow(), ownerAllows) || this.vanish.vanished(order.owner())) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = s.announceCooldown().toMillis();
        Long last = this.lastAnnounced.get(order.owner());
        if (last != null && now - last < cooldown) {
            return;
        }
        this.lastAnnounced.put(order.owner(), now);
        String search = OrderItems.path(order.itemType());
        Component text = lang().get(OrdersMessages.ANNOUNCE, Arg.text("name", ownerName), Arg.number("quantity", order.quantity()),
                item(order.key()), Arg.money("price", order.priceEach()))
            .clickEvent(ClickEvent.runCommand("/orders " + search))
            .hoverEvent(HoverEvent.showText(lang().get(OrdersMessages.ANNOUNCE_HOVER)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!this.services.settings().get(id, OrdersFeature.ANNOUNCEMENTS).shows(order.escrow()) || this.ignores.ignores(id, order.owner())) {
                continue;
            }
            player.sendMessage(text);
        }
    }

    /**
     * Whether a new order is announced at all: the server announces orders holding at least {@code minTotal} (0:
     * never) and the owner allows it ({@code order-announce-mine}). Each player's own filter applies on top.
     */
    static boolean announced(long minTotal, long escrow, boolean ownerAllows) {
        return ownerAllows && minTotal > 0 && escrow >= minTotal;
    }

    /** Forgets announcement cooldowns that ran out (bounded memory). */
    void sweep() {
        long cutoff = System.currentTimeMillis() - this.settings.get().announceCooldown().toMillis();
        this.lastAnnounced.values().removeIf(time -> time < cutoff);
    }
}
