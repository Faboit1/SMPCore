package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.CombatTagEvent;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.GridBackup;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.Menu;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/**
 * The rank perk commands: the ender chest, workstations that work anywhere (opened with the vanilla menus, so recipes,
 * anvil costs and repairs behave exactly like the blocks), the trash bin and the hat. Everything runs on the player's
 * thread. Screens of perks that are refused in combat are tracked so they close when their player gets into combat.
 */
final class PerkService implements Listener {

    /**
     * A perk screen that is open: either a vanilla view, or one of SiftCore's menus.
     *
     * @param perk the perk that opened it
     * @param view the vanilla view, or null for a menu
     * @param menu the menu, or null for a vanilla view
     */
    private record Open(Perk perk, InventoryView view, Menu menu) {
        boolean matches(InventoryView other) {
            if (other == null) {
                return false;
            }
            if (this.view != null) {
                return other == this.view;
            }
            return other.getTopInventory().getHolder(false) == this.menu;
        }
    }

    /** The claim box source of items the trash gives back that did not fit the inventory. */
    static final String TRASH_SOURCE = "trash";

    private final Services services;
    private final Setting<KitsSettings> settings;
    private final CombatStatus combat;
    private final WorthLookup worth;
    /** The copy of an open trash bin kept in its player's data ({@code siftcore:trash_grid}). */
    private final GridBackup trashCopy;
    private final Map<UUID, Open> open = new ConcurrentHashMap<>();

    /** @param worth the server's sell prices, for Trash protection's valuables */
    PerkService(Services services, Setting<KitsSettings> settings, CombatStatus combat, WorthLookup worth) {
        this.services = services;
        this.settings = settings;
        this.combat = combat;
        this.worth = worth;
        this.trashCopy = new GridBackup(new NamespacedKey(services.plugin(), "trash_grid"), services.plugin().getLogger());
    }

    boolean has(Player player, Perk perk) {
        return player.hasPermission(perk.node());
    }

    /** The perks the player has, in their fixed order. */
    List<Perk> available(Player player) {
        List<Perk> perks = new ArrayList<>();
        for (Perk perk : Perk.values()) {
            if (has(player, perk)) {
                perks.add(perk);
            }
        }
        return perks;
    }

    /** True (after telling the player) when the perk is refused because they are in combat. */
    private boolean refusedInCombat(Player player, Perk perk) {
        if (!this.settings.get().perks().blocked(perk) || !this.combat.tagged(player.getUniqueId())) {
            return false;
        }
        this.services.messenger().send(player, KitsMessages.PERK_IN_COMBAT,
            Arg.text("time", Durations.format(KitText.roundUp(this.combat.remaining(player.getUniqueId())))));
        return true;
    }

    /** Uses a perk. Call on the player's thread (commands and dialog buttons run there). */
    void use(Player player, Perk perk) {
        if (!has(player, perk)) {
            this.services.messenger().send(player, CoreMessages.NO_PERMISSION);
            return;
        }
        if (refusedInCombat(player, perk)) {
            return;
        }
        if (perk.opensScreen()) {
            // A dialog button that opens a screen must not have it closed again by the dialog router.
            this.services.dialogs().markShown(player);
        }
        switch (perk) {
            case EC -> track(player, perk, player.openInventory(player.getEnderChest()));
            case CRAFT -> station(player, perk, MenuType.CRAFTING);
            case ANVIL -> station(player, perk, MenuType.ANVIL);
            case STONECUTTER -> station(player, perk, MenuType.STONECUTTER);
            case GRINDSTONE -> station(player, perk, MenuType.GRINDSTONE);
            case SMITHING -> station(player, perk, MenuType.SMITHING);
            case LOOM -> station(player, perk, MenuType.LOOM);
            case CARTOGRAPHY -> station(player, perk, MenuType.CARTOGRAPHY_TABLE);
            case TRASH -> trash(player);
            case HAT -> hat(player);
        }
    }

    /**
     * Opens a workstation's vanilla menu. Without a location the menu belongs to the player's own position, so it
     * never closes for distance and whatever is left in its slots goes back to the player when it closes.
     */
    private void station(Player player, Perk perk, MenuType.Typed<?, ?> type) {
        InventoryView view = type.create(player);
        player.openInventory(view);
        track(player, perk, player.getOpenInventory() == view ? view : null);
    }

    private void track(Player player, Perk perk, InventoryView view) {
        if (view != null) {
            this.open.put(player.getUniqueId(), new Open(perk, view, null));
        }
    }

    /** Opens the bin in the player's Trash bin mode. */
    private void trash(Player player) {
        KitPlayerSettings.TrashMode mode = this.services.settings().get(player, KitPlayerSettings.TRASH_MODE);
        Component title = Component.text(this.services.lang().plain(mode == KitPlayerSettings.TrashMode.DELETE_BUTTON
            ? KitsMessages.TRASH_TITLE_BUTTON : KitsMessages.TRASH_TITLE));
        TrashMenu menu = new TrashMenu(this.services.menus(), player, title, mode, this.services.lang().get(KitsMessages.TRASH_DELETE),
            this.services.lang().lines(KitsMessages.TRASH_DELETE_LORE), this.trashCopy,
            (items, delete, dying) -> emptied(player, items, delete, dying));
        this.open.put(player.getUniqueId(), new Open(Perk.TRASH, null, menu));
        menu.open();
    }

    /**
     * What left the bin (already out of it): deleted, except what the player's Trash protection keeps, or all given
     * back. Player's thread.
     */
    private void emptied(Player player, List<ItemStack> items, boolean delete, boolean dying) {
        if (items.isEmpty()) {
            this.services.messenger().send(player, KitsMessages.TRASH_EMPTY);
            return;
        }
        List<ItemStack> deleted = new ArrayList<>();
        List<ItemStack> back = new ArrayList<>();
        if (delete) {
            KitPlayerSettings.TrashProtect protect = this.services.settings().get(player, KitPlayerSettings.TRASH_PROTECT);
            long threshold = this.settings.get().perks().protectWorth();
            for (ItemStack item : items) {
                (protects(protect, item, threshold) ? back : deleted).add(item);
            }
        } else {
            back.addAll(items);
        }
        int deletedCount = count(deleted);
        int backCount = count(back);
        if (!deleted.isEmpty()) {
            this.services.audit().record(player.getUniqueId().toString(), "perks.trash", player.getUniqueId().toString(), summary(deleted));
        }
        long claimed = back.isEmpty() ? 0 : giveBack(player, back, dying);
        if (back.isEmpty() && !dying) {
            // The bin's copy went with the deleted items: saved now, a crash can't bring them back.
            this.services.saveAfterTrade(player);
        }
        if (!delete) {
            this.services.messenger().send(player, KitsMessages.TRASH_RETURNED, Arg.text("count", Lang.number(backCount)));
        } else if (backCount > 0 && deletedCount > 0) {
            this.services.messenger().send(player, KitsMessages.TRASH_DELETED_KEPT, Arg.text("count", Lang.number(deletedCount)),
                Arg.text("kept", Lang.number(backCount)));
        } else if (backCount > 0) {
            this.services.messenger().send(player, KitsMessages.TRASH_KEPT, Arg.text("kept", Lang.number(backCount)));
        } else if (deletedCount == 1) {
            this.services.messenger().send(player, KitsMessages.TRASH_DELETED_ONE);
        } else {
            this.services.messenger().send(player, KitsMessages.TRASH_DELETED, Arg.text("count", Lang.number(deletedCount)));
        }
        if (claimed > 0) {
            this.services.messenger().send(player, KitsMessages.TRASH_CLAIM_BOX, Arg.text("count", Lang.number(claimed)));
        }
    }

    /** Whether the player's Trash protection keeps this stack (gear, or a valuable worth at least the threshold). */
    private boolean protects(KitPlayerSettings.TrashProtect protect, ItemStack item, long threshold) {
        if (protect == KitPlayerSettings.TrashProtect.OFF) {
            return false;
        }
        boolean enchanted = !item.getEnchantments().isEmpty()
            || item.getItemMeta() instanceof EnchantmentStorageMeta book && book.hasStoredEnchants();
        boolean named = item.hasItemMeta() && item.getItemMeta().hasDisplayName();
        boolean gear = KitPlayerSettings.gear(item.getType().getKey().asString(), enchanted, named);
        long worth;
        try {
            worth = protect == KitPlayerSettings.TrashProtect.VALUABLES ? this.worth.price(item) : 0;
        } catch (ArithmeticException e) {
            worth = Long.MAX_VALUE;
        }
        return KitPlayerSettings.protects(protect, gear, worth, threshold);
    }

    /**
     * Gives items from the bin back: into the inventory, the player saved, then what did not fit into the claim box
     * (so a crash never leaves an item both in the saved inventory and in the claim box). When the bin closed because
     * its player died, they drop where the player died instead, like the rest of their inventory (going into the
     * inventory now would put them into an inventory that is about to be emptied). Returns how many items went to the
     * claim box. Player's thread.
     */
    private long giveBack(Player player, List<ItemStack> items, boolean dying) {
        if (dying) {
            for (ItemStack item : items) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            return 0;
        }
        return GridBackup.handBack(items, all -> {
            List<ItemStack> left = new ArrayList<>();
            for (ItemStack item : all) {
                left.addAll(player.getInventory().addItem(item.clone()).values());
            }
            return left;
        }, () -> this.services.saveAfterTrade(player), left -> {
            long stored = 0;
            for (ItemStack item : left) {
                TransactionResult result = this.services.deliveries().give(player.getUniqueId(), TRASH_SOURCE, null, item,
                    player.getUniqueId().toString());
                if (result.success()) {
                    stored += item.getAmount();
                    result.committed().whenComplete((ignored, error) -> {
                        if (error != null) {
                            this.services.plugin().getLogger().log(Level.SEVERE, "A trash item given back to " + player.getName()
                                + " could not be stored in the claim box; restore it by hand: " + item, error);
                        }
                    });
                } else {
                    // The claim box is unavailable (economy paused): never lose the item, drop it at the player's feet.
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }
            return stored;
        });
    }

    private static int count(List<ItemStack> items) {
        int count = 0;
        for (ItemStack item : items) {
            count += item.getAmount();
        }
        return count;
    }

    private static String summary(List<ItemStack> items) {
        StringBuilder summary = new StringBuilder();
        for (ItemStack item : items) {
            if (summary.length() >= 900) {
                break;
            }
            if (!summary.isEmpty()) {
                summary.append(", ");
            }
            summary.append(item.getType().getKey().asString()).append(" x").append(item.getAmount());
        }
        return summary.toString();
    }

    /**
     * The server closes an open screen with reason DEATH right after this event, once the inventory's own drops were
     * collected and before the inventory is cleared: what the bin gives back must drop then, not go into it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Open current = this.open.get(player.getUniqueId());
        if (!event.getKeepInventory() && current != null && current.menu() instanceof TrashMenu trash
            && current.matches(player.getOpenInventory())) {
            trash.dying();
        }
    }

    /**
     * Puts one of the held item on the player's head and the old helmet in the hand (or in the inventory when the hand
     * keeps the rest of its stack). Refused for blocked items, a helmet with curse of binding, or no room.
     */
    private void hat(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        if (hand.isEmpty()) {
            this.services.messenger().send(player, KitsMessages.HAT_EMPTY);
            return;
        }
        if (this.settings.get().perks().hatBlocked().contains(hand.getType().getKey().asString())) {
            this.services.messenger().send(player, KitsMessages.HAT_BLOCKED);
            return;
        }
        ItemStack current = inventory.getHelmet();
        boolean wearing = current != null && !current.isEmpty();
        if (wearing && current.containsEnchantment(Enchantment.BINDING_CURSE) && player.getGameMode() != GameMode.CREATIVE) {
            this.services.messenger().send(player, KitsMessages.HAT_CURSED);
            return;
        }
        ItemStack hat = hand.asOne();
        if (hand.getAmount() == 1) {
            inventory.setHelmet(hat);
            inventory.setItemInMainHand(wearing ? current : null);
        } else {
            ItemStack rest = hand.asQuantity(hand.getAmount() - 1);
            if (wearing) {
                ItemStack[] storage = inventory.getStorageContents();
                storage[inventory.getHeldItemSlot()] = rest;
                if (!StackFit.fits(Arrays.asList(storage), current, KitHandouts.STACKS)) {
                    this.services.messenger().send(player, KitsMessages.HAT_NO_ROOM);
                    return;
                }
            }
            inventory.setItemInMainHand(rest);
            inventory.setHelmet(hat);
            if (wearing) {
                for (ItemStack left : inventory.addItem(current).values()) {
                    // Cannot happen after the room check; never drop or lose it if it does.
                    this.services.deliveries().give(player.getUniqueId(), "hat", null, left, player.getUniqueId().toString());
                }
            }
        }
        this.services.messenger().send(player, KitsMessages.HAT_DONE, Arg.component("item", Items.name(hat)));
    }

    /** Shows another online player's ender chest, read only. Call on the viewer's thread. */
    void peek(Player viewer, Player target) {
        if (viewer.equals(target)) {
            use(viewer, Perk.EC);
            return;
        }
        String name = target.getName();
        this.services.scheduler().supplyOnEntity(target, () -> {
            List<ItemStack> copies = new ArrayList<>();
            for (ItemStack item : target.getEnderChest().getContents()) {
                copies.add(item == null || item.isEmpty() ? null : item.clone());
            }
            return copies;
        }).whenComplete((items, error) -> {
            if (error != null) {
                this.services.messenger().send(viewer, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", name));
                return;
            }
            this.services.scheduler().entity(viewer, () -> {
                Component title = Component.text(this.services.lang().plain(KitsMessages.EC_OTHERS_TITLE, Arg.text("name", name)));
                new EnderChestPeekMenu(this.services.menus(), viewer, title, items).open();
            }, null);
            this.services.audit().record(viewer.getUniqueId().toString(), "perks.ec.others", target.getUniqueId().toString(), null);
        });
    }

    // ------------------------------------------------------------------ combat

    private boolean closesInCombat(Open open) {
        KitsSettings.Perks perks = this.settings.get().perks();
        return open != null && perks.closeOnCombat() && perks.blocked(open.perk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTag(CombatTagEvent event) {
        Player player = event.player();
        UUID uuid = player.getUniqueId();
        if (!closesInCombat(this.open.get(uuid))) {
            return;
        }
        // The tagged player may belong to another region (the attacker of a long shot): close on their thread.
        this.services.scheduler().entity(player, () -> closeIfOpen(player), null);
    }

    private void closeIfOpen(Player player) {
        Open current = this.open.get(player.getUniqueId());
        if (closesInCombat(current) && current.matches(player.getOpenInventory())) {
            player.closeInventory();
            this.services.messenger().send(player, KitsMessages.PERK_CLOSED);
        }
    }

    /** Clicks in a blocked perk's screen while in combat (a staff tag fires no event) close it instead. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && refuseInCombat(player, event.getView())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && refuseInCombat(player, event.getView())) {
            event.setCancelled(true);
        }
    }

    private boolean refuseInCombat(Player player, InventoryView view) {
        Open current = this.open.get(player.getUniqueId());
        if (!closesInCombat(current) || !current.matches(view) || !this.combat.tagged(player.getUniqueId())) {
            return false;
        }
        this.services.scheduler().entity(player, () -> closeIfOpen(player), null);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Open current = this.open.get(uuid);
        if (current != null && current.matches(event.getView())) {
            this.open.remove(uuid, current);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.open.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Gives back what a trash bin held when the server stopped hard (its copy in the player's data). Nothing was
     * deleted then, so everything comes back, whatever the bin's mode.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<ItemStack> items = this.trashCopy.take(player);
        if (items.isEmpty()) {
            return;
        }
        long claimed = giveBack(player, items, false);
        this.services.messenger().send(player, KitsMessages.TRASH_RESTORED);
        if (claimed > 0) {
            this.services.messenger().send(player, KitsMessages.TRASH_CLAIM_BOX, Arg.text("count", Lang.number(claimed)));
        }
    }

    /**
     * Empties every open trash bin as its close would ({@link TrashMenu#closeAtShutdown}). The server fires no close or
     * quit events at shutdown, so without this whatever sat in a bin, Delete button bins and protected items included,
     * was gone after a restart (dupe audit R12). Called first in the feature's disable, synchronously (the schedulers
     * have stopped), while storage still takes the claim box's writes.
     */
    void returnAll() {
        for (Open current : List.copyOf(this.open.values())) {
            if (current.menu() instanceof TrashMenu bin) {
                try {
                    bin.closeAtShutdown();
                } catch (RuntimeException e) {
                    this.services.plugin().getLogger().log(Level.SEVERE, "Could not empty the trash bin of "
                        + bin.viewer().getName() + " at shutdown", e);
                }
            }
        }
        this.open.clear();
    }

    /** Perk screens being tracked. */
    int tracked() {
        return this.open.size();
    }
}
