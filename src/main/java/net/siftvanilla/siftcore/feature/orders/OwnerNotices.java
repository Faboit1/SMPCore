package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
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
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Tells owners what happened to their orders: right away when they are online (respecting their order messages
 * setting for deliveries), otherwise as a notice row (V021) that is summed up when they next join. Refunds and staff
 * cancellations always reach the owner. Also announces big new orders to players who want to hear about them.
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
    private final Toggle notifications;
    private final Toggle announcements;
    private final VanishStatus vanish;
    private final IgnoreLookup ignores;
    private final Logger logger;
    private final Map<UUID, Long> lastAnnounced = new ConcurrentHashMap<>();

    OwnerNotices(Services services, OrderStore store, OrderItems items, Supplier<OrdersSettings> settings, Toggle notifications,
                 Toggle announcements, VanishStatus vanish, IgnoreLookup ignores) {
        this.services = services;
        this.store = store;
        this.items = items;
        this.settings = settings;
        this.notifications = notifications;
        this.announcements = announcements;
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

    private boolean wantsMessages(UUID owner) {
        return this.services.settings().enabled(owner, this.notifications);
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
     * Items arrived for an order (after the delivery was stored). {@code complete} when it was the last of them.
     * {@code sold} when they came from a sale rather than a delivery.
     */
    void delivered(Order order, String sellerName, int units, long paid, boolean complete, boolean sold) {
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null) {
            store(order.owner(), order.id(), NoticeSummary.DELIVERED, units, paid, sellerName);
            if (complete) {
                store(order.owner(), order.id(), NoticeSummary.COMPLETE, order.quantity(), 0, null);
            }
            return;
        }
        if (!wantsMessages(order.owner())) {
            return;
        }
        if (complete) {
            this.services.messenger().send(owner, OrdersMessages.NOTIFY_COMPLETE, Arg.number("quantity", order.quantity()), item(order.key()));
        } else {
            this.services.messenger().send(owner, sold ? OrdersMessages.NOTIFY_SOLD : OrdersMessages.NOTIFY_DELIVERED,
                Arg.text("name", sellerName), Arg.number("amount", units), item(order.key()));
        }
    }

    /** One message per owner for a sale that filled several of their orders (sell routing). */
    void soldMany(UUID ownerId, String sellerName, int units) {
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && wantsMessages(ownerId)) {
            this.services.messenger().send(owner, OrdersMessages.NOTIFY_SOLD_MANY, Arg.text("name", sellerName), Arg.number("amount", units));
        }
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

    /** The order ends soon (once per order); the message opens the order. */
    void ending(Order order) {
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null) {
            store(order.owner(), order.id(), NoticeSummary.ENDING, order.quantity(), 0, null);
            return;
        }
        if (!wantsMessages(order.owner())) {
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
        NoticeSummary summary = NoticeSummary.of(rows, wantsMessages(player.getUniqueId()), MAX_DETAILS);
        boolean remind = waiting > 0 && this.settings.get().joinReminder();
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
     * online player who wants announcements and does not ignore the owner. Vanished owners are never announced.
     */
    void announce(Order order, String ownerName) {
        OrdersSettings s = this.settings.get();
        if (s.announceMinTotal() <= 0 || order.escrow() < s.announceMinTotal() || this.vanish.vanished(order.owner())) {
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
            if (!this.services.settings().enabled(id, this.announcements) || this.ignores.ignores(id, order.owner())) {
                continue;
            }
            player.sendMessage(text);
        }
    }

    /** Forgets announcement cooldowns that ran out (bounded memory). */
    void sweep() {
        long cutoff = System.currentTimeMillis() - this.settings.get().announceCooldown().toMillis();
        this.lastAnnounced.values().removeIf(time -> time < cutoff);
    }
}
