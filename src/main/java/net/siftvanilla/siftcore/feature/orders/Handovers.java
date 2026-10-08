package net.siftvanilla.siftcore.feature.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.inventory.ItemStack;

/**
 * Items that wait for storage before they may move:
 * <ul>
 *   <li>{@link Kind#COLLECT}: items the owner collected; they are handed over on the owner's thread once the collect
 *       is committed;</li>
 *   <li>{@link Kind#RETURN}: items taken from a seller for a delivery; they go back only if storing the delivery
 *       fails.</li>
 * </ul>
 * Whoever {@linkplain #take takes} an entry handles it, exactly once: the normal path on the player's thread, the
 * player leaving, or the shutdown (when the schedulers no longer run).
 */
final class Handovers {

    enum Kind {
        COLLECT,
        RETURN
    }

    /**
     * @param key       the order key of the items
     * @param amount    how many items
     * @param items     for {@link Kind#RETURN}: the exact stacks taken from the seller
     * @param committed the transaction's commit, to know at shutdown whether it is settled
     */
    record Pending(long token, Kind kind, UUID player, long orderId, String key, int amount, List<ItemStack> items,
                   CompletableFuture<Void> committed) {
        Pending {
            items = items.stream().map(ItemStack::clone).toList();
        }

        @Override
        public List<ItemStack> items() {
            return this.items.stream().map(ItemStack::clone).toList();
        }
    }

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();

    /** Registers collected items waiting for the commit; returns the token. */
    long collect(UUID owner, long orderId, String key, int amount, CompletableFuture<Void> committed) {
        long token = this.tokens.incrementAndGet();
        this.pending.put(token, new Pending(token, Kind.COLLECT, owner, orderId, key, amount, List.of(), committed));
        return token;
    }

    /** Registers delivered items to give back if the delivery can't be stored; returns the token. */
    long returnIfFailed(UUID seller, long orderId, String key, List<ItemStack> items, CompletableFuture<Void> committed) {
        long token = this.tokens.incrementAndGet();
        int amount = OrderItem.count(items);
        this.pending.put(token, new Pending(token, Kind.RETURN, seller, orderId, key, amount, items, committed));
        return token;
    }

    /** Removes and returns an entry; null when someone else already handled it. */
    Pending take(long token) {
        return this.pending.remove(token);
    }

    /** Removes and returns every entry (shutdown). */
    List<Pending> takeAll() {
        List<Pending> all = new ArrayList<>();
        for (Long token : List.copyOf(this.pending.keySet())) {
            Pending entry = this.pending.remove(token);
            if (entry != null) {
                all.add(entry);
            }
        }
        return all;
    }

    /** True while items of this order wait for a handover (the order must stay in memory until then). */
    boolean holdsOrder(long orderId) {
        for (Pending entry : this.pending.values()) {
            if (entry.orderId() == orderId) {
                return true;
            }
        }
        return false;
    }
}
