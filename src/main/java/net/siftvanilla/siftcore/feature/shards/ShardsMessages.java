package net.siftvanilla.siftcore.feature.shards;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the shards feature ({@code lang/shards.yml}). */
public final class ShardsMessages {

    public static final MessageKey BALANCE_SELF = MessageKey.chat("shards.balance.self", "shards");
    public static final MessageKey BALANCE_OTHER = MessageKey.chat("shards.balance.other", "name", "shards");
    public static final MessageKey BALANCE_EARN = MessageKey.chat("shards.balance.earn");

    public static final MessageKey ADMIN_GIVEN = MessageKey.chat("shards.admin.given", "name", "amount", "balance");
    public static final MessageKey ADMIN_TAKEN = MessageKey.chat("shards.admin.taken", "name", "amount", "balance");
    public static final MessageKey ADMIN_SET = MessageKey.chat("shards.admin.set", "name", "amount");
    public static final MessageKey ADMIN_NOT_ENOUGH = MessageKey.chat("shards.admin.not-enough", "name", "balance");
    public static final MessageKey ADMIN_FAILED = MessageKey.chat("shards.admin.failed", "reason");
    public static final MessageKey ADMIN_PENDING_HEADER = MessageKey.chat("shards.admin.pending-header", "count");
    public static final MessageKey ADMIN_PENDING_LINE = MessageKey.chat("shards.admin.pending-line", "name", "keys", "crate", "cost", "ago");
    public static final MessageKey ADMIN_PENDING_RETRY = MessageKey.chat("shards.admin.pending-retry");
    public static final MessageKey RECEIVED = MessageKey.notify("shards.received", "amount", "balance");

    public static final MessageKey SHOP_TITLE = MessageKey.ui("shards.shop.title");
    public static final MessageKey SHOP_BODY = MessageKey.ui("shards.shop.body", "shards");
    public static final MessageKey SHOP_EMPTY = MessageKey.ui("shards.shop.empty");
    public static final MessageKey SHOP_OFFER = MessageKey.ui("shards.shop.offer", "name", "price");
    public static final MessageKey SHOP_OFFER_MANY = MessageKey.ui("shards.shop.offer-many", "amount", "name", "price");
    public static final MessageKey SHOP_OFFER_TOOLTIP = MessageKey.ui("shards.shop.offer-tooltip", "description", "max");
    public static final MessageKey KEY_NAME = MessageKey.ui("shards.shop.key-name", "crate");

    public static final MessageKey BUY_TITLE = MessageKey.ui("shards.buy.title", "name");
    public static final MessageKey BUY_BODY = MessageKey.ui("shards.buy.body", "price", "shards");
    public static final MessageKey BUY_MAX = MessageKey.ui("shards.buy.max", "max");
    public static final MessageKey BUY_KEYS = MessageKey.ui("shards.buy.keys", "keys");
    public static final MessageKey BUY_ITEMS = MessageKey.ui("shards.buy.items", "items");
    public static final MessageKey BUY_OWNED = MessageKey.ui("shards.buy.owned", "keys");
    public static final MessageKey BUY_DESCRIPTION = MessageKey.ui("shards.buy.description", "description");
    public static final MessageKey BUY_AMOUNT = MessageKey.ui("shards.buy.amount");
    public static final MessageKey BUY_BUTTON = MessageKey.ui("shards.buy.button", "amount", "total");
    public static final MessageKey BUY_CHANGED = MessageKey.ui("shards.buy.changed");
    public static final MessageKey BUY_PRICE_CHANGED = MessageKey.ui("shards.buy.price-changed", "price");
    public static final MessageKey BUY_NOT_ENOUGH = MessageKey.ui("shards.buy.not-enough", "total", "shards");
    public static final MessageKey BUY_TOO_EXPENSIVE = MessageKey.ui("shards.buy.too-expensive");

    public static final MessageKey CONFIRM_TITLE = MessageKey.ui("shards.confirm.title");
    public static final MessageKey CONFIRM_BODY = MessageKey.ui("shards.confirm.body", "count", "name", "total", "left");
    public static final MessageKey CONFIRM_BUTTON = MessageKey.ui("shards.confirm.button");

    public static final MessageKey BOUGHT = MessageKey.chat("shards.bought", "count", "name", "total");
    public static final MessageKey BOUGHT_KEYS = MessageKey.chat("shards.bought-keys", "count", "name", "total");
    public static final MessageKey BOUGHT_CLAIM_BOX = MessageKey.chat("shards.bought-claim-box", "count", "name", "total", "left");
    public static final MessageKey REFUNDED = MessageKey.notify("shards.refunded", "count", "name", "total");
    public static final MessageKey GIVING = MessageKey.info("shards.giving");
    public static final MessageKey NO_LONGER_SOLD = MessageKey.error("shards.no-longer-sold");
    public static final MessageKey UNAVAILABLE = MessageKey.error("shards.unavailable");
    public static final MessageKey CANCELLED = MessageKey.info("shards.cancelled");
    public static final MessageKey FAILED = MessageKey.error("shards.failed");

    public static final MessageKey HUB_LABEL = MessageKey.ui("shards.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("shards.hub.description");
    public static final MessageKey HUB_TITLE = MessageKey.ui("shards.hub.title");
    public static final MessageKey HUB_BALANCE = MessageKey.ui("shards.hub.balance", "shards");
    public static final MessageKey HUB_ZONE_RATE = MessageKey.ui("shards.hub.zone-rate", "shards", "time");
    public static final MessageKey HUB_ZONE_TODAY = MessageKey.ui("shards.hub.zone-today", "today");
    public static final MessageKey HUB_ZONE_TODAY_CAP = MessageKey.ui("shards.hub.zone-today-cap", "today", "cap");
    public static final MessageKey HUB_ZONE_INSIDE = MessageKey.ui("shards.hub.zone-inside");
    public static final MessageKey HUB_ZONE_CLOSED = MessageKey.ui("shards.hub.zone-closed");
    public static final MessageKey HUB_SPEND = MessageKey.ui("shards.hub.spend");
    public static final MessageKey HUB_SHOP = MessageKey.ui("shards.hub.shop");
    public static final MessageKey HUB_SHOP_TOOLTIP = MessageKey.ui("shards.hub.shop-tooltip");
    public static final MessageKey HUB_ZONE = MessageKey.ui("shards.hub.zone");
    public static final MessageKey HUB_ZONE_TOOLTIP = MessageKey.ui("shards.hub.zone-tooltip");

    private ShardsMessages() {
    }
}
