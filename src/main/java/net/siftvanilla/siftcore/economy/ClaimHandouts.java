package net.siftvanilla.siftcore.economy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Moves items owed to a player into their inventory on their own thread, without ever losing them. Two ways in:
 * <ul>
 *   <li>{@link #claim}: the items were put into the claim box inside the transaction that owes them (a purchase, a
 *       picked-up spawner). The stacks that fit are marked claimed in storage first, then handed over; the rest stay in
 *       the claim box. If the hand-over never happens, the items are still in the claim box, which is stored.</li>
 *   <li>{@link #give}: the items already left their source in a committed transaction (taken out of a spawner's
 *       storage, given back after a failed change).</li>
 * </ul>
 * Every hand-over is tracked until it runs. Whatever can't be handed over (the player left, the scheduler refused the
 * task, the inventory filled up meanwhile, the server is stopping) goes into the claim box exactly once, never onto the
 * ground. A feature using this calls {@link #awaitIdle} and {@link #drain} from its {@code disable()}, after flushing
 * the database and before storage closes.
 */
public final class ClaimHandouts {

    /**
     * How a claim or hand-over ended, counted in items.
     *
     * @param handed items put into the inventory
     * @param left   items that stay in (or went back to) the claim box
     * @param failed true when storage refused the claim (nothing was handed over, everything is still in the claim box)
     */
    public record Outcome(int handed, int left, boolean failed) {
        public static final Outcome NONE = new Outcome(0, 0, false);
    }

    /** How the planner reads item stacks. */
    public static final SlotPlan.Stacks<ItemStack> STACKS = new SlotPlan.Stacks<>() {
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

    private record Handover(UUID owner, List<ItemStack> items, String source, String ref) {
    }

    private final Deliveries deliveries;
    private final Scheduler scheduler;
    private final Logger logger;
    private final BooleanSupplier saveAfterTrade;
    private final Handoffs<Handover> handoffs = new Handoffs<>();

    /**
     * @param saveAfterTrade whether to save the player file after items reached the inventory (core setting)
     */
    public ClaimHandouts(Deliveries deliveries, Scheduler scheduler, Logger logger, BooleanSupplier saveAfterTrade) {
        this.deliveries = deliveries;
        this.scheduler = scheduler;
        this.logger = logger;
        this.saveAfterTrade = saveAfterTrade;
    }

    /** The unclaimed deliveries of a player with this source and reference, oldest first. */
    public List<Deliveries.Delivery> waiting(UUID owner, String source, String ref) {
        List<Deliveries.Delivery> matching = new ArrayList<>();
        for (Deliveries.Delivery delivery : this.deliveries.of(owner)) {
            if (source.equals(delivery.source()) && ref.equals(delivery.ref())) {
                matching.add(delivery);
            }
        }
        return matching;
    }

    /**
     * Claims the deliveries with this source and reference into the player's inventory. The stacks that fit (each one
     * whole, planned the way the game adds items) are marked claimed in storage, then handed over on the player's
     * thread; the others stay in the claim box. Call on the player's thread. The future completes once the hand-over
     * ran (on the player's thread) or its items went back into the claim box (on any thread).
     */
    public CompletableFuture<Outcome> claim(Player player, String source, String ref) {
        UUID owner = player.getUniqueId();
        List<Deliveries.Delivery> matching = waiting(owner, source, ref);
        if (matching.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NONE);
        }
        List<ItemStack> stacks = new ArrayList<>(matching.size());
        for (Deliveries.Delivery delivery : matching) {
            stacks.add(delivery.item());
        }
        int total = count(stacks);
        List<Integer> fitting = SlotPlan.fitting(Arrays.asList(player.getInventory().getStorageContents()), stacks, STACKS);
        if (fitting.isEmpty()) {
            return CompletableFuture.completedFuture(new Outcome(0, total, false));
        }
        List<Long> ids = new ArrayList<>(fitting.size());
        for (int index : fitting) {
            ids.add(matching.get(index).id());
        }
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        this.handoffs.begin();
        try {
            this.deliveries.claim(owner, ids, owner.toString(),
                items -> {
                    try {
                        int claimed = count(items);
                        hand(player, items, source, ref, returned -> result.complete(
                            new Outcome(claimed - count(returned), total - claimed + count(returned), false)));
                    } finally {
                        this.handoffs.end();
                    }
                },
                () -> {
                    this.handoffs.end();
                    result.complete(new Outcome(0, total, true));
                });
        } catch (RuntimeException e) {
            this.handoffs.end();
            this.logger.log(Level.SEVERE, "Claiming " + source + " items (" + ref + ") for " + player.getName() + " failed; they stay in the claim box", e);
            result.complete(new Outcome(0, total, true));
        }
        return result;
    }

    /**
     * Hands items that already left their source to a player on their thread: into the inventory, whatever does not fit
     * (or can't be handed over) into the claim box. Safe from any thread. The future completes once the hand-over ran (on
     * the player's thread) or the items went into the claim box (on any thread).
     */
    public CompletableFuture<Outcome> give(Player player, List<ItemStack> items, String source, String ref) {
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        int total = count(items);
        hand(player, items, source, ref, returned -> result.complete(new Outcome(total - count(returned), count(returned), false)));
        return result;
    }

    /** Hands items over on the player's thread; {@code done} receives what went into the claim box instead. */
    private void hand(Player player, List<ItemStack> items, String source, String ref, Consumer<List<ItemStack>> done) {
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                copies.add(item.clone());
            }
        }
        UUID owner = player.getUniqueId();
        this.handoffs.hand(this.scheduler, player, new Handover(owner, List.copyOf(copies), source, ref), handover -> {
            if (!player.isOnline()) {
                store(owner, handover.items(), source, ref);
                done.accept(handover.items());
                return;
            }
            List<ItemStack> left = new ArrayList<>();
            for (ItemStack item : handover.items()) {
                for (ItemStack part : split(item)) {
                    left.addAll(player.getInventory().addItem(part).values());
                }
            }
            if (!left.isEmpty()) {
                store(owner, left, source, ref);
            }
            if (this.saveAfterTrade.getAsBoolean()) {
                player.saveData();
            }
            done.accept(left);
        }, handover -> {
            store(handover.owner(), handover.items(), handover.source(), handover.ref());
            done.accept(handover.items());
        });
    }

    /** Puts items into a player's claim box; if even that fails, logs everything needed to restore them by hand. */
    public void store(UUID owner, Collection<ItemStack> items, String source, String ref) {
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            TransactionResult result;
            try {
                result = this.deliveries.give(owner, source, ref, item, "system");
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
        this.logger.log(Level.SEVERE, "Could not put " + item.getAmount() + " x " + item.getType().getKey() + " into the claim box of "
            + owner + " (" + ref + "). Restore it by hand; item data (base64): " + data, error);
    }

    /**
     * Counts a stored transaction whose commit callback will call {@link #give}. Storage completes commits on more than
     * one thread, so a flush can return before such a callback even started; {@link #awaitIdle} waits for it, so its
     * hand-over (or the claim box write it falls back to) happens before storage closes. Call before registering the
     * callback, and pair with exactly one {@link #end()} at the end of the callback.
     */
    public void begin() {
        this.handoffs.begin();
    }

    /** Ends what {@link #begin()} counted. */
    public void end() {
        this.handoffs.end();
    }

    /** Hand-overs that have not reached their player or the claim box yet. */
    public int pending() {
        return this.handoffs.pending();
    }

    /** Waits until no claim is waiting for storage any more, at most {@code timeout}. */
    public boolean awaitIdle(Duration timeout) {
        return this.handoffs.awaitIdle(timeout);
    }

    /** Puts every hand-over that has not run yet into the claim box. Called on shutdown, before storage closes. */
    public void drain() {
        this.handoffs.drain();
    }

    /**
     * The usual shutdown sequence of a feature that hands items over after commits: let every queued transaction
     * commit and every claim register its hand-over (players can no longer receive items, the region threads have
     * stopped), put everything undelivered into the claim box, and store that, all before storage closes.
     *
     * @param flush flushes the database synchronously
     */
    public void shutdown(Runnable flush, String what) {
        try {
            flush.run();
            if (!awaitIdle(Duration.ofSeconds(10))) {
                this.logger.warning("Some " + what + " were still waiting for storage at shutdown");
            }
            drain();
            flush.run();
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Returning pending " + what + " on shutdown failed", e);
        }
    }

    private static List<ItemStack> split(ItemStack item) {
        int max = Math.max(1, item.getMaxStackSize());
        if (item.getAmount() <= max) {
            return List.of(item);
        }
        List<ItemStack> parts = new ArrayList<>();
        int left = item.getAmount();
        while (left > 0) {
            int part = Math.min(max, left);
            parts.add(item.asQuantity(part));
            left -= part;
        }
        return parts;
    }

    private static int count(List<ItemStack> items) {
        long total = 0;
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                total += item.getAmount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
