package net.siftvanilla.siftcore.feature.shards;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.economy.Deliveries;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Moves items bought in the shard shop from the claim box into the buyer's inventory. A purchase puts all its items
 * into the claim box inside the transaction that takes the shards, so a crash at any moment loses neither the shards
 * nor the items. This class then claims as many of those stacks as fit (marked claimed in storage first) and hands
 * them over on the buyer's thread. Anything that can't be handed over (the buyer left, the inventory filled up
 * meanwhile, the server is stopping) goes back into the claim box, never onto the ground.
 */
final class ShardHandouts {

    /** The claim box source of shard shop items. */
    static final String SOURCE = "shard_shop";

    /** How a hand-over ended. Runs on the buyer's thread. */
    @FunctionalInterface
    interface Done {
        /**
         * @param given items now in the inventory
         * @param left  items still waiting in the claim box
         */
        void accept(long given, long left);
    }

    private record Handover(UUID owner, List<ItemStack> items, String ref) {
    }

    private final Services services;
    private final Logger logger;
    private final Map<Long, Handover> handovers = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();
    private final AtomicInteger waiting = new AtomicInteger();

    ShardHandouts(Services services) {
        this.services = services;
        this.logger = services.plugin().getLogger();
    }

    private Deliveries deliveries() {
        return this.services.deliveries();
    }

    /** The items of one purchase still waiting in the buyer's claim box. */
    List<Deliveries.Delivery> waiting(UUID owner, String ref) {
        List<Deliveries.Delivery> matching = new ArrayList<>();
        for (Deliveries.Delivery delivery : deliveries().of(owner)) {
            if (SOURCE.equals(delivery.source()) && ref.equals(delivery.ref())) {
                matching.add(delivery);
            }
        }
        return matching;
    }

    private static long count(List<Deliveries.Delivery> deliveries) {
        long total = 0;
        for (Deliveries.Delivery delivery : deliveries) {
            total += delivery.item().getAmount();
        }
        return total;
    }

    /**
     * Claims the stacks of a purchase that fit into the buyer's inventory and hands them over. Call on the buyer's
     * thread; {@code done} runs on it too (not at all when the buyer left before).
     */
    void claim(Player player, String ref, Done done) {
        UUID owner = player.getUniqueId();
        List<Deliveries.Delivery> matching = waiting(owner, ref);
        if (matching.isEmpty()) {
            done.accept(0, 0);
            return;
        }
        ItemStack unit = matching.getFirst().item();
        int[] amounts = new int[matching.size()];
        for (int i = 0; i < amounts.length; i++) {
            amounts[i] = matching.get(i).item().getAmount();
        }
        boolean[] chosen = ShardMath.pick(amounts, capacity(player, unit));
        List<Long> ids = new ArrayList<>();
        long claiming = 0;
        for (int i = 0; i < chosen.length; i++) {
            if (chosen[i]) {
                ids.add(matching.get(i).id());
                claiming += amounts[i];
            }
        }
        long total = count(matching);
        if (ids.isEmpty()) {
            done.accept(0, total);
            return;
        }
        long stays = total - claiming;
        this.waiting.incrementAndGet();
        try {
            deliveries().claim(owner, ids, owner.toString(),
                items -> {
                    try {
                        hand(player, items, ref, (given, back) -> done.accept(given, stays + back));
                    } finally {
                        this.waiting.decrementAndGet();
                    }
                },
                () -> {
                    this.waiting.decrementAndGet();
                    this.services.scheduler().entity(player, () -> done.accept(0, count(waiting(owner, ref))), null);
                });
        } catch (RuntimeException e) {
            this.waiting.decrementAndGet();
            this.logger.log(Level.SEVERE, "Claiming shard shop items for " + player.getName() + " failed; they stay in the claim box", e);
            done.accept(0, total);
        }
    }

    /**
     * Hands claimed items to the buyer on their thread. Until that runs the hand-over is tracked, so a server stop can
     * still put the items back into the claim box. {@code done} receives the items given and the items put back.
     */
    private void hand(Player player, List<ItemStack> items, String ref, Done done) {
        long token = this.tokens.incrementAndGet();
        UUID owner = player.getUniqueId();
        this.handovers.put(token, new Handover(owner, List.copyOf(items), ref));
        Runnable retired = () -> {
            Handover handover = this.handovers.remove(token);
            if (handover != null) {
                store(owner, handover.items(), ref);
            }
        };
        Task task;
        try {
            task = this.services.scheduler().entity(player, () -> {
                Handover handover = this.handovers.remove(token);
                if (handover == null) {
                    return;
                }
                if (!player.isOnline()) {
                    store(owner, handover.items(), ref);
                    return;
                }
                long given = 0;
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack item : handover.items()) {
                    given += item.getAmount();
                    left.addAll(player.getInventory().addItem(item.clone()).values());
                }
                long back = 0;
                for (ItemStack item : left) {
                    back += item.getAmount();
                }
                if (!left.isEmpty()) {
                    store(owner, left, ref);
                }
                this.services.saveAfterTrade(player);
                done.accept(given - back, back);
            }, retired);
        } catch (RuntimeException e) {
            // The scheduler refuses new work while the plugin stops: the items go back into the claim box.
            task = Task.NONE;
        }
        if (task == Task.NONE) {
            retired.run();
        }
    }

    /** Puts items back into the claim box; if even that fails, logs everything needed to restore them by hand. */
    void store(UUID owner, List<ItemStack> items, String ref) {
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            TransactionResult result;
            try {
                result = deliveries().give(owner, SOURCE, ref, item, "system");
            } catch (RuntimeException e) {
                lost(owner, item, ref, e);
                continue;
            }
            if (!result.success()) {
                lost(owner, item, ref, new IllegalStateException(result.status() + " " + result.reason()));
                continue;
            }
            result.committed().whenComplete((ignored, error) -> {
                if (error != null) {
                    lost(owner, item, ref, error);
                }
            });
        }
    }

    private void lost(UUID owner, ItemStack item, String ref, Throwable error) {
        String data;
        try {
            data = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (RuntimeException e) {
            data = item.toString();
        }
        this.logger.log(Level.SEVERE, "Could not return " + item.getAmount() + " x " + item.getType().getKey()
            + " bought in the shard shop to the claim box of " + owner + " (" + ref + "). Restore it by hand; item data (base64): "
            + data, error);
    }

    /** How many more of {@code unit} fit into the main inventory (not armor or the off hand). Player's thread. */
    static long capacity(Player player, ItemStack unit) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int empty = 0;
        int[] partial = new int[contents.length];
        int partials = 0;
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) {
                empty++;
            } else if (stack.isSimilar(unit)) {
                partial[partials++] = stack.getAmount();
            }
        }
        return ShardMath.capacity(Arrays.copyOf(partial, partials), empty, Math.max(1, unit.getMaxStackSize()));
    }

    /** Items claimed for buyers that have not reached them yet. */
    int pending() {
        return this.handovers.size();
    }

    /** Waits until no claim is waiting for storage any more, at most {@code timeout}. */
    boolean awaitIdle(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (this.waiting.get() > 0) {
            if (System.nanoTime() - end >= 0) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return this.waiting.get() <= 0;
            }
        }
        return true;
    }

    /** Puts every hand-over that has not run yet back into the claim box. Called on shutdown, before storage closes. */
    void drain() {
        for (Long token : List.copyOf(this.handovers.keySet())) {
            Handover handover = this.handovers.remove(token);
            if (handover != null) {
                store(handover.owner(), handover.items(), handover.ref());
            }
        }
    }
}
