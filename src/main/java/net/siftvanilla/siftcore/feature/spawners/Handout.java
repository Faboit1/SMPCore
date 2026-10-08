package net.siftvanilla.siftcore.feature.spawners;

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
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Hands items to players: into the inventory first, whatever doesn't fit into the claim box, never onto the ground.
 * Inventory methods must run on the player's thread.
 */
final class Handout {

    static final String SOURCE = "spawner";

    private final Deliveries deliveries;
    private final Logger logger;

    Handout(Deliveries deliveries, Logger logger) {
        this.deliveries = deliveries;
        this.logger = logger;
    }

    /** {@code amount} copies of {@code unit} as stacks no larger than its max stack size. */
    static List<ItemStack> stacks(ItemStack unit, long amount) {
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

    /** How many of {@code unit} fit into the player's main inventory (storage slots, not armor or off hand). */
    static long room(Player player, ItemStack unit) {
        PlayerInventory inventory = player.getInventory();
        int max = Math.max(1, unit.getMaxStackSize());
        long room = 0;
        ItemStack[] contents = inventory.getStorageContents();
        for (ItemStack slot : contents) {
            if (slot == null || slot.isEmpty()) {
                room += max;
            } else if (slot.isSimilar(unit)) {
                room += Math.max(0, max - slot.getAmount());
            }
        }
        return room;
    }

    /** Puts items into the inventory; what doesn't fit goes to the claim box. Returns how many went to the claim box. */
    long give(Player player, List<ItemStack> items, String ref) {
        List<ItemStack> parts = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                parts.addAll(stacks(item, item.getAmount()));
            }
        }
        if (parts.isEmpty()) {
            return 0;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(parts.toArray(new ItemStack[0]));
        return toClaimBox(player.getUniqueId(), leftovers.values(), ref);
    }

    /** Adds items to a claim box as part of a transaction (they appear when it applies, stored with it). */
    void addTo(LedgerTx.Builder tx, UUID owner, ItemStack unit, long amount, String ref) {
        for (ItemStack stack : stacks(unit, amount)) {
            this.deliveries.add(tx, owner, SOURCE, ref, stack);
        }
    }

    /** Stores items in a claim box right away; returns how many were stored. Failures are logged in full. */
    long toClaimBox(UUID owner, Collection<ItemStack> items, String ref) {
        long stored = 0;
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            for (ItemStack stack : stacks(item, item.getAmount())) {
                TransactionResult result = this.deliveries.give(owner, SOURCE, ref, stack, "system");
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
