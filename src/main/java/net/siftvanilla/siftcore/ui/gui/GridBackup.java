package net.siftvanilla.siftcore.ui.gui;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.ListPersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * A copy of the player's items that a menu grid holds (the sell grid, an order delivery grid), kept in the viewer's own
 * player data. Every player save (autosave, a trade's save, quit) writes the inventory and this copy together, so the
 * player file always holds every item exactly once: in the inventory, or in the copy. A crash while items sit in a grid
 * can then neither lose them nor hand them out twice; the copy a crash left behind is {@linkplain #take taken} and given
 * back when the player next joins.
 * <p>
 * The menu writes the copy whenever its grid changes, before anything saves the player, and clears it when the items
 * leave the grid (given back, sold, dropped at death). Right before a click takes an item out of a slot the copy is
 * written without that slot, so a save between the click and the next write can't keep the item in both places.
 * Items that leave a grid for the inventory go through {@link #handBack}, which saves the player before anything
 * reaches the claim box (stored apart from the player file). Every method runs on the player's thread.
 */
public final class GridBackup {

    private static final ListPersistentDataType<byte[], byte[]> TYPE = PersistentDataType.LIST.byteArrays();

    /** A stack as last written, so an unchanged stack is not serialized again on the next write. */
    private record Written(ItemStack item, byte[] bytes) {
    }

    private final NamespacedKey key;
    private final Logger logger;
    /** What each player's copy holds now (weak: a player object is dropped after they leave). */
    private final Map<Player, List<Written>> written = Collections.synchronizedMap(new WeakHashMap<>());

    public GridBackup(NamespacedKey key, Logger logger) {
        this.key = key;
        this.logger = logger;
    }

    /** Copies the grid slots {@code from} (inclusive) to {@code to} (exclusive), leaving out {@code skip} (-1: none). */
    public void save(Player player, Inventory grid, int from, int to, int skip) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = from; slot < to; slot++) {
            if (slot == skip) {
                continue;
            }
            ItemStack item = grid.getItem(slot);
            if (item != null && !item.isEmpty()) {
                items.add(item);
            }
        }
        save(player, items);
    }

    /**
     * Makes the copy hold exactly these items (none clears it). Stacks that did not change since the last write are not
     * serialized again, and an unchanged grid writes nothing.
     */
    public void save(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            clear(player);
            return;
        }
        List<Written> before = this.written.getOrDefault(player, List.of());
        List<Written> reusable = new ArrayList<>(before);
        List<Written> now = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            Written same = null;
            for (int i = 0; i < reusable.size(); i++) {
                if (reusable.get(i).item().equals(item)) {
                    same = reusable.remove(i);
                    break;
                }
            }
            if (same == null) {
                try {
                    same = new Written(item.clone(), item.serializeAsBytes());
                } catch (RuntimeException e) {
                    // Not in the copy: a crash before the item leaves the grid could lose it, so say what it was.
                    this.logger.log(Level.WARNING, "Could not copy " + item.getAmount() + " x " + item.getType().getKey()
                        + " in a menu of " + player.getName() + " into their player data", e);
                    continue;
                }
            }
            now.add(same);
        }
        if (sameWrites(before, now) && present(player)) {
            return;
        }
        List<byte[]> encoded = new ArrayList<>(now.size());
        for (Written entry : now) {
            encoded.add(entry.bytes());
        }
        player.getPersistentDataContainer().set(this.key, TYPE, encoded);
        this.written.put(player, List.copyOf(now));
    }

    private static boolean sameWrites(List<Written> a, List<Written> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** Clears the copy (the items left the grid). */
    public void clear(Player player) {
        this.written.remove(player);
        player.getPersistentDataContainer().remove(this.key);
    }

    /**
     * Hands items that left a grid (or its copy) back to their player in the only order that never leaves them in two
     * places a crash keeps. The caller has already removed them from the copy (cleared it, or rewritten it without
     * them). Then the items go into the inventory, the player file is saved (inventory and copy together, so it holds
     * each item once), and only after that does what did not fit go to the claim box, which is stored apart from the
     * player file. A crash before the save leaves the saved file as it was: the items are still in the saved copy and
     * the claim box got nothing. Queuing the claim box first could commit it before the save and leave the overflow in
     * both the claim box and the saved copy. Should the save throw, the overflow still goes to the claim box (it exists
     * nowhere else) before the failure is passed on.
     *
     * @param toInventory puts the items into the inventory and returns what did not fit
     * @param save        saves the player file (does nothing when the server does not save after trades)
     * @param toClaimBox  stores what did not fit in the claim box; returns what it reports (for example how many)
     * @return what {@code toClaimBox} returned, or 0 when everything fitted the inventory
     */
    public static <I> long handBack(List<I> items, Function<List<I>, List<I>> toInventory, Runnable save,
                                    ToLongFunction<List<I>> toClaimBox) {
        List<I> left = toInventory.apply(items);
        RuntimeException failed = null;
        try {
            save.run();
        } catch (RuntimeException e) {
            failed = e;
        }
        long stored = left.isEmpty() ? 0 : toClaimBox.applyAsLong(left);
        if (failed != null) {
            throw failed;
        }
        return stored;
    }

    /** Whether the player has a copy (only after a crash, or while the menu is open). */
    public boolean present(Player player) {
        return player.getPersistentDataContainer().has(this.key);
    }

    /**
     * Takes a copy left by a previous session (the server stopped hard while the menu was open): returns its items and
     * removes it. Items that can't be read are logged in full (Base64) so staff can restore them.
     */
    public List<ItemStack> take(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (!data.has(this.key)) {
            return List.of();
        }
        List<byte[]> encoded;
        try {
            encoded = data.get(this.key, TYPE);
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "The menu items kept in the player data of " + player.getName() + " (" + this.key
                + ") can't be read and were left there", e);
            return List.of();
        }
        data.remove(this.key);
        this.written.remove(player);
        if (encoded == null) {
            return List.of();
        }
        List<ItemStack> items = new ArrayList<>(encoded.size());
        for (byte[] bytes : encoded) {
            try {
                items.add(ItemStack.deserializeBytes(bytes));
            } catch (RuntimeException e) {
                this.logger.log(Level.SEVERE, "An item kept from a menu of " + player.getName() + " (" + player.getUniqueId()
                    + ") can't be read. Restore it by hand; item data (base64): " + Base64.getEncoder().encodeToString(bytes), e);
            }
        }
        return items;
    }
}
