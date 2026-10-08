package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Opens sell menus and makes sure the items in them always find their way back: to the inventory (overflow to the
 * claim box) when a menu closes, onto the ground with the rest of the death drops when the player dies, and back
 * to the player when the server stops (no close events are fired then).
 */
final class SellMenus implements Listener {

    /**
     * What the grid holds right now.
     *
     * @param sellable   items that can be sold
     * @param unsellable items that can't (they are given back on close)
     * @param total      what selling pays, with the multiplier
     * @param multiplier the viewer's multiplier
     * @param tooMuch    the total does not fit in a long
     */
    record Summary(long sellable, long unsellable, long total, double multiplier, boolean tooMuch) {
    }

    private final Services services;
    private final WorthService worth;
    private final SellService sales;
    private final ItemHandout handout;
    private final Map<UUID, SellMenu> open = new ConcurrentHashMap<>();

    SellMenus(Services services, WorthService worth, SellService sales, ItemHandout handout) {
        this.services = services;
        this.worth = worth;
        this.sales = sales;
        this.handout = handout;
    }

    /** Opens a fresh sell menu. Any menu the player had open is closed (and emptied) by the server first. */
    void open(Player player) {
        SellMenu menu = new SellMenu(this.services.menus(), player, this);
        this.open.put(player.getUniqueId(), menu);
        menu.open();
    }

    int openCount() {
        return this.open.size();
    }

    Summary summarize(Player viewer, Inventory inventory) {
        long sellable = 0;
        long unsellable = 0;
        long base = 0;
        boolean tooMuch = false;
        for (int slot = 0; slot < SellMenu.GRID; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                continue;
            }
            long unit = this.worth.unitPrice(item);
            if (unit <= 0) {
                unsellable += item.getAmount();
                continue;
            }
            sellable += item.getAmount();
            try {
                base = SaleMath.add(base, unit, item.getAmount());
            } catch (ArithmeticException e) {
                tooMuch = true;
            }
        }
        double multiplier = this.worth.multiplier(viewer);
        long total = 0;
        if (!tooMuch) {
            try {
                total = SaleMath.withMultiplier(base, multiplier);
            } catch (ArithmeticException e) {
                tooMuch = true;
            }
        }
        return new Summary(sellable, unsellable, total, multiplier, tooMuch);
    }

    ItemStack totalIcon(Summary summary) {
        Lang lang = this.services.lang();
        List<Component> lore = new ArrayList<>();
        if (summary.sellable() == 0 && summary.unsellable() == 0) {
            lore.addAll(lang.lines(SellMessages.MENU_TOTAL_EMPTY));
        } else {
            lore.addAll(lang.lines(SellMessages.MENU_TOTAL_COUNT, Arg.number("count", summary.sellable())));
            if (summary.multiplier() > 1.0 && summary.sellable() > 0) {
                lore.addAll(lang.lines(SellMessages.MENU_TOTAL_BONUS, Arg.text("multiplier", Multipliers.format(summary.multiplier()))));
            }
            if (summary.unsellable() > 0) {
                lore.addAll(lang.lines(SellMessages.MENU_TOTAL_UNSELLABLE, Arg.number("count", summary.unsellable())));
            }
        }
        lore.add(Component.empty());
        lore.addAll(lang.lines(SellMessages.MENU_TOTAL_HINT));
        return Items.icon(Material.GOLD_INGOT, lang.get(SellMessages.MENU_TOTAL, Arg.money("total", summary.total())), lore);
    }

    ItemStack sellIcon(Summary summary) {
        Lang lang = this.services.lang();
        List<Component> lore = summary.total() > 0
            ? lang.lines(SellMessages.MENU_SELL_LORE, Arg.money("total", summary.total()))
            : lang.lines(SellMessages.MENU_SELL_EMPTY);
        return Items.icon(Material.EMERALD, lang.get(SellMessages.MENU_SELL), lore);
    }

    void sell(SellMenu menu) {
        this.sales.sellMenu(menu.viewer(), menu.getInventory(), SellMenu.GRID);
    }

    /** A menu closed (on the viewer's thread): its items go back to the viewer, or drop if they just died. */
    void closed(SellMenu menu) {
        Player player = menu.viewer();
        this.open.remove(player.getUniqueId(), menu);
        List<ItemStack> items = menu.drain();
        if (items.isEmpty()) {
            return;
        }
        if (menu.dropOnClose()) {
            Location at = player.getLocation();
            for (ItemStack item : items) {
                player.getWorld().dropItemNaturally(at, item);
            }
            return;
        }
        long claimed = this.handout.give(player, items, "sell", null);
        if (claimed > 0) {
            this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
    }

    /**
     * The server closes an open menu with reason DEATH right after this event, once the inventory's own drops were
     * collected and before the inventory is cleared, so the grid must drop instead of going back to the inventory.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        SellMenu menu = this.open.get(event.getEntity().getUniqueId());
        if (menu != null && !event.getKeepInventory()) {
            menu.dropOnClose(true);
        }
    }

    /** A menu still registered at quit never received its close event: keep its items in the claim box. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        this.worth.forget(uuid);
        SellMenu menu = this.open.remove(uuid);
        if (menu != null) {
            List<ItemStack> items = menu.drain();
            if (!items.isEmpty()) {
                this.handout.toClaimBox(uuid, items, "sell", null);
            }
        }
    }

    /**
     * Gives back the items of every open menu. Called from the feature's disable (the server fires no close or quit
     * events at shutdown, and the shutdown thread may touch every player).
     */
    void returnAll() {
        for (SellMenu menu : List.copyOf(this.open.values())) {
            Player player = menu.viewer();
            this.open.remove(player.getUniqueId(), menu);
            List<ItemStack> items = menu.drain();
            if (items.isEmpty()) {
                continue;
            }
            try {
                this.handout.give(player, items, "sell", null);
            } catch (RuntimeException e) {
                // Never hand anything out twice: report what may not have arrived instead of retrying.
                this.handout.reportLost(player.getUniqueId(), items, "sell menu at shutdown", e);
            }
        }
    }
}
