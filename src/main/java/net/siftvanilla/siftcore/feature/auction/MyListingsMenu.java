package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The viewer's own listings, newest first. Clicking one asks to take it down; slot 50 opens the history. */
final class MyListingsMenu extends PagedMenu<Listing<ItemStack>> {

    private final AuctionService service;
    private final AuctionDialogs dialogs;

    MyListingsMenu(MenuContext ctx, Player viewer, AuctionService service, AuctionDialogs dialogs, Runnable back) {
        super(ctx, viewer, Component.text(ctx.lang().plain(AuctionMessages.MINE_TITLE)), null, null, back);
        this.service = service;
        this.dialogs = dialogs;
    }

    @Override
    protected List<Listing<ItemStack>> entries() {
        List<Listing<ItemStack>> own = new ArrayList<>(this.service.engine().book().of(this.viewer.getUniqueId()));
        own.sort(SortOrder.NEWEST.comparator());
        return own;
    }

    @Override
    protected ItemStack icon(Listing<ItemStack> listing) {
        Lang lang = this.ctx.lang();
        List<Component> lore = new ArrayList<>(lang.lines(AuctionMessages.MINE_LORE, this.service.price("price", listing.price()),
            Arg.time("time", Duration.ofMillis(listing.millisLeft(this.service.engine().now())))));
        lore.add(Component.empty());
        boolean saved = this.service.engine().book().saved(listing.id());
        lore.addAll(lang.lines(saved ? AuctionMessages.MINE_HINT : AuctionMessages.MINE_PENDING));
        return AuctionItems.display(listing.item(), lore);
    }

    @Override
    protected void clicked(Listing<ItemStack> entry, ClickContext click) {
        Player player = click.player();
        if (!this.service.usable(player)) {
            return;
        }
        Listing<ItemStack> listing = this.service.engine().book().get(entry.id());
        if (listing == null) {
            this.ctx.messenger().send(player, AuctionMessages.BUY_GONE);
            redraw();
            return;
        }
        if (!this.service.engine().book().saved(listing.id())) {
            this.ctx.messenger().send(player, AuctionMessages.BUY_PENDING);
            return;
        }
        click(Feedback.CLICK);
        this.dialogs.cancelConfirm(player, listing, this::open);
    }

    @Override
    protected void drawExtras() {
        Lang lang = this.ctx.lang();
        set(SLOT_EXTRA_1, Items.icon(Material.BOOK, lang.get(AuctionMessages.HISTORY_BUTTON), lang.lines(AuctionMessages.HISTORY_BUTTON_LORE)),
            click -> {
                click(Feedback.CLICK);
                this.dialogs.history(this.viewer, this::open);
            });
    }
}
