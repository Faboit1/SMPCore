package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import java.util.function.UnaryOperator;

/**
 * Every order that is not closed, in memory. Records are immutable and replaced whole, so reads are lock-free and
 * consistent per order from any thread. Changes happen only inside economy transactions (under the economy lock),
 * through {@link #put}, {@link #update} and {@link #remove}; nothing else may call them.
 * <p>
 * Every change bumps the {@link #revision()}. The bid index (active orders per order key, best price first, then
 * oldest) is rebuilt lazily when the revision moved and published as an immutable snapshot, so sellers and previews
 * read it without locks.
 * <p>
 * Memory is bounded by the orders that are active or still have items to collect.
 */
final class OrderBook {

    /** Best price each first, then the oldest, then the lowest id. */
    static final Comparator<Order> BEST_FIRST = Comparator.comparingLong(Order::priceEach).reversed()
        .thenComparingLong(Order::created)
        .thenComparingLong(Order::id);

    /** The bid index at one revision. */
    private record Index(long revision, Map<String, List<Order>> byKey) {
    }

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> activeByOwner = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private volatile Index index = new Index(-1, Map.of());

    /** Replaces everything with orders read from storage (startup only). */
    void load(Collection<Order> loaded) {
        this.orders.clear();
        this.activeByOwner.clear();
        for (Order order : loaded) {
            put(order);
        }
    }

    /** Changes on every put, update and remove. */
    long revision() {
        return this.revision.get();
    }

    Order get(long id) {
        return this.orders.get(id);
    }

    /** Orders in memory (active, or ended with items waiting). */
    int size() {
        return this.orders.size();
    }

    /** Every order in memory (active, or ended with items waiting), in no particular order. */
    List<Order> all() {
        return new ArrayList<>(this.orders.values());
    }

    /** Active orders, in no particular order. */
    List<Order> active() {
        List<Order> list = new ArrayList<>();
        for (Order order : this.orders.values()) {
            if (order.active()) {
                list.add(order);
            }
        }
        return list;
    }

    /** One player's orders that are not closed (active, or ended with items waiting). */
    List<Order> of(UUID owner) {
        List<Order> list = new ArrayList<>();
        for (Order order : this.orders.values()) {
            if (order.owner().equals(owner) && !order.closed()) {
                list.add(order);
            }
        }
        return list;
    }

    /** How many active orders a player has. Cheap (an index lookup). */
    int activeCount(UUID owner) {
        return this.activeByOwner.getOrDefault(owner, 0);
    }

    /** Total delivered items a player has not collected yet, over all their orders. */
    long waiting(UUID owner) {
        long total = 0;
        for (Order order : this.orders.values()) {
            if (order.owner().equals(owner)) {
                total += order.waiting();
            }
        }
        return total;
    }

    /** Money a player's active orders hold. */
    long held(UUID owner) {
        long total = 0;
        for (Order order : this.orders.values()) {
            if (order.owner().equals(owner) && order.active()) {
                total = Math.addExact(total, order.escrow());
            }
        }
        return total;
    }

    /** Active orders whose time ran out at {@code now}. */
    List<Order> due(long now) {
        List<Order> list = new ArrayList<>();
        for (Order order : this.orders.values()) {
            if (order.active() && order.expiredAt(now)) {
                list.add(order);
            }
        }
        return list;
    }

    /** Money held by all orders in memory (the orders escrow account must hold exactly this). */
    long escrowTotal() {
        long total = 0;
        for (Order order : this.orders.values()) {
            total = Math.addExact(total, order.escrow());
        }
        return total;
    }

    /** Number of active orders. */
    int activeTotal() {
        int total = 0;
        for (Order order : this.orders.values()) {
            if (order.active()) {
                total++;
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ the bid index

    /**
     * Active orders for an order key, best price each first, then oldest. An immutable snapshot of the latest
     * revision; it may include orders that expired since (callers filter by time) and never includes ended orders of
     * that revision.
     */
    List<Order> bids(String key) {
        return current().byKey().getOrDefault(key, List.of());
    }

    /** Every order key that has active orders, with its bids (the same snapshot as {@link #bids}). */
    Map<String, List<Order>> bidsByKey() {
        return current().byKey();
    }

    private Index current() {
        Index snapshot = this.index;
        long now = this.revision.get();
        return snapshot.revision() == now ? snapshot : rebuild(now);
    }

    private synchronized Index rebuild(long revisionSeen) {
        Index snapshot = this.index;
        if (snapshot.revision() == revisionSeen) {
            return snapshot;
        }
        Index built = new Index(revisionSeen, build(this.orders.values()));
        this.index = built;
        return built;
    }

    /** The index of a set of orders: active ones grouped by key, best first. Pure. */
    static Map<String, List<Order>> build(Collection<Order> orders) {
        Map<String, List<Order>> grouped = new HashMap<>();
        for (Order order : orders) {
            if (order.active()) {
                grouped.computeIfAbsent(order.key(), k -> new ArrayList<>()).add(order);
            }
        }
        Map<String, List<Order>> sorted = new HashMap<>(grouped.size());
        grouped.forEach((key, list) -> {
            list.sort(BEST_FIRST);
            sorted.put(key, List.copyOf(list));
        });
        return Map.copyOf(sorted);
    }

    // ------------------------------------------------------------------ changes (under the economy lock only)

    void put(Order order) {
        Order previous = this.orders.put(order.id(), order);
        count(previous, order);
        this.revision.incrementAndGet();
    }

    /** Replaces an order with {@code change(current)}; returns {@code {before, after}}, or null when it is absent. */
    Order[] update(long id, UnaryOperator<Order> change) {
        Order before = this.orders.get(id);
        if (before == null) {
            return null;
        }
        Order after = change.apply(before);
        this.orders.put(id, after);
        count(before, after);
        this.revision.incrementAndGet();
        return new Order[] {before, after};
    }

    Order remove(long id) {
        Order removed = this.orders.remove(id);
        if (removed != null) {
            count(removed, null);
            this.revision.incrementAndGet();
        }
        return removed;
    }

    /** Removes closed orders unless {@code keep} says otherwise; returns how many were removed. */
    int pruneClosed(LongPredicate keep) {
        int removed = 0;
        for (Order order : List.copyOf(this.orders.values())) {
            if (order.closed() && !keep.test(order.id())) {
                remove(order.id());
                removed++;
            }
        }
        return removed;
    }

    private void count(Order before, Order after) {
        boolean wasActive = before != null && before.active();
        boolean isActive = after != null && after.active();
        if (wasActive == isActive && (before == null || after == null || before.owner().equals(after.owner()))) {
            return;
        }
        if (wasActive) {
            this.activeByOwner.computeIfPresent(before.owner(), (owner, count) -> count <= 1 ? null : count - 1);
        }
        if (isActive) {
            this.activeByOwner.merge(after.owner(), 1, Integer::sum);
        }
    }

    // ------------------------------------------------------------------ consistency

    /**
     * Checks the book against its own rules: the active index matches, active orders hold exactly what is still
     * wanted, ended orders hold nothing, and counts stay in range. Call under the economy lock for a stable view.
     * Returns null when everything holds, otherwise the first problem.
     */
    String verify() {
        Map<UUID, Integer> counted = new HashMap<>();
        for (Order order : this.orders.values()) {
            if (order.filled() < 0 || order.filled() > order.quantity()) {
                return "order " + order.id() + " has " + order.filled() + " of " + order.quantity() + " delivered";
            }
            if (order.collected() < 0 || order.collected() > order.filled()) {
                return "order " + order.id() + " has " + order.collected() + " collected of " + order.filled() + " delivered";
            }
            if (order.active()) {
                counted.merge(order.owner(), 1, Integer::sum);
                long expected = OrderMath.total(order.quantity() - order.filled(), order.priceEach());
                if (order.escrow() != expected) {
                    return "order " + order.id() + " holds " + order.escrow() + " but still wants " + expected;
                }
                if (order.filled() >= order.quantity()) {
                    return "order " + order.id() + " is active but complete";
                }
            } else if (order.escrow() != 0) {
                return "order " + order.id() + " ended but still holds " + order.escrow();
            }
        }
        if (!counted.equals(this.activeByOwner)) {
            return "the active-orders index is out of date";
        }
        return null;
    }

    /** Null when the published bid index equals one built from the book now. Call under the economy lock. */
    String verifyIndex() {
        Map<String, List<Order>> fresh = build(this.orders.values());
        Map<String, List<Order>> published = current().byKey();
        if (!fresh.equals(published)) {
            return "the bid index has " + published.size() + " keys but the book has " + fresh.size()
                + (fresh.keySet().equals(published.keySet()) ? " (orders differ)" : "");
        }
        return null;
    }
}
