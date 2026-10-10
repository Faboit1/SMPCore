package net.siftvanilla.siftcore.feature.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.feature.sell.SellLink;
import net.siftvanilla.siftcore.feature.sell.ShopOffers;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Opens the shop's menus and builds their icons from the current {@code features/shop.yml}. Each player's sort order
 * of the item pages is remembered silently (the {@code shop-sort} row, like the auction house and order browsers).
 */
final class ShopMenus {

    /** The remembered sort order of category and search pages (UI state, not a setting). */
    static final String SORT_SETTING = "shop-sort";
    private static final String DEFAULT_SORT = "shop";

    private final Services services;
    private final Setting<ShopSettings> settings;
    private final ShopItems items;
    private final PurchaseFlow purchases;
    private final SellLink sell;
    private final RecentPurchases recent;

    ShopMenus(Services services, Setting<ShopSettings> settings, ShopItems items, PurchaseFlow purchases, SellLink sell,
              RecentPurchases recent) {
        this.services = services;
        this.settings = settings;
        this.items = items;
        this.purchases = purchases;
        this.sell = sell;
        this.recent = recent;
    }

    /** The category overview. */
    void openShop(Player player) {
        if (this.purchases.blocked(player)) {
            return;
        }
        new ShopMenu(this.services.menus(), player, this, this.settings.get().rows()).open();
    }

    /** One category's items, or an error when it doesn't exist or has nothing to offer right now. */
    void openCategory(Player player, String id) {
        if (this.purchases.blocked(player)) {
            return;
        }
        ShopSettings.Category category = this.settings.get().category(id);
        if (category == null || visibleEntries(category).isEmpty()) {
            this.services.messenger().send(player, ShopMessages.UNKNOWN_CATEGORY, Arg.text("name", id));
            return;
        }
        new CategoryMenu(this.services.menus(), player, this, category.id(), Component.text(category.name()), sortCycle(player),
            () -> openShop(player)).open();
    }

    /**
     * Every item of the shop in one list, searchable and filtered by category. Without a query the search prompt
     * opens right away.
     */
    void openSearch(Player player, String query) {
        if (this.purchases.blocked(player)) {
            return;
        }
        ShopSearchMenu menu = new ShopSearchMenu(this.services.menus(), player, this,
            Component.text(this.services.lang().plain(ShopMessages.SEARCH_TITLE)), sortCycle(player), categoryFilter(),
            () -> openShop(player));
        if (query == null || query.isBlank()) {
            menu.prompt();
        } else {
            menu.query(query);
            menu.open();
        }
    }

    ShopSettings settings() {
        return this.settings.get();
    }

    /** Categories with at least one entry that can be bought right now, in file order. */
    List<ShopSettings.Category> visibleCategories() {
        List<ShopSettings.Category> visible = new ArrayList<>();
        for (ShopSettings.Category category : this.settings.get().categories()) {
            if (!visibleEntries(category).isEmpty()) {
                visible.add(category);
            }
        }
        return visible;
    }

    /** The entries of a category (by id, following reloads) that can be bought right now. */
    List<ShopSettings.Entry> visibleEntries(String categoryId) {
        ShopSettings.Category category = this.settings.get().category(categoryId);
        return category == null ? List.of() : visibleEntries(category);
    }

    /** Every entry that can be bought right now, in shop order (categories in file order). */
    List<ShopSettings.Entry> allVisibleEntries() {
        List<ShopSettings.Entry> all = new ArrayList<>();
        for (ShopSettings.Category category : this.settings.get().categories()) {
            all.addAll(visibleEntries(category));
        }
        return all;
    }

    private List<ShopSettings.Entry> visibleEntries(ShopSettings.Category category) {
        List<ShopSettings.Entry> visible = new ArrayList<>(category.entries().size());
        for (ShopSettings.Entry entry : category.entries()) {
            if (this.items.available(entry)) {
                visible.add(entry);
            }
        }
        return visible;
    }

    ItemStack categoryIcon(ShopSettings.Category category, int count) {
        Lang lang = this.services.lang();
        Material material = Material.matchMaterial(category.icon());
        List<Component> lore = new ArrayList<>();
        if (!category.description().isEmpty()) {
            lore.add(this.services.lang().style().secondary(category.description()));
        }
        lore.addAll(lang.lines(ShopMessages.CATEGORY_COUNT, Arg.number("count", count)));
        lore.add(Component.empty());
        lore.addAll(lang.lines(ShopMessages.CATEGORY_HINT));
        return Items.icon(material == null || !material.isItem() ? Material.CHEST : material,
            this.services.lang().style().primary(category.name()), lore);
    }

    ItemStack balanceIcon(Player player) {
        Lang lang = this.services.lang();
        return Items.icon(Material.GOLD_INGOT, lang.get(ShopMessages.BALANCE,
            Arg.money("balance", this.services.ledger().balance(player.getUniqueId(), Currency.MONEY))),
            lang.lines(ShopMessages.BALANCE_LORE));
    }

    ItemStack searchIcon() {
        Lang lang = this.services.lang();
        return Items.icon(Material.OAK_SIGN, lang.get(ShopMessages.SEARCH), lang.lines(ShopMessages.SEARCH_LORE));
    }

    /** The player's last purchases that can still be bought, newest first. */
    List<RecentPurchases.Recent> recent(Player player) {
        List<RecentPurchases.Recent> shown = new ArrayList<>();
        for (RecentPurchases.Recent purchase : this.recent.of(player.getUniqueId())) {
            ShopSettings.Entry entry = this.settings.get().entry(purchase.ref());
            if (entry != null && this.items.available(entry) && this.items.unit(entry).isPresent()) {
                shown.add(purchase);
            }
        }
        return shown;
    }

    /** A "Buy again" icon: the item with what was last bought and what that amount costs now. */
    ItemStack recentIcon(RecentPurchases.Recent purchase) {
        ShopSettings.Entry entry = this.settings.get().entry(purchase.ref());
        Lang lang = this.services.lang();
        int amount = Math.min(purchase.amount(), entry.max());
        long total = PurchaseMath.total(entry.price(), amount, this.services.money().get().maxAmount()).orElse(0);
        List<Component> lore = new ArrayList<>();
        lore.addAll(lang.lines(ShopMessages.RECENT, Arg.number("amount", amount), Arg.money("total", total)));
        lore.add(Component.empty());
        lore.addAll(lang.lines(ShopMessages.RECENT_HINT));
        return Items.display(this.items.unit(entry).orElseThrow(), lore);
    }

    /** "Buy again": the purchase dialog at the amount last bought. */
    void buyAgain(Player player, RecentPurchases.Recent purchase, Runnable back) {
        this.purchases.open(player, purchase.ref(), back, purchase.amount());
    }

    /**
     * The real item an entry sells, with its price, limit, what it sells back for (the viewer's own rate) and click
     * hints added to the tooltip.
     *
     * @param category the category name to show (in search results), or null
     */
    ItemStack entryIcon(ShopSettings.Entry entry, Player viewer, String category) {
        Lang lang = this.services.lang();
        List<Component> lore = new ArrayList<>();
        lore.addAll(lang.lines(ShopMessages.ENTRY_PRICE, Arg.money("price", entry.price())));
        lore.addAll(lang.lines(ShopMessages.ENTRY_MAX, Arg.number("max", entry.max())));
        long sellBack = entry.spawner() ? 0 : this.sell.sellBack(viewer, entry.item());
        if (sellBack > 0) {
            lore.addAll(lang.lines(ShopMessages.ENTRY_SELLS_BACK, Arg.money("price", sellBack)));
        }
        if (category != null) {
            lore.addAll(lang.lines(ShopMessages.ENTRY_CATEGORY, Arg.text("category", category)));
        }
        lore.add(Component.empty());
        lore.addAll(lang.lines(ShopMessages.ENTRY_HINT));
        if (sellBack > 0) {
            lore.addAll(lang.lines(ShopMessages.ENTRY_SELL_HINT));
        }
        Optional<ItemStack> unit = this.items.unit(entry);
        if (unit.isPresent()) {
            return Items.display(unit.get(), lore);
        }
        return Items.icon(Material.BARRIER, this.services.lang().style().primary(this.items.name(entry)), lore);
    }

    String searchText(ShopSettings.Entry entry) {
        return (this.items.name(entry) + " " + entry.id()).toLowerCase(Locale.ROOT);
    }

    /** Opens the purchase dialog; Back returns to {@code back}. */
    void buy(Player player, ShopSettings.Entry entry, Runnable back) {
        this.purchases.open(player, entry.ref(), back);
    }

    /**
     * A right click on an entry the server buys: asks whether to sell every plain one the player carries (always
     * with a confirmation; Cancel returns to {@code back}).
     */
    void sell(Player player, ShopSettings.Entry entry, Runnable back) {
        if (entry.spawner() || this.sell.sellBack(player, entry.item()) <= 0) {
            buy(player, entry, back);
            return;
        }
        this.sell.offerSell(player, entry.item(), back);
    }

    /** The category name of an entry, for search results. */
    String categoryName(ShopSettings.Entry entry) {
        ShopSettings.Category category = this.settings.get().category(entry.category());
        return category == null ? entry.category() : category.name();
    }

    /**
     * Sort orders of a category menu, shop order first, starting at the player's remembered one (or at the one of the
     * shop page they are still looking at: its choice is saved only when it closes, after this menu was built).
     * Labels are plain text so the cycle button can colour them (gray, the selected one white).
     */
    Cycle<Comparator<ShopSettings.Entry>> sortCycle(Player player) {
        String initial = this.services.settings().raw(player.getUniqueId(), SORT_SETTING, DEFAULT_SORT);
        Object open = player.getOpenInventory().getTopInventory().getHolder(false);
        if (open instanceof CategoryMenu menu) {
            initial = menu.sortId();
        } else if (open instanceof ShopSearchMenu menu) {
            initial = menu.sortId();
        }
        return sortCycle(initial);
    }

    /** Remembers the sort order a shop page closed with, when it differs from the stored one. */
    void rememberSort(Player player, String sortId) {
        if (!sortId.equals(this.services.settings().raw(player.getUniqueId(), SORT_SETTING, DEFAULT_SORT))) {
            this.services.settings().setRaw(player.getUniqueId(), SORT_SETTING, sortId);
        }
    }

    private Cycle<Comparator<ShopSettings.Entry>> sortCycle(String initial) {
        Lang lang = this.services.lang();
        Comparator<ShopSettings.Entry> shopOrder = (a, b) -> 0;
        Comparator<ShopSettings.Entry> cheapest = Comparator.comparingLong(ShopSettings.Entry::price);
        Comparator<ShopSettings.Entry> name = Comparator.comparing(this.items::name);
        return new Cycle<>(List.of(
            new Cycle.Option<>("shop", Component.text(lang.plain(ShopMessages.SORT_SHOP)), shopOrder),
            new Cycle.Option<>("cheapest", Component.text(lang.plain(ShopMessages.SORT_CHEAPEST)), cheapest),
            new Cycle.Option<>("priciest", Component.text(lang.plain(ShopMessages.SORT_PRICIEST)), cheapest.reversed()),
            new Cycle.Option<>("name", Component.text(lang.plain(ShopMessages.SORT_NAME)), name)), initial);
    }

    /** The search menu's filter: all categories, then each visible category. */
    private Cycle<Predicate<ShopSettings.Entry>> categoryFilter() {
        Lang lang = this.services.lang();
        List<Cycle.Option<Predicate<ShopSettings.Entry>>> options = new ArrayList<>();
        options.add(new Cycle.Option<>("all", Component.text(lang.plain(ShopMessages.SEARCH_FILTER_ALL)), entry -> true));
        for (ShopSettings.Category category : visibleCategories()) {
            String id = category.id();
            options.add(new Cycle.Option<>(id, Component.text(category.name()), entry -> id.equals(entry.category())));
        }
        return new Cycle<>(options, "all");
    }

    /** The shop as selling sees it: the lowest price of a plain item and opening its purchase dialog. */
    ShopOffers offers() {
        return new ShopOffers() {
            @Override
            public OptionalLong price(String itemKey) {
                ShopSettings.Entry entry = cheapest(itemKey);
                return entry == null ? OptionalLong.empty() : OptionalLong.of(entry.price());
            }

            @Override
            public boolean open(Player player, String itemKey, Runnable back) {
                ShopSettings.Entry entry = cheapest(itemKey);
                if (entry == null) {
                    return false;
                }
                ShopMenus.this.purchases.open(player, entry.ref(), back);
                return true;
            }
        };
    }

    /** The cheapest visible entry that sells exactly this plain item, or null. */
    private ShopSettings.Entry cheapest(String itemKey) {
        ShopSettings.Entry best = null;
        for (ShopSettings.Category category : this.settings.get().categories()) {
            for (ShopSettings.Entry entry : category.entries()) {
                if (!entry.spawner() && itemKey.equals(entry.item()) && (best == null || entry.price() < best.price())) {
                    best = entry;
                }
            }
        }
        return best;
    }
}
