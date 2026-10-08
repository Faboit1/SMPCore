package net.siftvanilla.siftcore.feature.shop;

import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the shop feature ({@code lang/shop.yml}). */
public final class ShopMessages {

    public static final MessageKey MENU_TITLE = MessageKey.ui("shop.menu.title");
    public static final MessageKey CATEGORY_COUNT = MessageKey.ui("shop.menu.category-count", "count");
    public static final MessageKey CATEGORY_HINT = MessageKey.ui("shop.menu.category-hint");
    public static final MessageKey BALANCE = MessageKey.ui("shop.menu.balance", "balance");
    public static final MessageKey BALANCE_LORE = MessageKey.ui("shop.menu.balance-lore");

    public static final MessageKey ENTRY_PRICE = MessageKey.ui("shop.entry.price", "price");
    public static final MessageKey ENTRY_MAX = MessageKey.ui("shop.entry.max", "max");
    public static final MessageKey ENTRY_HINT = MessageKey.ui("shop.entry.hint");
    public static final MessageKey SPAWNER_NAME = MessageKey.ui("shop.entry.spawner", "mob");
    public static final MessageKey SORT_SHOP = MessageKey.ui("shop.sort.shop");
    public static final MessageKey SORT_CHEAPEST = MessageKey.ui("shop.sort.cheapest");
    public static final MessageKey SORT_PRICIEST = MessageKey.ui("shop.sort.priciest");
    public static final MessageKey SORT_NAME = MessageKey.ui("shop.sort.name");

    public static final MessageKey BUY_TITLE = MessageKey.ui("shop.buy.title", "item");
    public static final MessageKey BUY_BODY = MessageKey.ui("shop.buy.body", "price", "balance", "max");
    public static final MessageKey BUY_AMOUNT = MessageKey.ui("shop.buy.amount");
    public static final MessageKey BUY_EXACT = MessageKey.ui("shop.buy.exact");
    public static final MessageKey BUY_BUTTON = MessageKey.ui("shop.buy.button", "amount", "total");
    public static final MessageKey BUY_CHANGED = MessageKey.ui("shop.buy.changed");
    public static final MessageKey BUY_INVALID = MessageKey.ui("shop.buy.invalid", "max");
    public static final MessageKey BUY_PRICE_CHANGED = MessageKey.ui("shop.buy.price-changed", "price");
    public static final MessageKey BUY_NOT_ENOUGH = MessageKey.ui("shop.buy.not-enough", "total", "balance");
    public static final MessageKey BUY_TOO_EXPENSIVE = MessageKey.ui("shop.buy.too-expensive");

    public static final MessageKey CONFIRM_TITLE = MessageKey.ui("shop.confirm.title");
    public static final MessageKey CONFIRM_BODY = MessageKey.ui("shop.confirm.body", "amount", "item", "total", "left");
    public static final MessageKey CONFIRM_BUTTON = MessageKey.ui("shop.confirm.button");

    /** The receipt, kept in chat, with the success sound. */
    public static final MessageKey BOUGHT = MessageKey.chat("shop.bought", "amount", "item", "total").withFeedback(Feedback.SUCCESS);
    public static final MessageKey BOUGHT_CLAIM_BOX = MessageKey.chat("shop.bought-claim-box", "amount", "item", "total", "count")
        .withFeedback(Feedback.SUCCESS);
    public static final MessageKey NO_LONGER_SOLD = MessageKey.error("shop.no-longer-sold");
    public static final MessageKey UNAVAILABLE = MessageKey.error("shop.unavailable");
    public static final MessageKey CANCELLED = MessageKey.info("shop.cancelled");
    public static final MessageKey FAILED = MessageKey.error("shop.failed");
    public static final MessageKey UNKNOWN_CATEGORY = MessageKey.error("shop.unknown-category", "name");

    public static final MessageKey HUB_LABEL = MessageKey.ui("shop.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("shop.hub.description");

    private ShopMessages() {
    }
}
