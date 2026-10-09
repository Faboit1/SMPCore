package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.economy.Deliveries;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Hands items to a player: into their inventory first, whatever does not fit into their claim box, never onto the
 * ground. Inventory methods must run on the player's thread.
 */
public final class ItemHandout {

    private final Deliveries deliveries;
    private final Logger logger;

    public ItemHandout(Deliveries deliveries, Logger logger) {
        this.deliveries = deliveries;
        this.logger = logger;
    }

    /** {@code amount} copies of {@code unit} as stacks no larger than the item's max stack size. */
    public static List<ItemStack> stacks(ItemStack unit, long amount) {
        int max = Math.max(1, unit.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        long left = amount;
        while (left > 0) {
            int part = (int) Math.min(max, left);
            stacks.add(unit.asQuantity(part));
            left -= part;
        }
        return stacks;
    }

    /**
     * Puts items into the player's inventory; what does not fit goes to the claim box. Returns how many items went
     * to the claim box.
     */
    public long give(Player player, List<ItemStack> items, String source, String ref) {
        return toClaimBox(player.getUniqueId(), toInventory(player, items), source, ref);
    }

    /** Puts items into the player's inventory and returns what did not fit (nothing goes to the claim box yet). */
    public List<ItemStack> toInventory(Player player, List<ItemStack> items) {
        List<ItemStack> parts = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                parts.addAll(stacks(item, item.getAmount()));
            }
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(parts.toArray(new ItemStack[0]));
        return new ArrayList<>(leftovers.values());
    }

    /**
     * Stores items in a player's claim box. If storage refuses them, they are logged in full (with their data in
     * Base64) so staff can restore them. Returns how many items were stored.
     */
    public long toClaimBox(UUID owner, Collection<ItemStack> items, String source, String ref) {
        long stored = 0;
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            for (ItemStack stack : stacks(item, item.getAmount())) {
                TransactionResult result = this.deliveries.give(owner, source, ref, stack, "system");
                if (!result.success()) {
                    lost(owner, stack, result.status().name(), null);
                    continue;
                }
                stored += stack.getAmount();
                result.committed().whenComplete((ignored, error) -> {
                    if (error != null) {
                        lost(owner, stack, "storage failed", error);
                    }
                });
            }
        }
        return stored;
    }

    /** Logs items that could not be delivered, in full, so staff can restore them. */
    public void reportLost(UUID owner, Collection<ItemStack> items, String reason, Throwable error) {
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                lost(owner, item, reason, error);
            }
        }
    }

    private void lost(UUID owner, ItemStack stack, String reason, Throwable error) {
        String data;
        try {
            data = Base64.getEncoder().encodeToString(stack.serializeAsBytes());
        } catch (RuntimeException e) {
            data = "unserializable";
        }
        this.logger.log(Level.SEVERE, "Could not put " + stack.getAmount() + " x " + stack.getType().getKey()
            + " into the inventory or claim box of " + owner + " (" + reason + "). Item data (Base64): " + data, error);
    }
}
