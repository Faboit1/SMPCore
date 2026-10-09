package net.siftvanilla.siftcore.feature.sell;

import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the sell feature ({@code lang/sell.yml}). */
public final class SellMessages {

    /** The receipt, kept in chat, with the success sound. */
    public static final MessageKey SOLD = MessageKey.chat("sell.sold", "items", "total").withFeedback(Feedback.SUCCESS);
    public static final MessageKey SOLD_BONUS = MessageKey.chat("sell.sold-bonus", "items", "total", "multiplier")
        .withFeedback(Feedback.SUCCESS);
    public static final MessageKey SOLD_BONUSES = MessageKey.chat("sell.sold-bonuses", "items", "total")
        .withFeedback(Feedback.SUCCESS);
    public static final MessageKey SOLD_ORDERS = MessageKey.chat("sell.sold-orders", "items", "total", "orders")
        .withFeedback(Feedback.SUCCESS);
    public static final MessageKey SOLD_ACTION_BAR = MessageKey.success("sell.sold-action-bar", "total");
    public static final MessageKey ORDERS_CHANGED = MessageKey.chat("sell.orders-changed");
    public static final MessageKey ITEMS_ONE = MessageKey.ui("sell.items.one", "amount", "item");
    public static final MessageKey ITEMS_MANY = MessageKey.ui("sell.items.many", "amount");
    public static final MessageKey RECEIPT_LINE = MessageKey.ui("sell.receipt.line", "amount", "item", "value");
    public static final MessageKey RECEIPT_MORE = MessageKey.ui("sell.receipt.more", "count");
    public static final MessageKey RECEIPT_BONUS = MessageKey.ui("sell.receipt.bonus", "multiplier");
    public static final MessageKey RECEIPT_CATEGORY_BONUS = MessageKey.ui("sell.receipt.category-bonus", "category", "multiplier");
    public static final MessageKey RECEIPT_ORDER = MessageKey.ui("sell.receipt.order", "amount", "item", "owner", "value");
    public static final MessageKey RECEIPT_TAX = MessageKey.ui("sell.receipt.tax", "tax");
    public static final MessageKey RECEIPT_REST = MessageKey.ui("sell.receipt.rest", "value");
    public static final MessageKey RECEIPT_BOXES = MessageKey.ui("sell.receipt.boxes", "count");
    public static final MessageKey LEVEL_UP = MessageKey.chat("sell.level-up", "category", "level", "multiplier")
        .withFeedback(Feedback.SUCCESS);

    public static final MessageKey EMPTY_HAND = MessageKey.error("sell.empty-hand");
    public static final MessageKey NOT_SELLABLE = MessageKey.error("sell.not-sellable", "item");
    public static final MessageKey MODIFIED = MessageKey.error("sell.modified");
    public static final MessageKey TRADED = MessageKey.error("sell.traded");
    public static final MessageKey HOLD_PLAIN = MessageKey.error("sell.hold-plain", "item");
    public static final MessageKey BOX_NOTHING = MessageKey.error("sell.box-nothing");
    public static final MessageKey BUNDLE_NOTHING = MessageKey.error("sell.bundle-nothing");
    public static final MessageKey NOTHING_TO_SELL = MessageKey.error("sell.nothing-to-sell");
    public static final MessageKey NOTHING_OF_TYPE = MessageKey.error("sell.nothing-of-type", "item");
    public static final MessageKey NOTHING_IN_CATEGORY = MessageKey.error("sell.nothing-in-category", "category");
    public static final MessageKey NOTHING_IN_MENU = MessageKey.error("sell.nothing-in-menu");
    public static final MessageKey IN_COMBAT = MessageKey.error("sell.in-combat", "time");
    public static final MessageKey LOADING = MessageKey.error("sell.loading");
    public static final MessageKey CANCELLED = MessageKey.info("sell.cancelled");
    public static final MessageKey FAILED = MessageKey.error("sell.failed");
    public static final MessageKey BALANCE_FULL = MessageKey.error("sell.balance-full");
    public static final MessageKey TOO_MUCH = MessageKey.error("sell.too-much");
    public static final MessageKey CLAIM_BOX = MessageKey.chat("sell.claim-box", "count");

    public static final MessageKey CONFIRM_TITLE = MessageKey.ui("sell.confirm.title");
    public static final MessageKey CONFIRM_TITLE_TYPE = MessageKey.ui("sell.confirm.title-type", "item");
    public static final MessageKey CONFIRM_TITLE_CATEGORY = MessageKey.ui("sell.confirm.title-category", "category");
    public static final MessageKey CONFIRM_BODY = MessageKey.ui("sell.confirm.body", "count", "total");
    public static final MessageKey CONFIRM_BONUS = MessageKey.ui("sell.confirm.bonus", "multiplier");
    public static final MessageKey CONFIRM_BONUSES = MessageKey.ui("sell.confirm.bonuses");
    public static final MessageKey CONFIRM_ORDERS = MessageKey.ui("sell.confirm.orders", "orders", "count");
    public static final MessageKey CONFIRM_INNER = MessageKey.ui("sell.confirm.inner", "count");
    public static final MessageKey CONFIRM_KEPT = MessageKey.ui("sell.confirm.kept", "count");
    public static final MessageKey CONFIRM_SELL = MessageKey.ui("sell.confirm.sell", "total");
    public static final MessageKey CONFIRM_CHOOSE = MessageKey.ui("sell.confirm.choose");
    public static final MessageKey CONFIRM_CHANGED = MessageKey.ui("sell.confirm.changed");

    public static final MessageKey MENU_TITLE = MessageKey.ui("sell.menu.title");
    public static final MessageKey MENU_TOTAL = MessageKey.ui("sell.menu.total", "total");
    public static final MessageKey MENU_TOTAL_COUNT = MessageKey.ui("sell.menu.total-count", "count");
    public static final MessageKey MENU_TOTAL_LINE = MessageKey.ui("sell.menu.total-line", "amount", "item", "value");
    public static final MessageKey MENU_TOTAL_MORE = MessageKey.ui("sell.menu.total-more", "count");
    public static final MessageKey MENU_TOTAL_BONUS = MessageKey.ui("sell.menu.total-bonus", "multiplier");
    public static final MessageKey MENU_TOTAL_BONUSES = MessageKey.ui("sell.menu.total-bonuses");
    public static final MessageKey MENU_TOTAL_ORDERS = MessageKey.ui("sell.menu.total-orders", "orders", "count");
    public static final MessageKey MENU_TOTAL_UNSELLABLE = MessageKey.ui("sell.menu.total-unsellable", "count");
    public static final MessageKey MENU_TOTAL_MASTERY = MessageKey.ui("sell.menu.total-mastery", "value", "category", "level");
    public static final MessageKey MENU_TOTAL_EMPTY = MessageKey.ui("sell.menu.total-empty");
    public static final MessageKey MENU_TOTAL_HINT = MessageKey.ui("sell.menu.total-hint");
    public static final MessageKey MENU_SELL = MessageKey.ui("sell.menu.sell");
    public static final MessageKey MENU_SELL_LORE = MessageKey.ui("sell.menu.sell-lore", "total");
    public static final MessageKey MENU_SELL_EMPTY = MessageKey.ui("sell.menu.sell-empty");
    public static final MessageKey MENU_TOTAL_LOWER = MessageKey.error("sell.menu.total-lower", "total");
    public static final MessageKey MENU_ADD = MessageKey.ui("sell.menu.add");
    public static final MessageKey MENU_ADD_LORE = MessageKey.ui("sell.menu.add-lore");
    public static final MessageKey MENU_ADD_NONE = MessageKey.error("sell.menu.add-none");
    public static final MessageKey MENU_RESTORED = MessageKey.chat("sell.menu.restored");
    public static final MessageKey MENU_ADD_FULL = MessageKey.info("sell.menu.add-full");
    public static final MessageKey MENU_GIVE_BACK = MessageKey.ui("sell.menu.give-back");
    public static final MessageKey MENU_GIVE_BACK_LORE = MessageKey.ui("sell.menu.give-back-lore");
    public static final MessageKey MENU_MASTERY = MessageKey.ui("sell.menu.mastery");
    public static final MessageKey MENU_MASTERY_LORE = MessageKey.ui("sell.menu.mastery-lore");

    public static final MessageKey MASTERY_TITLE = MessageKey.ui("sell.mastery.title");
    public static final MessageKey MASTERY_LINE = MessageKey.ui("sell.mastery.line", "name", "level", "max", "multiplier",
        "sold", "next", "next-level");
    public static final MessageKey MASTERY_LINE_MAX = MessageKey.ui("sell.mastery.line-max", "name", "multiplier", "sold");
    public static final MessageKey MASTERY_OFF = MessageKey.error("sell.mastery.disabled");
    public static final MessageKey MASTERY_DETAIL_TITLE = MessageKey.ui("sell.mastery.detail-title", "name");
    public static final MessageKey MASTERY_DETAIL_RATE = MessageKey.ui("sell.mastery.detail-rate", "name", "multiplier",
        "rank", "bonus");
    public static final MessageKey MASTERY_LADDER_DONE = MessageKey.ui("sell.mastery.ladder-done", "level", "threshold");
    public static final MessageKey MASTERY_LADDER_NEXT = MessageKey.ui("sell.mastery.ladder-next", "level", "threshold", "left");
    public static final MessageKey MASTERY_LADDER_LATER = MessageKey.ui("sell.mastery.ladder-later", "level", "threshold");
    public static final MessageKey MASTERY_SELL = MessageKey.ui("sell.mastery.sell", "name", "count", "total");
    public static final MessageKey MASTERY_PRICES = MessageKey.ui("sell.mastery.prices");

    public static final MessageKey TOP_TITLE = MessageKey.ui("sell.top.title");
    public static final MessageKey TOP_LINE = MessageKey.ui("sell.top.line", "place", "name", "sold");
    public static final MessageKey TOP_EMPTY = MessageKey.ui("sell.top.empty");
    public static final MessageKey TOP_YOU = MessageKey.ui("sell.top.you", "rank", "sold");
    public static final MessageKey TOP_YOU_NONE = MessageKey.ui("sell.top.you-none");

    public static final MessageKey HISTORY_TITLE = MessageKey.ui("sell.history.title");
    public static final MessageKey HISTORY_ENTRY = MessageKey.ui("sell.history.entry", "total");
    public static final MessageKey HISTORY_AGO = MessageKey.ui("sell.history.ago", "time");
    public static final MessageKey HISTORY_ORDERS = MessageKey.ui("sell.history.orders", "orders");
    public static final MessageKey HISTORY_ITEMS = MessageKey.ui("sell.history.items", "items");
    public static final MessageKey HISTORY_MORE = MessageKey.ui("sell.history.more");

    public static final MessageKey BROWSER_TITLE = MessageKey.ui("sell.browser.title");
    public static final MessageKey BROWSER_PRICE = MessageKey.ui("sell.browser.price", "price");
    public static final MessageKey BROWSER_BONUS = MessageKey.ui("sell.browser.bonus", "price");
    public static final MessageKey BROWSER_CATEGORY = MessageKey.ui("sell.browser.category", "category");
    public static final MessageKey BROWSER_SHOP = MessageKey.ui("sell.browser.shop", "price");
    public static final MessageKey BROWSER_ORDER = MessageKey.ui("sell.browser.order", "price");
    public static final MessageKey BROWSER_CARRY = MessageKey.ui("sell.browser.carry", "count", "total");
    public static final MessageKey BROWSER_SOURCE_BASE = MessageKey.ui("sell.browser.source-base");
    public static final MessageKey BROWSER_SOURCE_DERIVED = MessageKey.ui("sell.browser.source-derived", "recipe");
    public static final MessageKey BROWSER_SOURCE_OVERRIDE = MessageKey.ui("sell.browser.source-override");
    public static final MessageKey BROWSER_HINT = MessageKey.ui("sell.browser.hint");
    public static final MessageKey SORT_NAME = MessageKey.ui("sell.browser.sort-name");
    public static final MessageKey SORT_HIGHEST = MessageKey.ui("sell.browser.sort-highest");
    public static final MessageKey SORT_LOWEST = MessageKey.ui("sell.browser.sort-lowest");
    public static final MessageKey FILTER_ALL = MessageKey.ui("sell.browser.filter-all");

    public static final MessageKey DETAILS_PRICE = MessageKey.ui("sell.details.price", "price");
    public static final MessageKey DETAILS_BONUS = MessageKey.ui("sell.details.bonus", "multiplier", "price");
    public static final MessageKey DETAILS_MASTERY = MessageKey.ui("sell.details.mastery", "category", "level", "max");
    public static final MessageKey DETAILS_SHOP = MessageKey.ui("sell.details.shop", "price");
    public static final MessageKey DETAILS_ORDER = MessageKey.ui("sell.details.order", "price");
    public static final MessageKey DETAILS_CARRY = MessageKey.ui("sell.details.carry", "count");
    public static final MessageKey DETAILS_NOT_SOLD = MessageKey.ui("sell.details.not-sold");
    public static final MessageKey DETAILS_SELL = MessageKey.ui("sell.details.sell", "count", "total");
    public static final MessageKey DETAILS_BUY = MessageKey.ui("sell.details.buy", "price");
    public static final MessageKey DETAILS_ORDER_IT = MessageKey.ui("sell.details.order-it");

    public static final MessageKey WORTH_EACH = MessageKey.chat("sell.worth.each", "item", "price");
    public static final MessageKey WORTH_STACK = MessageKey.chat("sell.worth.stack", "item", "price", "amount", "total");
    public static final MessageKey WORTH_BONUS = MessageKey.chat("sell.worth.bonus", "multiplier", "total");
    public static final MessageKey WORTH_SHOP = MessageKey.chat("sell.worth.shop", "price");
    public static final MessageKey WORTH_ORDER = MessageKey.chat("sell.worth.order", "price");
    public static final MessageKey WORTH_BOX = MessageKey.chat("sell.worth.box", "total", "count");
    public static final MessageKey WORTH_BOX_EMPTY = MessageKey.chat("sell.worth.box-empty", "price");
    public static final MessageKey WORTH_BOX_NOTHING = MessageKey.chat("sell.worth.box-nothing");
    public static final MessageKey WORTH_NONE = MessageKey.chat("sell.worth.none", "item");
    public static final MessageKey WORTH_BELOW_ONE = MessageKey.chat("sell.worth.below-one", "item");
    public static final MessageKey WORTH_MODIFIED = MessageKey.chat("sell.worth.modified", "item", "price");
    public static final MessageKey WORTH_TRADED = MessageKey.chat("sell.worth.traded", "item", "price");
    public static final MessageKey WORTH_HOLD = MessageKey.error("sell.worth.hold");
    public static final MessageKey WORTH_CLICK = MessageKey.ui("sell.worth.click");
    public static final MessageKey WORTH_SOURCE_BASE = MessageKey.chat("sell.worth.source-base");
    public static final MessageKey WORTH_SOURCE_DERIVED = MessageKey.chat("sell.worth.source-derived", "recipe");
    public static final MessageKey WORTH_SOURCE_OVERRIDE = MessageKey.chat("sell.worth.source-override");

    public static final MessageKey ADMIN_HEADER = MessageKey.chat("sell.admin.header", "player");
    public static final MessageKey ADMIN_LINE = MessageKey.chat("sell.admin.line", "category", "level", "sold");
    public static final MessageKey ADMIN_NONE = MessageKey.chat("sell.admin.none", "player");
    public static final MessageKey ADMIN_RESET = MessageKey.chat("sell.admin.reset", "player", "category");
    public static final MessageKey ADMIN_RESET_ALL = MessageKey.chat("sell.admin.reset-all", "player");
    public static final MessageKey ADMIN_SET = MessageKey.chat("sell.admin.set", "player", "category", "level");
    public static final MessageKey ADMIN_UNKNOWN_CATEGORY = MessageKey.error("sell.admin.unknown-category", "name");
    public static final MessageKey ADMIN_LEVEL_RANGE = MessageKey.error("sell.admin.level-range", "max");
    public static final MessageKey ADMIN_LOADING = MessageKey.error("sell.admin.loading");

    public static final MessageKey TOGGLE_CONFIRM = MessageKey.ui("sell.toggle.confirm");
    public static final MessageKey TOGGLE_CONFIRM_DESCRIPTION = MessageKey.ui("sell.toggle.confirm-description");
    public static final MessageKey TOGGLE_ORDERS = MessageKey.ui("sell.toggle.orders");
    public static final MessageKey TOGGLE_ORDERS_DESCRIPTION = MessageKey.ui("sell.toggle.orders-description");

    public static final MessageKey HUB_LABEL = MessageKey.ui("sell.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("sell.hub.description");
    public static final MessageKey HUB_PRICES_LABEL = MessageKey.ui("sell.hub.prices-label");
    public static final MessageKey HUB_PRICES_DESCRIPTION = MessageKey.ui("sell.hub.prices-description");

    private SellMessages() {
    }
}
