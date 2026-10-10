package net.siftvanilla.siftcore.feature.spawners;

import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.entity.Player;

/** Opens spawner storage menus. */
final class StorageMenus {

    private final MenuContext ctx;
    private final SpawnerService service;
    private final WorthLookup worth;

    StorageMenus(MenuContext ctx, SpawnerService service, WorthLookup worth) {
        this.ctx = ctx;
        this.service = service;
        this.worth = worth;
    }

    /** Opens the storage of a spawner; {@code back} (may be null) is what the back button does. */
    void open(Player player, ManagedSpawner spawner, Runnable back) {
        new StorageMenu(this.ctx, player, this.service, this.worth, spawner, back).open();
    }
}
