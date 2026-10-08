package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.economy.Deliveries;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Moves items between the claim box and inventories. Claiming follows the claim box's remove-before-grant rule: the
 * deliveries are marked claimed in storage first, then the items are handed over on the player's thread. Only
 * stacks that fit completely are claimed. Items that cannot be handed over (the player left, the inventory filled
 * up meanwhile, the server is stopping) go back into the claim box, never onto the ground.
 * <p>
 * Work that will hand items over once storage answers (a claim waiting for its commit, a listing that may have to
 * be returned) is counted, so shutdown can wait for those answers before it puts undelivered items back.
 */
final class ClaimBox {

    /** Source of items that were claimed but no longer fitted. */
    static final String OVERFLOW = "overflow";

    /**
     * The result of a claim.
     *
     * @param claimed stacks put into the inventory
     * @param left    stacks that stay in (or went back to) the claim box
     * @param first   the first stack handed over, for the message, or null
     * @param failed  true when the claim could not be stored (nothing was handed over)
     */
    record Outcome(int claimed, int left, ItemStack first, boolean failed) {
        static final Outcome NONE = new Outcome(0, 0, null, false);

        /** True when everything asked for ended up in the inventory (or there was nothing to claim). */
        boolean complete() {
            return !this.failed && this.left == 0;
        }
    }

    private record Handover(UUID owner, List<ItemStack> items, String source, String ref) {
    }

    private final Services services;
    private final Logger logger;
    private final Map<Long, Handover> handovers = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();
    private final InFlight waiting = new InFlight();

    ClaimBox(Services services) {
        this.services = services;
        this.logger = services.plugin().getLogger();
    }

    private Deliveries deliveries() {
        return this.services.deliveries();
    }

    /**
     * Claims the given deliveries that fit into the player's inventory. Deliveries that are no longer waiting (claimed
     * meanwhile, shown by an outdated menu) are skipped. Call on the player's thread.
     */
    CompletableFuture<Outcome> claim(Player player, List<Deliveries.Delivery> requested) {
        Set<Long> waitingIds = new HashSet<>();
        for (Deliveries.Delivery delivery : this.deliveries().of(player.getUniqueId())) {
            waitingIds.add(delivery.id());
        }
        List<Deliveries.Delivery> wanted = new ArrayList<>(requested.size());
        for (Deliveries.Delivery delivery : requested) {
            if (waitingIds.contains(delivery.id())) {
                wanted.add(delivery);
            }
        }
        if (wanted.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NONE);
        }
        List<ItemStack> stacks = new ArrayList<>(wanted.size());
        for (Deliveries.Delivery delivery : wanted) {
            stacks.add(delivery.item());
        }
        List<Integer> fitting = AuctionItems.PLANNER.fitting(AuctionItems.storage(player), stacks);
        if (fitting.isEmpty()) {
            return CompletableFuture.completedFuture(new Outcome(0, wanted.size(), null, false));
        }
        List<Long> ids = new ArrayList<>(fitting.size());
        for (int index : fitting) {
            ids.add(wanted.get(index).id());
        }
        int notTried = wanted.size() - fitting.size();
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        UUID owner = player.getUniqueId();
        begin();
        try {
            this.deliveries().claim(owner, ids, owner.toString(),
                items -> {
                    try {
                        hand(player, items, OVERFLOW, null, returned -> result.complete(new Outcome(items.size() - returned.size(),
                            notTried + returned.size(), items.isEmpty() ? null : items.getFirst(), false)));
                    } finally {
                        end();
                    }
                },
                () -> {
                    end();
                    result.complete(new Outcome(0, wanted.size(), null, true));
                });
        } catch (RuntimeException e) {
            end();
            this.logger.log(Level.SEVERE, "Claiming items for " + player.getName() + " failed", e);
            result.complete(new Outcome(0, wanted.size(), null, true));
        }
        return result;
    }

    /**
     * Claims the auction deliveries with this reference (one bought or returned listing) when all of them fit.
     * Completes with what happened; {@code claimed == 0} means they are waiting in the claim box.
     */
    CompletableFuture<Outcome> claimReference(Player player, String ref) {
        List<Deliveries.Delivery> matching = new ArrayList<>();
        for (Deliveries.Delivery delivery : this.deliveries().of(player.getUniqueId())) {
            if (AuctionEngine.SOURCE.equals(delivery.source()) && ref.equals(delivery.ref())) {
                matching.add(delivery);
            }
        }
        if (matching.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NONE);
        }
        List<ItemStack> stacks = new ArrayList<>(matching.size());
        for (Deliveries.Delivery delivery : matching) {
            stacks.add(delivery.item());
        }
        if (!AuctionItems.PLANNER.fitsAll(AuctionItems.storage(player), stacks)) {
            return CompletableFuture.completedFuture(new Outcome(0, matching.size(), null, false));
        }
        return claim(player, matching);
    }

    /**
     * Gives items to a player: into the inventory on their thread when they are online, whatever does not fit (or
     * cannot be handed over) into the claim box. Safe from any thread.
     */
    void give(UUID owner, List<ItemStack> items, String source, String ref) {
        Player player = this.services.plugin().getServer().getPlayer(owner);
        if (player == null) {
            store(owner, items, source, ref);
            return;
        }
        hand(player, items, source, ref, returned -> {
        });
    }

    /**
     * Hands items to a player on their thread. {@code done} receives what went back to the claim box. Until the
     * hand-over runs it is tracked, so a server stop can still put the items into the claim box.
     */
    private void hand(Player player, List<ItemStack> items, String source, String ref, Consumer<List<ItemStack>> done) {
        long token = this.tokens.incrementAndGet();
        UUID owner = player.getUniqueId();
        this.handovers.put(token, new Handover(owner, List.copyOf(items), source, ref));
        Runnable retired = () -> {
            Handover handover = this.handovers.remove(token);
            if (handover != null) {
                store(owner, handover.items(), handover.source(), handover.ref());
                done.accept(handover.items());
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
                    store(owner, handover.items(), handover.source(), handover.ref());
                    done.accept(handover.items());
                    return;
                }
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack item : handover.items()) {
                    left.addAll(player.getInventory().addItem(item.clone()).values());
                }
                if (!left.isEmpty()) {
                    store(owner, left, handover.source(), handover.ref());
                }
                if (this.services.core().get().savePlayerAfterTrade()) {
                    player.saveData();
                }
                done.accept(left);
            }, retired);
        } catch (RuntimeException e) {
            // The scheduler refuses new work while the plugin stops: the items go back into the claim box.
            task = Task.NONE;
        }
        if (task == Task.NONE) {
            retired.run();
        }
    }

    /** Puts items into the claim box; if even that fails, logs everything needed to restore them by hand. */
    void store(UUID owner, List<ItemStack> items, String source, String ref) {
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            TransactionResult result;
            try {
                result = this.deliveries().give(owner, source, ref, item, "system");
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
        this.logger.log(Level.SEVERE, "Could not return an item to " + owner + " (" + ref + "). Restore it by hand; item data (base64 NBT): "
            + data, error);
    }

    /** Stacks waiting in a player's claim box (from every source). */
    int count(UUID owner) {
        return this.deliveries().count(owner);
    }

    /** A player's claim box, oldest first. */
    List<Deliveries.Delivery> of(UUID owner) {
        return this.deliveries().of(owner);
    }

    /** Items handed to players that have not reached them yet. */
    int pending() {
        return this.handovers.size();
    }

    /** Marks work that will hand items over once storage answers; pair with {@link #end()}. */
    void begin() {
        this.waiting.begin();
    }

    void end() {
        this.waiting.end();
    }

    /**
     * Waits until no work is waiting for storage any more, at most {@code timeout}. Called on shutdown after the
     * database flushed, when the remaining answers are only callbacks that are about to run.
     */
    boolean awaitIdle(Duration timeout) {
        return this.waiting.awaitIdle(timeout);
    }

    /** Puts every hand-over that has not run yet into the claim box. Called on shutdown, before storage closes. */
    void drain() {
        for (Long token : List.copyOf(this.handovers.keySet())) {
            Handover handover = this.handovers.remove(token);
            if (handover != null) {
                store(handover.owner(), handover.items(), handover.source(), handover.ref());
            }
        }
    }
}
