package net.siftvanilla.siftcore.feature.sell;

import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the sell feature ({@code lang/sell.yml}). */
public final class SellMessages {

    /** The receipt, kept in chat, with the success sound. */
    public static final MessageKey SOLD = MessageKey.chat("sell.sold", "items", "total").withFeedback(Feedback.SUCCESS);
    public static final MessageKey SOLD_BONUS = MessageKey.chat("sell.sold-bonus", "items", "total", "multiplier")
        .withFeedback(Feedback.SUCCESS);
    public static final MessageKey ITEMS_ONE = MessageKey.ui("sell.items.one", "amount", "item");
    public static final MessageKey ITEMS_MANY = MessageKey.ui("sell.items.many", "amount");
    public static final MessageKey RECEIPT_LINE = MessageKey.ui("sell.receipt.line", "amount", "item", "value");
    public static final MessageKey RECEIPT_MORE = MessageKey.ui("sell.receipt.more", "count");
    public static final MessageKey RECEIPT_BONUS = MessageKey.ui("sell.receipt.bonus", "multiplier");

    public static final MessageKey EMPTY_HAND = MessageKey.error("sell.empty-hand");
    public static final MessageKey NOT_SELLABLE = MessageKey.error("sell.not-sellable", "item");
    public static final MessageKey MODIFIED = MessageKey.error("sell.modified");
    public static final MessageKey NOTHING_TO_SELL = MessageKey.error("sell.nothing-to-sell");
    public static final MessageKey NOTHING_IN_MENU = MessageKey.error("sell.nothing-in-menu");
    public static final MessageKey CANCELLED = MessageKey.info("sell.cancelled");
    public static final MessageKey FAILED = MessageKey.error("sell.failed");
    public static final MessageKey BALANCE_FULL = MessageKey.error("sell.balance-full");
    public static final MessageKey TOO_MUCH = MessageKey.error("sell.too-much");
    public static final MessageKey CLAIM_BOX = MessageKey.chat("sell.claim-box", "count");

    public static final MessageKey MENU_TITLE = MessageKey.ui("sell.menu.title");
    public static final MessageKey MENU_TOTAL = MessageKey.ui("sell.menu.total", "total");
    public static final MessageKey MENU_TOTAL_COUNT = MessageKey.ui("sell.menu.total-count", "count");
    public static final MessageKey MENU_TOTAL_BONUS = MessageKey.ui("sell.menu.total-bonus", "multiplier");
    public static final MessageKey MENU_TOTAL_UNSELLABLE = MessageKey.ui("sell.menu.total-unsellable", "count");
    public static final MessageKey MENU_TOTAL_EMPTY = MessageKey.ui("sell.menu.total-empty");
    public static final MessageKey MENU_TOTAL_HINT = MessageKey.ui("sell.menu.total-hint");
    public static final MessageKey MENU_SELL = MessageKey.ui("sell.menu.sell");
    public static final MessageKey MENU_SELL_LORE = MessageKey.ui("sell.menu.sell-lore", "total");
    public static final MessageKey MENU_SELL_EMPTY = MessageKey.ui("sell.menu.sell-empty");

    public static final MessageKey WORTH_EACH = MessageKey.chat("sell.worth.each", "item", "price");
    public static final MessageKey WORTH_STACK = MessageKey.chat("sell.worth.stack", "item", "price", "amount", "total");
    public static final MessageKey WORTH_BONUS = MessageKey.chat("sell.worth.bonus", "multiplier", "total");
    public static final MessageKey WORTH_NONE = MessageKey.chat("sell.worth.none", "item");
    public static final MessageKey WORTH_BELOW_ONE = MessageKey.chat("sell.worth.below-one", "item");
    public static final MessageKey WORTH_MODIFIED = MessageKey.chat("sell.worth.modified", "item", "price");
    public static final MessageKey WORTH_HOLD = MessageKey.error("sell.worth.hold");
    public static final MessageKey WORTH_SOURCE_BASE = MessageKey.chat("sell.worth.source-base");
    public static final MessageKey WORTH_SOURCE_DERIVED = MessageKey.chat("sell.worth.source-derived", "recipe");
    public static final MessageKey WORTH_SOURCE_OVERRIDE = MessageKey.chat("sell.worth.source-override");

    public static final MessageKey HUB_LABEL = MessageKey.ui("sell.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("sell.hub.description");

    private SellMessages() {
    }
}
