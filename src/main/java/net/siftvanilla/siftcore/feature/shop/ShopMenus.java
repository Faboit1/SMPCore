package net.siftvanilla.siftcore.feature.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Opens the shop's menus and builds their icons from the current {@code features/shop.yml}. */
final class ShopMenus {

    private final Services services;
    private final Setting<ShopSettings> settings;
    private final ShopItems items;
    private final PurchaseFlow purchases;

    ShopMenus(Services services, Setting<ShopSettings> settings, ShopItems items, PurchaseFlow purchases) {
        this.services = services;
        this.settings = settings;
        this.items = items;
        this.purchases = purchases;
    }

    /** The category overview. */
    void openShop(Player player) {
        new ShopMenu(this.services.menus(), player, this, this.settings.get().rows()).open();
    }

    /** One category's items, or an error when it doesn't exist or has nothing to offer right now. */
    void openCategory(Player player, String id) {
        ShopSettings.Category category = this.settings.get().category(id);
        if (category == null || visibleEntries(category).isEmpty()) {
            this.services.messenger().send(player, ShopMessages.UNKNOWN_CATEGORY, Arg.text("name", id));
            return;
        }
        new CategoryMenu(this.services.menus(), player, this, category.id(), Component.text(category.name()), sortCycle(),
            () -> openShop(player)).open();
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

    /** The real item an entry sells, with its price, limit and a click hint added to the tooltip. */
    ItemStack entryIcon(ShopSettings.Entry entry) {
        Lang lang = this.services.lang();
        List<Component> lore = new ArrayList<>();
        lore.addAll(lang.lines(ShopMessages.ENTRY_PRICE, Arg.money("price", entry.price())));
        lore.addAll(lang.lines(ShopMessages.ENTRY_MAX, Arg.number("max", entry.max())));
        lore.add(Component.empty());
        lore.addAll(lang.lines(ShopMessages.ENTRY_HINT));
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
     * Sort orders of a category menu, shop order first. Labels are plain text so the cycle button can colour them
     * (gray, the selected one white).
     */
    Cycle<Comparator<ShopSettings.Entry>> sortCycle() {
        Lang lang = this.services.lang();
        Comparator<ShopSettings.Entry> shopOrder = (a, b) -> 0;
        Comparator<ShopSettings.Entry> cheapest = Comparator.comparingLong(ShopSettings.Entry::price);
        Comparator<ShopSettings.Entry> name = Comparator.comparing(this.items::name);
        return new Cycle<>(List.of(
            new Cycle.Option<>("shop", Component.text(lang.plain(ShopMessages.SORT_SHOP)), shopOrder),
            new Cycle.Option<>("cheapest", Component.text(lang.plain(ShopMessages.SORT_CHEAPEST)), cheapest),
            new Cycle.Option<>("priciest", Component.text(lang.plain(ShopMessages.SORT_PRICIEST)), cheapest.reversed()),
            new Cycle.Option<>("name", Component.text(lang.plain(ShopMessages.SORT_NAME)), name)), "shop");
    }
}
