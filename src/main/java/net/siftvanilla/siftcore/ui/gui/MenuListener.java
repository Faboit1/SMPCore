package net.siftvanilla.siftcore.ui.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Routes inventory events to {@link Menu}s and throttles click spam: clicks closer together than the configured
 * interval are cancelled before they reach any handler.
 */
public final class MenuListener implements Listener {

    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private volatile long minIntervalMillis;

    public MenuListener(long minIntervalMillis) {
        this.minIntervalMillis = minIntervalMillis;
    }

    public void minInterval(long millis) {
        this.minIntervalMillis = millis;
    }

    private static Menu menu(InventoryHolder holder) {
        return holder instanceof Menu menu ? menu : null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Menu menu = menu(event.getView().getTopInventory().getHolder(false));
        if (menu == null) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || !player.equals(menu.viewer())) {
            event.setCancelled(true);
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = this.lastClick.put(player.getUniqueId(), now);
        boolean topClick = event.getClickedInventory() == menu.getInventory();
        if (topClick && previous != null && now - previous < this.minIntervalMillis) {
            event.setCancelled(true);
            return;
        }
        menu.handleClick(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        Menu menu = menu(event.getView().getTopInventory().getHolder(false));
        if (menu != null) {
            menu.handleDrag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menu(event.getView().getTopInventory().getHolder(false));
        if (menu != null && event.getPlayer().equals(menu.viewer())) {
            menu.handleClose();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.lastClick.remove(event.getPlayer().getUniqueId());
    }
}
