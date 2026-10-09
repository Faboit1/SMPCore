package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The price list ({@code /worth} with an empty hand, {@code /worth list}): every item the server buys, with its
 * price, the viewer's bonus, its category, the shop price, the best buy order and what the viewer carries. Sorted
 * by name or price, filtered by sell category and searchable; the chosen sort and filter are remembered per player.
 * Clicking an item opens its details.
 */
final class WorthBrowser extends PagedMenu<WorthBrowser.Row> {

    static final String SORT_SETTING = "worth_sort";
    static final String FILTER_SETTING = "worth_filter";

    /** One sellable item. */
    record Row(String key, Material material, String name, WorthTable.Entry entry) {
    }

    private final Services services;
    private final WorthService worth;
    private final Setting<SellSettings> settings;
    private final SellService sales;
    private final OrderBids bids;
    private final Supplier<ShopOffers> shop;
    private final SellDialogs dialogs;
    private final Cycle<Comparator<Row>> sort;
    private final Cycle<Predicate<Row>> filter;
    private final AtomicReference<String> search;
    /** A filter the list was opened with (a category, or "all" for a search); not remembered until changed. */
    private String forcedFilter;
    private Map<String, Long> carried = Map.of();
    private WorthService.Rates rates;

    private WorthBrowser(Services services, Player viewer, WorthService worth, Setting<SellSettings> settings,
                         SellService sales, OrderBids bids, Supplier<ShopOffers> shop, SellDialogs dialogs,
                         Cycle<Comparator<Row>> sort, Cycle<Predicate<Row>> filter, AtomicReference<String> search, Runnable back) {
        super(services.menus(), viewer, Component.text(services.lang().plain(SellMessages.BROWSER_TITLE)), sort, filter, back);
        this.services = services;
        this.worth = worth;
        this.settings = settings;
        this.sales = sales;
        this.bids = bids;
        this.shop = shop;
        this.dialogs = dialogs;
        this.sort = sort;
        this.filter = filter;
        this.search = search;
    }

    /** Remembers the search for the exact-match-first ordering. */
    @Override
    public void query(String query) {
        super.query(query);
        this.search.set(query());
    }

    /**
     * Opens the price list.
     *
     * @param category a category to filter by, or null for the remembered filter
     * @param query    a search to start with, or null
     * @param back     what the Back button does, or null for none
     */
    static void open(Services services, Player viewer, WorthService worth, Setting<SellSettings> settings, SellService sales,
                     OrderBids bids, Supplier<ShopOffers> shop, SellDialogs dialogs, String category, String query, Runnable back) {
        Lang lang = services.lang();
        String sortId = services.settings().raw(viewer.getUniqueId(), SORT_SETTING, "name");
        // An item whose name is exactly the search comes first in every order, so /worth list diamond (the name in
        // /worth is a link to it) shows the diamond itself at the top.
        AtomicReference<String> search = new AtomicReference<>("");
        Comparator<Row> exact = Comparator.comparing(row -> !row.name().equals(search.get()));
        Comparator<Row> byName = Comparator.comparing(Row::name);
        Comparator<Row> byPrice = Comparator.comparingLong(row -> row.entry().price());
        Cycle<Comparator<Row>> sort = new Cycle<>(List.of(
            new Cycle.Option<>("name", Component.text(lang.plain(SellMessages.SORT_NAME)), exact.thenComparing(byName)),
            new Cycle.Option<>("highest", Component.text(lang.plain(SellMessages.SORT_HIGHEST)),
                exact.thenComparing(byPrice.reversed()).thenComparing(byName)),
            new Cycle.Option<>("lowest", Component.text(lang.plain(SellMessages.SORT_LOWEST)),
                exact.thenComparing(byPrice).thenComparing(byName))),
            sortId);
        List<Cycle.Option<Predicate<Row>>> filters = new ArrayList<>();
        filters.add(new Cycle.Option<>("all", Component.text(lang.plain(SellMessages.FILTER_ALL)), row -> true));
        for (SellCategories.Category option : worth.categories().categories().values()) {
            String id = option.id();
            filters.add(new Cycle.Option<>(id, Component.text(option.name()), row -> id.equals(row.entry().category())));
        }
        String filterId = category != null ? category : services.settings().raw(viewer.getUniqueId(), FILTER_SETTING, "all");
        Cycle<Predicate<Row>> filter = new Cycle<>(filters, filterId);
        WorthBrowser menu = new WorthBrowser(services, viewer, worth, settings, sales, bids, shop, dialogs, sort, filter, search, back);
        menu.forcedFilter = category;
        if (query != null && !query.isBlank()) {
            menu.query(query);
        }
        menu.open();
    }

    @Override
    protected List<Row> entries() {
        this.rates = this.worth.rates(this.viewer);
        this.carried = this.sales.builder().carried(this.viewer, this.settings.get());
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, WorthTable.Entry> entry : this.worth.table().entries().entrySet()) {
            Material material = Material.matchMaterial(entry.getKey());
            if (material == null || !material.isItem() || material.isAir()) {
                continue;
            }
            rows.add(new Row(entry.getKey(), material, ItemKeys.name(entry.getKey()), entry.getValue()));
        }
        return rows;
    }

    @Override
    protected ItemStack icon(Row row) {
        Lang lang = this.services.lang();
        List<Component> lore = new ArrayList<>();
        long price = row.entry().price();
        lore.addAll(lang.lines(SellMessages.BROWSER_PRICE, Arg.money("price", price)));
        BigDecimal multiplier = this.rates.multiplier(row.entry().category());
        long withBonus = SaleMath.withMultiplier(price, multiplier);
        if (multiplier.compareTo(BigDecimal.ONE) > 0) {
            lore.addAll(lang.lines(SellMessages.BROWSER_BONUS, Arg.money("price", withBonus)));
        }
        lore.addAll(lang.lines(SellMessages.BROWSER_CATEGORY,
            Arg.text("category", this.worth.categories().name(row.entry().category()))));
        OptionalLong shopPrice = this.shop.get().price(row.key());
        if (shopPrice.isPresent()) {
            lore.addAll(lang.lines(SellMessages.BROWSER_SHOP, Arg.money("price", shopPrice.getAsLong())));
        }
        OrderMarket.Bid best = this.bids.best(this.viewer.getUniqueId(), row.key());
        if (best != null) {
            lore.addAll(lang.lines(SellMessages.BROWSER_ORDER, Arg.money("price", best.priceEach())));
        }
        long count = this.carried.getOrDefault(row.key(), 0L);
        if (count > 0) {
            long total;
            try {
                total = SaleMath.withMultiplier(Math.multiplyExact(price, count), multiplier);
            } catch (ArithmeticException e) {
                total = Long.MAX_VALUE;
            }
            lore.addAll(lang.lines(SellMessages.BROWSER_CARRY, Arg.number("count", count), Arg.money("total", total)));
        }
        if (this.viewer.hasPermission(SellCommands.WORTH_DETAILS)) {
            switch (row.entry().origin()) {
                case BASE -> lore.addAll(lang.lines(SellMessages.BROWSER_SOURCE_BASE));
                case OVERRIDE -> lore.addAll(lang.lines(SellMessages.BROWSER_SOURCE_OVERRIDE));
                case DERIVED -> lore.addAll(lang.lines(SellMessages.BROWSER_SOURCE_DERIVED, Arg.text("recipe", row.entry().recipe())));
            }
        }
        lore.add(Component.empty());
        lore.addAll(lang.lines(SellMessages.BROWSER_HINT));
        return Items.display(ItemStack.of(row.material()), lore);
    }

    @Override
    protected void clicked(Row row, ClickContext click) {
        click(Feedback.CLICK);
        this.dialogs.details(this.viewer, row.key(), this::open);
    }

    @Override
    protected String searchText(Row row) {
        return (row.name() + " " + row.key()).toLowerCase(Locale.ROOT);
    }

    /** Remembers the sort and filter whenever the page is drawn with a new choice. */
    @Override
    protected void drawExtras() {
        remember(SORT_SETTING, this.sort.selected().id());
        String filterId = this.filter.selected().id();
        if (this.forcedFilter == null || !this.forcedFilter.equals(filterId)) {
            // A filter the list was opened with (from mastery, or a search) is only remembered once the player picks one.
            this.forcedFilter = null;
            remember(FILTER_SETTING, filterId);
        }
    }

    private void remember(String setting, String value) {
        if (value == null) {
            return;
        }
        String known = this.services.settings().raw(this.viewer.getUniqueId(), setting, null);
        if (!value.equals(known)) {
            this.services.settings().setRaw(this.viewer.getUniqueId(), setting, value);
        }
    }
}
