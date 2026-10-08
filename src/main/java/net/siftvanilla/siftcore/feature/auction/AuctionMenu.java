package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The auction house: every listing buyers can see, with sort, category filter and search, plus the viewer's
 * listings, claim box and the sell button in slots 50 to 52. Clicking a listing opens the purchase confirmation
 * (or, for one's own listing, the take-down confirmation; staff can shift right click to remove one).
 */
final class AuctionMenu extends PagedMenu<Listing<ItemStack>> {

    static final String SORT_SETTING = "auction-sort";
    static final String FILTER_SETTING = "auction-filter";

    private final AuctionMenus menus;
    private final AuctionService service;
    private final AuctionDialogs dialogs;
    private final Cycle<Comparator<Listing<ItemStack>>> sort;
    private final Cycle<Predicate<Listing<ItemStack>>> filter;
    private String savedSort;
    private String savedFilter;

    private AuctionMenu(MenuContext ctx, Player viewer, AuctionMenus menus, AuctionService service, AuctionDialogs dialogs,
                        Cycle<Comparator<Listing<ItemStack>>> sort, Cycle<Predicate<Listing<ItemStack>>> filter, Runnable back) {
        super(ctx, viewer, Component.text(ctx.lang().plain(AuctionMessages.MENU_TITLE)), sort, filter, back);
        this.menus = menus;
        this.service = service;
        this.dialogs = dialogs;
        this.sort = sort;
        this.filter = filter;
        this.savedSort = sort.selected().id();
        this.savedFilter = filter.selected().id();
    }

    static AuctionMenu create(MenuContext ctx, Player viewer, AuctionMenus menus, AuctionService service,
                              AuctionDialogs dialogs, String sortId, String filterId, Runnable back) {
        return new AuctionMenu(ctx, viewer, menus, service, dialogs, sortCycle(ctx.lang(), sortId), filterCycle(ctx.lang(), filterId), back);
    }

    private static Component label(Lang lang, MessageKey key) {
        // Plain, uncoloured: the cycle button colours the selected option white and the others gray.
        return Component.text(lang.plain(key));
    }

    static Cycle<Comparator<Listing<ItemStack>>> sortCycle(Lang lang, String initial) {
        List<Cycle.Option<Comparator<Listing<ItemStack>>>> options = new ArrayList<>();
        for (SortOrder order : SortOrder.values()) {
            MessageKey key = switch (order) {
                case NEWEST -> AuctionMessages.SORT_NEWEST;
                case ENDING_SOON -> AuctionMessages.SORT_ENDING_SOON;
                case LOWEST_PRICE -> AuctionMessages.SORT_LOWEST_PRICE;
                case HIGHEST_PRICE -> AuctionMessages.SORT_HIGHEST_PRICE;
            };
            options.add(new Cycle.Option<>(order.id(), label(lang, key), order.comparator()));
        }
        return new Cycle<>(options, initial);
    }

    static MessageKey categoryLabel(Category category) {
        return switch (category) {
            case BLOCKS -> AuctionMessages.CATEGORY_BLOCKS;
            case TOOLS -> AuctionMessages.CATEGORY_TOOLS;
            case COMBAT -> AuctionMessages.CATEGORY_COMBAT;
            case FOOD -> AuctionMessages.CATEGORY_FOOD;
            case POTIONS -> AuctionMessages.CATEGORY_POTIONS;
            case BOOKS -> AuctionMessages.CATEGORY_BOOKS;
            case SPAWNERS -> AuctionMessages.CATEGORY_SPAWNERS;
            case MISC -> AuctionMessages.CATEGORY_MISC;
        };
    }

    static Cycle<Predicate<Listing<ItemStack>>> filterCycle(Lang lang, String initial) {
        List<Cycle.Option<Predicate<Listing<ItemStack>>>> options = new ArrayList<>();
        options.add(new Cycle.Option<>("all", label(lang, AuctionMessages.FILTER_ALL), listing -> true));
        for (Category category : Category.values()) {
            options.add(new Cycle.Option<>(category.id(), label(lang, categoryLabel(category)), listing -> listing.category() == category));
        }
        return new Cycle<>(options, initial);
    }

    /** The id of the selected sort order. */
    String sortId() {
        return this.sort.selected().id();
    }

    /** The id of the selected category filter. */
    String filterId() {
        return this.filter.selected().id();
    }

    @Override
    protected List<Listing<ItemStack>> entries() {
        return this.service.engine().book().available(this.service.engine().now());
    }

    @Override
    protected ItemStack icon(Listing<ItemStack> listing) {
        Lang lang = this.ctx.lang();
        long now = this.service.engine().now();
        List<Component> lore = new ArrayList<>(lang.lines(AuctionMessages.LISTING_LORE, this.service.price("price", listing.price()),
            Arg.text("seller", this.service.name(listing.seller())), Arg.time("time", Duration.ofMillis(listing.millisLeft(now)))));
        lore.add(Component.empty());
        boolean own = listing.seller().equals(this.viewer.getUniqueId());
        lore.addAll(lang.lines(own ? AuctionMessages.LISTING_OWN : AuctionMessages.LISTING_BUY));
        if (!own && this.viewer.hasPermission(AuctionService.PERMISSION_ADMIN)) {
            lore.addAll(lang.lines(AuctionMessages.LISTING_STAFF, Arg.text("id", Long.toString(listing.id()))));
        }
        return AuctionItems.display(listing.item(), lore);
    }

    @Override
    protected String searchText(Listing<ItemStack> listing) {
        return listing.searchText();
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
        click(Feedback.CLICK);
        boolean own = listing.seller().equals(player.getUniqueId());
        if (!own && click.shift() && click.right() && player.hasPermission(AuctionService.PERMISSION_ADMIN)) {
            this.dialogs.removeConfirm(player, listing, this::open);
        } else if (own) {
            this.dialogs.cancelConfirm(player, listing, this::open);
        } else {
            this.dialogs.buyConfirm(player, listing, this::open);
        }
    }

    @Override
    protected void drawExtras() {
        Lang lang = this.ctx.lang();
        int count = this.service.engine().book().count(this.viewer.getUniqueId());
        int limit = this.service.slotLimit(this.viewer);
        List<Component> mineLore = limit == Limits.UNLIMITED
            ? lang.lines(AuctionMessages.MINE_BUTTON_LORE_UNLIMITED, Arg.number("count", count))
            : lang.lines(AuctionMessages.MINE_BUTTON_LORE, Arg.number("count", count), Arg.number("limit", limit));
        set(SLOT_EXTRA_1, Items.icon(Material.NAME_TAG, lang.get(AuctionMessages.MINE_BUTTON), mineLore), click -> {
            click(Feedback.CLICK);
            this.menus.openMine(this.viewer, this::open);
        });
        int waiting = this.service.claims().count(this.viewer.getUniqueId());
        set(SLOT_EXTRA_2, Items.icon(Material.CHEST, lang.get(AuctionMessages.CLAIMS_BUTTON),
            lang.lines(AuctionMessages.CLAIMS_BUTTON_LORE, Arg.number("count", waiting))), click -> {
                click(Feedback.CLICK);
                this.menus.openClaims(this.viewer, this::open);
            });
        set(SLOT_EXTRA_3, Items.icon(Material.EMERALD, lang.get(AuctionMessages.SELL_BUTTON), lang.lines(AuctionMessages.SELL_BUTTON_LORE)),
            click -> {
                click(Feedback.CLICK);
                this.dialogs.sellForm(this.viewer, this::open);
            });
    }

    @Override
    protected void closed() {
        String sortId = this.sort.selected().id();
        String filterId = this.filter.selected().id();
        var settings = this.menus.services().settings();
        if (!sortId.equals(this.savedSort)) {
            settings.setRaw(this.viewer.getUniqueId(), SORT_SETTING, sortId);
            this.savedSort = sortId;
        }
        if (!filterId.equals(this.savedFilter)) {
            settings.setRaw(this.viewer.getUniqueId(), FILTER_SETTING, filterId);
            this.savedFilter = filterId;
        }
    }
}
