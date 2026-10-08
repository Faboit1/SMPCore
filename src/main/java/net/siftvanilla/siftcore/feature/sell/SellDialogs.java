package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The sell dialogs: the details of one item (where selling, the shop and buy orders meet), sell mastery and the
 * top sellers.
 */
final class SellDialogs {

    /** Opens the worth browser (filtered to a category, or not). */
    interface Browser {
        void open(Player player, String category, String query);
    }

    private final Services services;
    private final WorthService worth;
    private final Setting<SellSettings> settings;
    private final SellService sales;
    private final OrderBids bids;
    private final MasteryBook mastery;
    private final TopSellers top;
    private final Supplier<ShopOffers> shop;
    private Browser browser = (player, category, query) -> { };

    SellDialogs(Services services, WorthService worth, Setting<SellSettings> settings, SellService sales, OrderBids bids,
                MasteryBook mastery, TopSellers top, Supplier<ShopOffers> shop) {
        this.services = services;
        this.worth = worth;
        this.settings = settings;
        this.sales = sales;
        this.bids = bids;
        this.mastery = mastery;
        this.top = top;
        this.shop = shop;
    }

    void browser(Browser browser) {
        this.browser = browser;
    }

    // ------------------------------------------------------------------ item details

    /** The details of one item: prices, bonus, mastery, shop, orders, and what can be done with it. */
    void details(Player player, String key, Runnable back) {
        Lang lang = this.services.lang();
        Material material = Material.matchMaterial(key);
        if (material == null || !material.isItem()) {
            return;
        }
        WorthTable.Entry entry = this.worth.table().entry(key);
        WorthService.Rates rates = this.worth.rates(player);
        List<Component> lines = new ArrayList<>();
        if (entry != null) {
            lines.addAll(lang.lines(SellMessages.DETAILS_PRICE, Arg.money("price", entry.price())));
            BigDecimal multiplier = rates.multiplier(entry.category());
            if (multiplier.compareTo(BigDecimal.ONE) > 0) {
                lines.addAll(lang.lines(SellMessages.DETAILS_BONUS, Arg.text("multiplier", Multipliers.format(multiplier.doubleValue())),
                    Arg.money("price", SaleMath.withMultiplier(entry.price(), multiplier))));
            }
            Mastery rules = this.settings.get().mastery();
            if (rules.enabled()) {
                lines.addAll(lang.lines(SellMessages.DETAILS_MASTERY,
                    Arg.text("category", this.worth.categories().name(entry.category())),
                    Arg.number("level", rates.level(entry.category())), Arg.number("max", rules.maxLevel())));
            }
        } else {
            lines.addAll(lang.lines(SellMessages.DETAILS_NOT_SOLD));
        }
        OptionalLong shopPrice = this.shop.get().price(key);
        shopPrice.ifPresent(price -> lines.addAll(lang.lines(SellMessages.DETAILS_SHOP, Arg.money("price", price))));
        OrderMarket.Bid best = this.bids.best(player.getUniqueId(), key);
        if (best != null) {
            lines.addAll(lang.lines(SellMessages.DETAILS_ORDER, Arg.money("price", best.priceEach())));
        }
        long carried = this.sales.builder().carried(player, this.settings.get()).getOrDefault(key, 0L);
        if (carried > 0) {
            lines.addAll(lang.lines(SellMessages.DETAILS_CARRY, Arg.number("count", carried)));
        }
        List<Body> body = new ArrayList<>();
        body.add(Body.item(ItemStack.of(material), null));
        body.add(Body.text(Component.join(JoinConfiguration.newlines(), lines)));

        List<Button> buttons = new ArrayList<>();
        if (carried > 0) {
            SaleBuilder.Result preview = this.sales.preview(player, player.getInventory(), SaleRequest.type(key));
            if (preview.draft() != null) {
                SaleDraft draft = preview.draft();
                buttons.add(Button.of(lang.get(SellMessages.DETAILS_SELL, Arg.text("count", Lang.number(draft.count())),
                    Arg.text("total", this.services.money().get().format(draft.total()))), s -> {
                        s.close();
                        this.sales.sellType(s.player(), key, false, () -> details(s.player(), key, back));
                    }).width(Templates.WIDE));
            }
        }
        if (shopPrice.isPresent()) {
            long price = shopPrice.getAsLong();
            buttons.add(Button.of(lang.get(SellMessages.DETAILS_BUY, Arg.text("price", this.services.money().get().format(price))),
                s -> {
                    if (!this.shop.get().open(s.player(), key, () -> details(s.player(), key, back))) {
                        s.close();
                    }
                }).width(Templates.WIDE));
        }
        OrderMarket market = this.bids.market();
        if (market.available()) {
            buttons.add(Button.of(lang.get(SellMessages.DETAILS_ORDER_IT), s -> {
                if (!market.openOrderForm(s.player(), key, () -> details(s.player(), key, back))) {
                    s.close();
                }
            }).width(Templates.WIDE));
        }
        Component title = Component.translatable(material);
        this.services.dialogs().show(player, this.services.templates().listWithBody(title, body, buttons, 1, s -> {
            s.close();
            back.run();
        }));
    }

    // ------------------------------------------------------------------ mastery

    /** {@code /sell mastery}: every category with its level and progress; each opens its details. */
    void mastery(Player player) {
        Mastery rules = this.settings.get().mastery();
        Lang lang = this.services.lang();
        if (!rules.enabled()) {
            this.services.messenger().send(player, SellMessages.MASTERY_OFF);
            return;
        }
        WorthService.Rates rates = this.worth.rates(player);
        SellCategories categories = this.worth.categories();
        List<Component> lines = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        for (SellCategories.Category category : categories.categories().values()) {
            lines.add(masteryLine(rates, rules, category));
            String id = category.id();
            buttons.add(Button.of(Component.text(category.name()), s -> masteryDetail(s.player(), id)).width(150));
        }
        this.services.dialogs().show(player, this.services.templates().list(lang.get(SellMessages.MASTERY_TITLE), lines,
            buttons, 2, null));
    }

    private Component masteryLine(WorthService.Rates rates, Mastery rules, SellCategories.Category category) {
        Lang lang = this.services.lang();
        int level = rates.level(category.id());
        long sold = rates.sold(category.id());
        String multiplier = Multipliers.format(rates.multiplier(category.id()).doubleValue());
        if (level >= rules.maxLevel()) {
            return lang.get(SellMessages.MASTERY_LINE_MAX, Arg.text("name", category.name()), Arg.text("multiplier", multiplier),
                Arg.money("sold", sold));
        }
        return lang.get(SellMessages.MASTERY_LINE, Arg.text("name", category.name()), Arg.number("level", level),
            Arg.number("max", rules.maxLevel()), Arg.text("multiplier", multiplier), Arg.money("sold", sold),
            Arg.money("next", rules.threshold(level + 1)), Arg.number("next-level", level + 1));
    }

    /** One category: the level ladder, the rate, selling everything of it and its prices. */
    void masteryDetail(Player player, String id) {
        Mastery rules = this.settings.get().mastery();
        SellCategories.Category category = this.worth.categories().category(id);
        if (!rules.enabled() || category == null) {
            mastery(player);
            return;
        }
        Lang lang = this.services.lang();
        WorthService.Rates rates = this.worth.rates(player);
        long sold = rates.sold(id);
        int level = rates.level(id);
        List<Component> lines = new ArrayList<>();
        lines.addAll(lang.lines(SellMessages.MASTERY_DETAIL_RATE, Arg.text("name", category.name()),
            Arg.text("multiplier", Multipliers.format(rates.multiplier(id).doubleValue())),
            Arg.text("rank", Multipliers.format(rates.rank().doubleValue())),
            Arg.text("bonus", Multipliers.format(rates.bonus(id).doubleValue()))));
        for (int step = rules.maxLevel(); step >= 1; step--) {
            long threshold = rules.threshold(step);
            if (step <= level) {
                lines.addAll(lang.lines(SellMessages.MASTERY_LADDER_DONE, Arg.number("level", step), Arg.money("threshold", threshold)));
            } else if (step == level + 1) {
                lines.addAll(lang.lines(SellMessages.MASTERY_LADDER_NEXT, Arg.number("level", step), Arg.money("threshold", threshold),
                    Arg.money("left", Math.max(0, threshold - sold))));
            } else {
                lines.addAll(lang.lines(SellMessages.MASTERY_LADDER_LATER, Arg.number("level", step), Arg.money("threshold", threshold)));
            }
        }
        List<Button> buttons = new ArrayList<>();
        SaleBuilder.Result preview = this.sales.preview(player, player.getInventory(), SaleRequest.category(id));
        if (preview.draft() != null) {
            SaleDraft draft = preview.draft();
            buttons.add(Button.of(lang.get(SellMessages.MASTERY_SELL, Arg.text("name", category.name()),
                Arg.text("count", Lang.number(draft.count())), Arg.text("total", this.services.money().get().format(draft.total()))),
                s -> {
                    s.close();
                    this.sales.sellCategory(s.player(), id);
                }).width(Templates.WIDE));
        }
        buttons.add(Button.of(lang.get(SellMessages.MASTERY_PRICES), s -> {
            s.close();
            this.browser.open(s.player(), id, null);
        }).width(Templates.WIDE));
        this.services.dialogs().show(player, this.services.templates().list(
            lang.get(SellMessages.MASTERY_DETAIL_TITLE, Arg.text("name", category.name())), lines, buttons, 1,
            s -> mastery(s.player())));
    }

    // ------------------------------------------------------------------ top sellers

    /** {@code /sell top}: the ten players who sold the most, and the viewer's own place. */
    void top(Player player) {
        Lang lang = this.services.lang();
        TopSellers.Snapshot snapshot = this.top.snapshot();
        List<Component> lines = new ArrayList<>();
        if (snapshot.top().isEmpty()) {
            lines.addAll(lang.lines(SellMessages.TOP_EMPTY));
        }
        int place = 1;
        for (TopSellers.Entry entry : snapshot.top()) {
            lines.addAll(lang.lines(SellMessages.TOP_LINE, Arg.number("place", place++), Arg.text("name", entry.name()),
                Arg.money("sold", entry.sold())));
        }
        long own = Math.max(this.mastery.total(player.getUniqueId()),
            snapshot.byUuid().getOrDefault(player.getUniqueId(), 0L));
        lines.add(Component.empty());
        if (own > 0) {
            lines.addAll(lang.lines(SellMessages.TOP_YOU, Arg.number("rank", snapshot.rankOf(own)), Arg.money("sold", own)));
        } else {
            lines.addAll(lang.lines(SellMessages.TOP_YOU_NONE));
        }
        this.services.dialogs().show(player, this.services.templates().notice(lang.get(SellMessages.TOP_TITLE), lines,
            lang.get(CoreMessages.UI_CLOSE), null));
    }

    /** A per-category summary line for staff, e.g. "Mining level 2, $300,000 sold". */
    List<Component> adminLines(Map<String, Long> totals) {
        Lang lang = this.services.lang();
        Mastery rules = this.settings.get().mastery();
        List<Component> lines = new ArrayList<>();
        totals.forEach((category, sold) -> lines.add(lang.get(SellMessages.ADMIN_LINE,
            Arg.text("category", this.worth.categories().name(category)), Arg.number("level", rules.level(sold)),
            Arg.money("sold", sold))));
        return lines;
    }
}
