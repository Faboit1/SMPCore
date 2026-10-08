package net.siftvanilla.siftcore.feature.auction;

import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.entity.Player;

/** Opens the auction house menus with the viewer's remembered sort and filter. */
final class AuctionMenus {

    private final Services services;
    private final AuctionService service;
    private final AuctionDialogs dialogs;

    AuctionMenus(Services services, AuctionService service, AuctionDialogs dialogs) {
        this.services = services;
        this.service = service;
        this.dialogs = dialogs;
    }

    Services services() {
        return this.services;
    }

    /**
     * True when the player may open a menu right now. Otherwise they are told why and every screen is closed, so a
     * dialog button that led here (the main menu) does not leave the client waiting.
     */
    private boolean admit(Player player) {
        if (this.service.usable(player)) {
            return true;
        }
        player.closeInventory();
        return false;
    }

    /** Opens the auction house, optionally pre-searched. Call on the player's thread. */
    void openMain(Player player, String query) {
        if (!admit(player)) {
            return;
        }
        String sort = this.services.settings().raw(player.getUniqueId(), AuctionMenu.SORT_SETTING, this.service.settings().defaultSort().id());
        String filter = this.services.settings().raw(player.getUniqueId(), AuctionMenu.FILTER_SETTING, "all");
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof AuctionMenu open) {
            // Its choice is saved only when it closes, which happens after this menu was built.
            sort = open.sortId();
            filter = open.filterId();
        }
        AuctionMenu menu = AuctionMenu.create(this.services.menus(), player, this, this.service, this.dialogs, sort, filter, hubBack(player));
        if (query != null && !query.isBlank()) {
            menu.query(query);
        }
        menu.open();
    }

    void openMain(Player player) {
        openMain(player, "");
    }

    /** The viewer's listings; {@code back} null opens the auction house as the back target. */
    void openMine(Player player, Runnable back) {
        if (!admit(player)) {
            return;
        }
        new MyListingsMenu(this.services.menus(), player, this.service, this.dialogs, back != null ? back : () -> openMain(player)).open();
    }

    /** The claim box; {@code back} null opens the auction house as the back target. */
    void openClaims(Player player, Runnable back) {
        if (!admit(player)) {
            return;
        }
        new ClaimBoxMenu(this.services.menus(), player, this.service, back != null ? back : () -> openMain(player)).open();
    }

    /** Back from the auction house goes to the main menu when the hub is installed. */
    private Runnable hubBack(Player player) {
        HubEntry menu = this.services.hub().get("menu");
        return menu == null ? null : () -> menu.open().accept(player);
    }
}
