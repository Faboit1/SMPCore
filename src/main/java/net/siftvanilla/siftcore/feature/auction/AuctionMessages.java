package net.siftvanilla.siftcore.feature.auction;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the auction house ({@code lang/auction.yml}). */
public final class AuctionMessages {

    // ---------------------------------------------------------------- hub and menus
    public static final MessageKey HUB_LABEL = MessageKey.ui("auction.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("auction.hub.description");
    public static final MessageKey HUB_CLAIMS_LABEL = MessageKey.ui("auction.hub.claims-label");
    public static final MessageKey HUB_CLAIMS_DESCRIPTION = MessageKey.ui("auction.hub.claims-description");

    public static final MessageKey MENU_TITLE = MessageKey.ui("auction.menu.title");
    public static final MessageKey LISTING_LORE = MessageKey.ui("auction.menu.listing", "price", "seller", "time");
    public static final MessageKey LISTING_BUY = MessageKey.ui("auction.menu.buy-hint");
    public static final MessageKey LISTING_OWN = MessageKey.ui("auction.menu.own-hint");
    public static final MessageKey LISTING_STAFF = MessageKey.ui("auction.menu.staff-hint", "id");
    public static final MessageKey MINE_BUTTON = MessageKey.ui("auction.menu.mine");
    public static final MessageKey MINE_BUTTON_LORE = MessageKey.ui("auction.menu.mine-lore", "count", "limit");
    public static final MessageKey MINE_BUTTON_LORE_UNLIMITED = MessageKey.ui("auction.menu.mine-lore-unlimited", "count");
    public static final MessageKey CLAIMS_BUTTON = MessageKey.ui("auction.menu.claims");
    public static final MessageKey CLAIMS_BUTTON_LORE = MessageKey.ui("auction.menu.claims-lore", "count");
    public static final MessageKey SELL_BUTTON = MessageKey.ui("auction.menu.sell");
    public static final MessageKey SELL_BUTTON_LORE = MessageKey.ui("auction.menu.sell-lore");

    public static final MessageKey SORT_NEWEST = MessageKey.ui("auction.sort.newest");
    public static final MessageKey SORT_ENDING_SOON = MessageKey.ui("auction.sort.ending-soon");
    public static final MessageKey SORT_LOWEST_PRICE = MessageKey.ui("auction.sort.lowest-price");
    public static final MessageKey SORT_HIGHEST_PRICE = MessageKey.ui("auction.sort.highest-price");

    public static final MessageKey FILTER_ALL = MessageKey.ui("auction.category.all");
    public static final MessageKey CATEGORY_BLOCKS = MessageKey.ui("auction.category.blocks");
    public static final MessageKey CATEGORY_TOOLS = MessageKey.ui("auction.category.tools");
    public static final MessageKey CATEGORY_COMBAT = MessageKey.ui("auction.category.combat");
    public static final MessageKey CATEGORY_FOOD = MessageKey.ui("auction.category.food");
    public static final MessageKey CATEGORY_POTIONS = MessageKey.ui("auction.category.potions");
    public static final MessageKey CATEGORY_BOOKS = MessageKey.ui("auction.category.books");
    public static final MessageKey CATEGORY_SPAWNERS = MessageKey.ui("auction.category.spawners");
    public static final MessageKey CATEGORY_MISC = MessageKey.ui("auction.category.misc");

    public static final MessageKey MINE_TITLE = MessageKey.ui("auction.mine.title");
    public static final MessageKey MINE_LORE = MessageKey.ui("auction.mine.lore", "price", "time");
    public static final MessageKey MINE_HINT = MessageKey.ui("auction.mine.hint");
    public static final MessageKey MINE_PENDING = MessageKey.ui("auction.mine.pending");
    public static final MessageKey HISTORY_BUTTON = MessageKey.ui("auction.mine.history");
    public static final MessageKey HISTORY_BUTTON_LORE = MessageKey.ui("auction.mine.history-lore");

    public static final MessageKey CLAIMS_TITLE = MessageKey.ui("auction.claims.title");
    public static final MessageKey CLAIMS_LORE = MessageKey.ui("auction.claims.lore", "source", "time");
    public static final MessageKey CLAIMS_SOURCE_AUCTION = MessageKey.ui("auction.claims.source-auction");
    public static final MessageKey CLAIMS_SOURCE_OTHER = MessageKey.ui("auction.claims.source-other", "source");
    public static final MessageKey CLAIMS_SOURCE_OVERFLOW = MessageKey.ui("auction.claims.source-overflow");
    public static final MessageKey CLAIM_ALL = MessageKey.ui("auction.claims.claim-all");
    public static final MessageKey CLAIM_ALL_LORE = MessageKey.ui("auction.claims.claim-all-lore", "count");

    // ---------------------------------------------------------------- selling
    public static final MessageKey SELL_FORM_TITLE = MessageKey.ui("auction.sell.form-title");
    public static final MessageKey SELL_FORM_PRICE = MessageKey.ui("auction.sell.form-price");
    public static final MessageKey SELL_FORM_AMOUNT = MessageKey.ui("auction.sell.form-amount");
    public static final MessageKey SELL_FORM_BUTTON = MessageKey.ui("auction.sell.form-button");
    public static final MessageKey SELL_FORM_BUTTON_TOOLTIP = MessageKey.ui("auction.sell.form-button-tooltip");
    public static final MessageKey SELL_CONFIRM_TITLE = MessageKey.ui("auction.sell.confirm-title");
    public static final MessageKey SELL_CONFIRM_BODY = MessageKey.ui("auction.sell.confirm-body", "amount", "item", "price");
    /** The tax line of the listing confirmation, only while the auction house takes a tax. */
    public static final MessageKey SELL_CONFIRM_TAX = MessageKey.ui("auction.sell.confirm-tax", "tax", "rate", "earn");
    public static final MessageKey SELL_CONFIRM_TOOLTIP = MessageKey.ui("auction.sell.confirm-button-tooltip", "time");
    public static final MessageKey SELL_CONFIRM_SLOTS = MessageKey.ui("auction.sell.confirm-slots", "used", "limit");
    public static final MessageKey SELL_WARNING_SELL = MessageKey.ui("auction.sell.warning-sell", "worth");
    public static final MessageKey SELL_WARNING_MARKET = MessageKey.ui("auction.sell.warning-market", "price", "yours");
    public static final MessageKey SELL_CONFIRM_BUTTON = MessageKey.ui("auction.sell.confirm-button");
    public static final MessageKey SELL_LISTED = MessageKey.success("auction.sell.listed", "amount", "item", "price");
    public static final MessageKey SELL_NOTHING = MessageKey.error("auction.sell.nothing-in-hand");
    public static final MessageKey SELL_BLACKLISTED = MessageKey.error("auction.sell.blacklisted");
    public static final MessageKey SELL_FILLED_CONTAINER = MessageKey.error("auction.sell.filled-container");
    public static final MessageKey SELL_TOO_LARGE = MessageKey.error("auction.sell.too-large");
    public static final MessageKey SELL_CREATIVE = MessageKey.error("auction.sell.creative");
    public static final MessageKey SELL_PRICE_RANGE = MessageKey.error("auction.sell.price-range", "amount", "item", "min", "max");
    public static final MessageKey SELL_AMOUNT_RANGE = MessageKey.error("auction.sell.amount-range", "max");
    public static final MessageKey SELL_SLOTS_FULL = MessageKey.error("auction.sell.slots-full", "limit");
    public static final MessageKey SELL_NO_SLOTS = MessageKey.error("auction.sell.no-slots");
    public static final MessageKey SELL_ITEM_CHANGED = MessageKey.error("auction.sell.item-changed");
    public static final MessageKey SELL_CANCELLED = MessageKey.info("auction.sell.cancelled");
    public static final MessageKey SELL_FAILED = MessageKey.error("auction.sell.failed");

    // ---------------------------------------------------------------- buying
    public static final MessageKey BUY_TITLE = MessageKey.ui("auction.buy.confirm-title");
    public static final MessageKey BUY_BODY = MessageKey.ui("auction.buy.confirm-body", "amount", "item", "price", "seller",
        "balance");
    public static final MessageKey BUY_BUTTON = MessageKey.ui("auction.buy.confirm-button");
    public static final MessageKey BUY_BUTTON_TOOLTIP = MessageKey.ui("auction.buy.confirm-button-tooltip", "time");
    public static final MessageKey BUY_DONE = MessageKey.chat("auction.buy.done", "amount", "item", "seller", "price");
    public static final MessageKey BUY_DONE_CLAIM_BOX = MessageKey.chat("auction.buy.done-claim-box", "amount", "item", "seller", "price");
    public static final MessageKey BUY_GONE = MessageKey.error("auction.buy.gone");
    public static final MessageKey BUY_OWN = MessageKey.error("auction.buy.own");
    public static final MessageKey BUY_PRICE_CHANGED = MessageKey.error("auction.buy.price-changed");
    public static final MessageKey BUY_EXPIRED = MessageKey.error("auction.buy.expired");
    public static final MessageKey BUY_PENDING = MessageKey.error("auction.buy.pending");
    public static final MessageKey BUY_SELLER_FULL = MessageKey.error("auction.buy.seller-full");
    public static final MessageKey BUY_CANCELLED = MessageKey.info("auction.buy.cancelled");
    public static final MessageKey SOLD = MessageKey.notify("auction.sold", "buyer", "amount", "item", "price");
    /** {@link #SOLD} for a sale that was taxed: what the seller got after the tax. */
    public static final MessageKey SOLD_TAXED = MessageKey.notify("auction.sold-taxed", "buyer", "amount", "item", "price", "earned");

    // ---------------------------------------------------------------- taking listings down
    public static final MessageKey CANCEL_TITLE = MessageKey.ui("auction.cancel.confirm-title");
    public static final MessageKey CANCEL_BODY = MessageKey.ui("auction.cancel.confirm-body", "amount", "item", "price");
    public static final MessageKey CANCEL_BUTTON = MessageKey.ui("auction.cancel.confirm-button");
    public static final MessageKey CANCEL_BUTTON_TOOLTIP = MessageKey.ui("auction.cancel.confirm-button-tooltip");
    public static final MessageKey CANCEL_KEEP = MessageKey.ui("auction.cancel.keep-button");
    public static final MessageKey CANCEL_DONE = MessageKey.success("auction.cancel.done");
    public static final MessageKey CANCEL_DONE_CLAIM_BOX = MessageKey.success("auction.cancel.done-claim-box");
    public static final MessageKey REMOVE_TITLE = MessageKey.ui("auction.remove.confirm-title");
    public static final MessageKey REMOVE_BODY = MessageKey.ui("auction.remove.confirm-body", "seller", "amount", "item", "price");
    public static final MessageKey REMOVE_BUTTON = MessageKey.ui("auction.remove.confirm-button");
    public static final MessageKey REMOVE_BUTTON_TOOLTIP = MessageKey.ui("auction.remove.confirm-button-tooltip");
    public static final MessageKey EXPIRED_ONE = MessageKey.chat("auction.expired.one", "amount", "item");
    public static final MessageKey EXPIRED_MANY = MessageKey.chat("auction.expired.many", "count");

    // ---------------------------------------------------------------- claim box
    public static final MessageKey CLAIMED_ONE = MessageKey.success("auction.claim.one", "amount", "item");
    public static final MessageKey CLAIMED_MANY = MessageKey.success("auction.claim.many", "count");
    public static final MessageKey CLAIM_PARTIAL = MessageKey.info("auction.claim.partial", "count");
    public static final MessageKey CLAIM_EMPTY = MessageKey.info("auction.claim.empty");
    public static final MessageKey CLAIM_REMINDER = MessageKey.chat("auction.claim.reminder", "count");

    // ---------------------------------------------------------------- misc
    public static final MessageKey IN_COMBAT = MessageKey.error("auction.in-combat", "time");
    public static final MessageKey HISTORY_TITLE = MessageKey.ui("auction.history.title");
    public static final MessageKey HISTORY_HEADER = MessageKey.ui("auction.history.header", "count");
    public static final MessageKey HISTORY_SOLD = MessageKey.ui("auction.history.sold", "amount", "item", "name", "price", "ago");
    public static final MessageKey HISTORY_BOUGHT = MessageKey.ui("auction.history.bought", "amount", "item", "name", "price", "ago");
    public static final MessageKey HISTORY_EMPTY = MessageKey.ui("auction.history.empty");
    public static final MessageKey SETTING_SALES = MessageKey.ui("auction.settings.sales");
    public static final MessageKey SETTING_SALES_DESCRIPTION = MessageKey.ui("auction.settings.sales-description");
    public static final MessageKey SETTING_JOIN_SUMMARY = MessageKey.ui("auction.settings.join-summary");
    public static final MessageKey SETTING_JOIN_SUMMARY_DESCRIPTION = MessageKey.ui("auction.settings.join-summary-description");
    public static final MessageKey SETTING_PRICE_WARNING = MessageKey.ui("auction.settings.price-warning");
    public static final MessageKey SETTING_PRICE_WARNING_DESCRIPTION = MessageKey.ui("auction.settings.price-warning-description");
    public static final MessageKey SETTING_EXPIRY_ALERTS = MessageKey.ui("auction.settings.expiry-alerts");
    public static final MessageKey SETTING_EXPIRY_ALERTS_DESCRIPTION = MessageKey.ui("auction.settings.expiry-alerts-description");
    public static final MessageKey SETTING_HIDE_OWN = MessageKey.ui("auction.settings.hide-own");
    public static final MessageKey SETTING_HIDE_OWN_DESCRIPTION = MessageKey.ui("auction.settings.hide-own-description");

    // ---------------------------------------------------------------- join summary
    public static final MessageKey AWAY_SOLD_ONE = MessageKey.chat("auction.away.sold-one", "name", "amount", "item", "price");
    public static final MessageKey AWAY_SOLD_ONE_TAXED = MessageKey.chat("auction.away.sold-one-taxed", "name", "amount", "item", "price",
        "earned");
    public static final MessageKey AWAY_SOLD_MANY = MessageKey.chat("auction.away.sold-many", "count", "earned");
    public static final MessageKey AWAY_SOLD_MANY_TAXED = MessageKey.chat("auction.away.sold-many-taxed", "count", "earned");
    public static final MessageKey AWAY_SOLD_LINE = MessageKey.ui("auction.away.sold-line", "amount", "item", "name", "price");
    public static final MessageKey AWAY_SOLD_MORE = MessageKey.ui("auction.away.sold-more", "count");

    // ---------------------------------------------------------------- staff
    public static final MessageKey ADMIN_INFO = MessageKey.chat("auction.admin.info", "active", "sellers", "pending",
        "unreadable", "claims", "next");
    public static final MessageKey ADMIN_NEXT_EXPIRY = MessageKey.ui("auction.admin.next-expiry", "time");
    public static final MessageKey ADMIN_NO_EXPIRY = MessageKey.ui("auction.admin.no-expiry");
    public static final MessageKey ADMIN_LIST_HEADER = MessageKey.chat("auction.admin.list-header", "name", "count");
    public static final MessageKey ADMIN_LIST_LINE = MessageKey.chat("auction.admin.list-line", "id", "amount", "item", "price", "time");
    public static final MessageKey ADMIN_LIST_EMPTY = MessageKey.chat("auction.admin.list-empty", "name");
    public static final MessageKey ADMIN_REMOVED = MessageKey.chat("auction.admin.removed", "id", "name");
    public static final MessageKey ADMIN_NOT_FOUND = MessageKey.chat("auction.admin.not-found", "id");
    public static final MessageKey ADMIN_FAILED = MessageKey.chat("auction.admin.failed", "id", "reason");
    public static final MessageKey ADMIN_EXPIRED = MessageKey.chat("auction.admin.expired", "count");

    private AuctionMessages() {
    }
}
