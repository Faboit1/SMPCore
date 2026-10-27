package net.siftvanilla.siftcore.feature.crates;

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
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.economy.Deliveries;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Moves won items from the claim box into the winner's inventory. A crate's items are put into the claim box inside
 * the opening's transaction, so they are stored together with the spent key; this class then claims them (marked
 * claimed in storage first) and hands them over on the player's thread, but only when they all fit. Anything that
 * can't be handed over (the player left, the inventory filled up meanwhile, the server is stopping) goes back into
 * the claim box, never onto the ground.
 */
final class Handouts {

    /** The claim box source of crate rewards. */
    static final String SOURCE = "crate";

    static final InventoryFit.Stacks<ItemStack> STACKS = new InventoryFit.Stacks<>() {
        @Override
        public boolean empty(ItemStack stack) {
            return stack == null || stack.isEmpty();
        }

        @Override
        public boolean similar(ItemStack a, ItemStack b) {
            return a.isSimilar(b);
        }

        @Override
        public int amount(ItemStack stack) {
            return stack.getAmount();
        }

        @Override
        public int maxStack(ItemStack stack) {
            return stack.getMaxStackSize();
        }
    };

    private record Handover(UUID owner, List<ItemStack> items, String ref) {
    }

    private final Services services;
    private final Logger logger;
    private final Map<Long, Handover> handovers = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();
    private final AtomicInteger waiting = new AtomicInteger();

    Handouts(Services services) {
        this.services = services;
        this.logger = services.plugin().getLogger();
    }

    private Deliveries deliveries() {
        return this.services.deliveries();
    }

    /** The crate deliveries of one opening still waiting in the player's claim box. */
    List<Deliveries.Delivery> waiting(UUID owner, String ref) {
        List<Deliveries.Delivery> matching = new ArrayList<>();
        for (Deliveries.Delivery delivery : deliveries().of(owner)) {
            if (SOURCE.equals(delivery.source()) && ref.equals(delivery.ref())) {
                matching.add(delivery);
            }
        }
        return matching;
    }

    /**
     * Claims the crate deliveries with this reference into the inventory when they all fit. Call on the player's
     * thread. {@code done} receives how many stacks stay in the claim box and runs on the player's thread (not at all
     * when the player left before).
     */
    void claim(Player player, String ref, IntConsumer done) {
        UUID owner = player.getUniqueId();
        List<Deliveries.Delivery> matching = waiting(owner, ref);
        if (matching.isEmpty()) {
            done.accept(0);
            return;
        }
        List<ItemStack> stacks = new ArrayList<>(matching.size());
        List<Long> ids = new ArrayList<>(matching.size());
        for (Deliveries.Delivery delivery : matching) {
            stacks.add(delivery.item());
            ids.add(delivery.id());
        }
        if (!InventoryFit.fitsAll(Arrays.asList(player.getInventory().getStorageContents()), stacks, STACKS)) {
            done.accept(matching.size());
            return;
        }
        this.waiting.incrementAndGet();
        try {
            deliveries().claim(owner, ids, owner.toString(),
                items -> {
                    try {
                        hand(player, items, ref, done);
                    } finally {
                        this.waiting.decrementAndGet();
                    }
                },
                () -> {
                    this.waiting.decrementAndGet();
                    this.services.scheduler().entity(player, () -> done.accept(waiting(owner, ref).size()), null);
                });
        } catch (RuntimeException e) {
            this.waiting.decrementAndGet();
            this.logger.log(Level.SEVERE, "Claiming crate items for " + player.getName() + " failed", e);
            done.accept(matching.size());
        }
    }

    /**
     * Hands claimed items to the player on their thread. Until that runs the hand-over is tracked, so a server stop can
     * still put the items back into the claim box.
     */
    private void hand(Player player, List<ItemStack> items, String ref, IntConsumer done) {
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
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack item : handover.items()) {
                    left.addAll(player.getInventory().addItem(item.clone()).values());
                }
                if (!left.isEmpty()) {
                    store(owner, left, ref);
                }
                this.services.saveAfterTrade(player);
                done.accept(left.size());
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
        this.logger.log(Level.SEVERE, "Could not return a crate reward to " + owner + " (" + ref
            + "). Restore it by hand; item data (base64 NBT): " + data, error);
    }

    /** Items claimed for players that have not reached them yet. */
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
