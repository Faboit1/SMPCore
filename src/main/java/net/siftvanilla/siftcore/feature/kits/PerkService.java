package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.event.CombatTagEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.Menu;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.PlayerInventory;

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

    private final Services services;
    private final Setting<KitsSettings> settings;
    private final CombatStatus combat;
    private final Map<UUID, Open> open = new ConcurrentHashMap<>();

    PerkService(Services services, Setting<KitsSettings> settings, CombatStatus combat) {
        this.services = services;
        this.settings = settings;
        this.combat = combat;
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
            Arg.time("time", KitText.roundUp(this.combat.remaining(player.getUniqueId()))));
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

    private void trash(Player player) {
        Component title = Component.text(this.services.lang().plain(KitsMessages.TRASH_TITLE));
        TrashMenu menu = new TrashMenu(this.services.menus(), player, title, deleted -> {
            int count = 0;
            StringBuilder summary = new StringBuilder();
            for (ItemStack item : deleted) {
                count += item.getAmount();
                if (summary.length() < 900) {
                    if (!summary.isEmpty()) {
                        summary.append(", ");
                    }
                    summary.append(item.getType().getKey().asString()).append(" x").append(item.getAmount());
                }
            }
            if (count == 1) {
                this.services.messenger().send(player, KitsMessages.TRASH_DELETED_ONE);
            } else {
                this.services.messenger().send(player, KitsMessages.TRASH_DELETED, Arg.number("count", count));
            }
            this.services.audit().record(player.getUniqueId().toString(), "perks.trash", player.getUniqueId().toString(), summary.toString());
        });
        this.open.put(player.getUniqueId(), new Open(Perk.TRASH, null, menu));
        menu.open();
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

    /** Perk screens being tracked. */
    int tracked() {
        return this.open.size();
    }
}
