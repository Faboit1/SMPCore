package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Buy orders as a market sales can be routed into ({@link OrderMarket}). Bids come from the book's bid index (an
 * immutable snapshot per revision); a sale's order part is added to the seller's own transaction with
 * {@link OrderEngine#addFill}, the same check, apply and write path the delivery menu uses, so a routed sale is as safe
 * as a delivery and commits or fails together with the server part.
 */
final class OrdersMarket implements OrderMarket {

    private final OrderService service;
    private final OrderDialogs dialogs;

    OrdersMarket(OrderService service, OrderDialogs dialogs) {
        this.service = service;
        this.dialogs = dialogs;
    }

    private OrderBook book() {
        return this.service.book();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public long revision() {
        return book().revision();
    }

    @Override
    public int taxBasisPoints() {
        return this.service.settings().taxBasisPoints();
    }

    @Override
    public String key(ItemStack sample) {
        return this.service.items().key(sample);
    }

    @Override
    public List<Bid> bids(UUID seller, String key) {
        if (key == null) {
            return List.of();
        }
        boolean guard = this.service.settings().refuseSameIp();
        return bids(book().bids(key), seller, this.service.engine().now(),
            order -> (guard && this.service.services().directory().sameIp(order.owner(), seller)) || this.service.items().of(order) == null);
    }

    /**
     * The bids of a key's index entry (best first) that {@code seller} may fill at {@code now}: never their own,
     * nothing expired or complete, and nothing {@code skip} refuses. Pure.
     */
    static List<Bid> bids(List<Order> best, UUID seller, long now, Predicate<Order> skip) {
        List<Bid> bids = new ArrayList<>();
        for (Order order : best) {
            if (!order.active() || order.owner().equals(seller) || order.expiredAt(now) || order.remaining() <= 0 || skip.test(order)) {
                continue;
            }
            bids.add(new Bid(order.id(), order.owner(), order.priceEach(), order.remaining(), order.created()));
        }
        return List.copyOf(bids);
    }

    @Override
    public Problem usable(Player seller) {
        if (!seller.hasPermission(OrderService.PERMISSION_USE)) {
            return new Problem(CoreMessages.NO_PERMISSION, List.of());
        }
        OrderService.Problem blocked = this.service.blocked(seller);
        return blocked == null ? null : new Problem(blocked.key(), List.of(blocked.args()));
    }

    @Override
    public List<Take> approve(Player seller, List<Take> takes) {
        List<Take> accepted = new ArrayList<>(takes.size());
        for (Take take : takes) {
            Order order = book().get(take.orderId());
            OrderItem item = order == null ? null : this.service.items().of(order);
            if (order == null || item == null || !order.key().equals(take.key())) {
                continue;
            }
            if (take.units() <= order.remaining() && this.service.approved(order, seller.getUniqueId(), item, (int) take.units(),
                FillSource.SELL)) {
                accepted.add(take);
            }
        }
        return List.copyOf(accepted);
    }

    @Override
    public void contribute(LedgerTx.Builder tx, UUID seller, List<Take> takes) {
        contribute(this.service.engine(), tx, seller, takes, taxBasisPoints());
    }

    /**
     * Adds one fill per order (takes for the same order merged) with the sell source. Never throws for a take the
     * caller built from bids: takes that can't be filled as given (one order at two prices, more units than any order
     * holds) add a check that refuses the whole transaction instead, since the caller already took the items.
     */
    static void contribute(OrderEngine engine, LedgerTx.Builder tx, UUID seller, List<Take> takes, int taxBasisPoints) {
        List<Take> merged;
        try {
            merged = merged(takes);
        } catch (IllegalArgumentException | ArithmeticException e) {
            tx.check(() -> Refusal.PRICE_CHANGED.reason());
            return;
        }
        for (Take take : merged) {
            if (take.units() > Integer.MAX_VALUE || OrderMath.total(take.units(), take.priceEach()) <= 0) {
                tx.check(() -> Refusal.NOT_ENOUGH_LEFT.reason());
                continue;
            }
            engine.addFill(tx, seller, take.orderId(), (int) take.units(), take.priceEach(), taxBasisPoints, FillSource.SELL);
        }
    }

    /**
     * One take per order: checks see the book as it was before every apply of the transaction, so the same order twice
     * could be promised more than it wants. Takes for the same order at the same price are summed; at different prices
     * the caller is broken and the sale is refused.
     */
    static List<Take> merged(List<Take> takes) {
        Map<Long, Take> byOrder = new LinkedHashMap<>();
        for (Take take : takes) {
            Take previous = byOrder.get(take.orderId());
            if (previous == null) {
                byOrder.put(take.orderId(), take);
                continue;
            }
            if (previous.priceEach() != take.priceEach() || !Objects.equals(previous.key(), take.key())) {
                throw new IllegalArgumentException("Order " + take.orderId() + " appears twice at different terms");
            }
            byOrder.put(take.orderId(), new Take(take.orderId(), take.key(), Math.addExact(previous.units(), take.units()), take.priceEach()));
        }
        return List.copyOf(byOrder.values());
    }

    @Override
    public void committed(Player seller, List<Take> takes) {
        List<Take> merged;
        try {
            merged = merged(takes);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return;
        }
        Map<UUID, List<Take>> byOwner = new LinkedHashMap<>();
        Map<Long, Order> orders = new LinkedHashMap<>();
        for (Take take : merged) {
            Order order = book().get(take.orderId());
            if (order == null) {
                continue;
            }
            orders.put(take.orderId(), order);
            byOwner.computeIfAbsent(order.owner(), k -> new ArrayList<>()).add(take);
        }
        String sellerName = seller.getName();
        byOwner.forEach((owner, ownerTakes) -> {
            if (Bukkit.getPlayer(owner) == null || ownerTakes.size() == 1) {
                // Offline owners get a notice row per order (summed up when they join); one order is one line.
                for (Take take : ownerTakes) {
                    Order order = orders.get(take.orderId());
                    this.service.notices().delivered(order, sellerName, (int) take.units(), take.gross(),
                        order.state() == OrderState.FILLED, true);
                }
                return;
            }
            // An online owner whose several orders took part of one sale gets one line for all of them.
            int units = (int) Math.min(Integer.MAX_VALUE, ownerTakes.stream().mapToLong(Take::units).sum());
            if (ownerTakes.stream().map(Take::key).distinct().count() == 1) {
                Order first = orders.get(ownerTakes.getFirst().orderId());
                this.service.notices().delivered(first, sellerName, units, 0, false, true);
            } else {
                this.service.notices().soldMany(owner, sellerName, units);
            }
        });
        this.service.refreshAll();
    }

    @Override
    public boolean openOrderForm(Player player, String key, Runnable back) {
        return this.dialogs.formFor(player, key, back);
    }
}
