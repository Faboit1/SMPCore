package net.siftvanilla.siftcore.feature.orders;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the orders feature ({@code lang/orders.yml}). */
final class OrdersMessages {

    // ------------------------------------------------------------------ item names
    static final MessageKey ITEM_BOOK = MessageKey.ui("orders.item.book", "enchantment");
    static final MessageKey ITEM_BOOK_LEVEL = MessageKey.ui("orders.item.book-level", "enchantment", "level");
    static final MessageKey ITEM_POTION_LONG = MessageKey.ui("orders.item.potion-long", "item");
    static final MessageKey ITEM_POTION_STRONG = MessageKey.ui("orders.item.potion-strong", "item");

    // ------------------------------------------------------------------ the orders browser
    static final MessageKey MENU_TITLE = MessageKey.ui("orders.menu.title");
    static final MessageKey SORT_PRICE = MessageKey.ui("orders.menu.sort-price");
    static final MessageKey SORT_TOTAL = MessageKey.ui("orders.menu.sort-total");
    static final MessageKey SORT_WANTED = MessageKey.ui("orders.menu.sort-wanted");
    static final MessageKey SORT_NEWEST = MessageKey.ui("orders.menu.sort-newest");
    static final MessageKey SORT_ENDING = MessageKey.ui("orders.menu.sort-ending");
    static final MessageKey FILTER_ALL = MessageKey.ui("orders.menu.filter-all");
    static final MessageKey CATEGORY_BLOCKS = MessageKey.ui("orders.category.blocks");
    static final MessageKey CATEGORY_TOOLS = MessageKey.ui("orders.category.tools");
    static final MessageKey CATEGORY_COMBAT = MessageKey.ui("orders.category.combat");
    static final MessageKey CATEGORY_FOOD = MessageKey.ui("orders.category.food");
    static final MessageKey CATEGORY_POTIONS = MessageKey.ui("orders.category.potions");
    static final MessageKey CATEGORY_BOOKS = MessageKey.ui("orders.category.books");
    static final MessageKey CATEGORY_SPAWNERS = MessageKey.ui("orders.category.spawners");
    static final MessageKey CATEGORY_MISC = MessageKey.ui("orders.category.misc");
    static final MessageKey ENTRY_PRICE = MessageKey.ui("orders.menu.entry-price", "price");
    /** What a seller keeps per item, only while deliveries are taxed. */
    static final MessageKey ENTRY_NET = MessageKey.ui("orders.menu.entry-net", "net", "tax");
    static final MessageKey ENTRY_DELIVERED = MessageKey.ui("orders.menu.entry-delivered", "filled", "quantity");
    static final MessageKey ENTRY_OWNER = MessageKey.ui("orders.menu.entry-owner", "owner");
    static final MessageKey ENTRY_ENDS = MessageKey.ui("orders.menu.entry-ends", "time");
    static final MessageKey ENTRY_WORTH = MessageKey.ui("orders.menu.entry-worth", "worth");
    static final MessageKey ENTRY_CARRY = MessageKey.ui("orders.menu.entry-carry", "count");
    static final MessageKey ENTRY_WAITING = MessageKey.ui("orders.menu.entry-waiting", "waiting");
    static final MessageKey ENTRY_HELD = MessageKey.ui("orders.menu.entry-held", "held");
    static final MessageKey ENTRY_STATE = MessageKey.ui("orders.menu.entry-state", "state");
    static final MessageKey ENTRY_UNAVAILABLE = MessageKey.ui("orders.menu.entry-unavailable");
    static final MessageKey HINT_DELIVER = MessageKey.ui("orders.menu.hint-deliver");
    static final MessageKey HINT_QUICK = MessageKey.ui("orders.menu.hint-quick");
    static final MessageKey HINT_OWN = MessageKey.ui("orders.menu.hint-own");
    static final MessageKey HINT_MANAGE = MessageKey.ui("orders.menu.hint-manage");
    static final MessageKey HINT_STAFF = MessageKey.ui("orders.menu.hint-staff");
    static final MessageKey NEW_BUTTON = MessageKey.ui("orders.menu.new");
    static final MessageKey NEW_LORE = MessageKey.ui("orders.menu.new-lore", "count", "limit");
    static final MessageKey NEW_LORE_UNLIMITED = MessageKey.ui("orders.menu.new-lore-unlimited", "count");
    static final MessageKey NEW_LORE_NONE = MessageKey.ui("orders.menu.new-lore-none");
    static final MessageKey YOURS_BUTTON = MessageKey.ui("orders.menu.yours");
    static final MessageKey YOURS_LORE = MessageKey.ui("orders.menu.yours-lore", "waiting", "count");
    static final MessageKey HISTORY_BUTTON = MessageKey.ui("orders.menu.history");
    static final MessageKey HISTORY_LORE = MessageKey.ui("orders.menu.history-lore");

    // ------------------------------------------------------------------ your orders
    static final MessageKey OWN_LIST_TITLE = MessageKey.ui("orders.own-list.title");
    static final MessageKey OWN_LIST_TITLE_OTHER = MessageKey.ui("orders.own-list.title-other", "name");
    static final MessageKey COLLECT_ALL_BUTTON = MessageKey.ui("orders.own-list.collect-all");
    static final MessageKey COLLECT_ALL_LORE = MessageKey.ui("orders.own-list.collect-all-lore", "waiting", "count");
    static final MessageKey COLLECT_ALL_EMPTY = MessageKey.ui("orders.own-list.collect-all-empty");
    static final MessageKey PAST_BUTTON = MessageKey.ui("orders.own-list.past");
    static final MessageKey PAST_LORE = MessageKey.ui("orders.own-list.past-lore");

    // ------------------------------------------------------------------ history
    static final MessageKey PAST_TITLE = MessageKey.ui("orders.past.title");
    static final MessageKey PAST_TITLE_OTHER = MessageKey.ui("orders.past.title-other", "name");
    static final MessageKey PAST_LORE_ENTRY = MessageKey.ui("orders.past.entry", "state", "filled", "quantity", "price", "paid", "ago");
    static final MessageKey PAST_REFUNDED = MessageKey.ui("orders.past.refunded", "refunded");
    static final MessageKey PAST_AGAIN = MessageKey.ui("orders.past.again");
    static final MessageKey DELIVERIES_BUTTON = MessageKey.ui("orders.past.deliveries");
    static final MessageKey DELIVERIES_BUTTON_LORE = MessageKey.ui("orders.past.deliveries-lore");
    static final MessageKey DELIVERIES_TITLE = MessageKey.ui("orders.deliveries.title");
    static final MessageKey DELIVERIES_TITLE_OTHER = MessageKey.ui("orders.deliveries.title-other", "name");
    static final MessageKey DELIVERIES_ENTRY = MessageKey.ui("orders.deliveries.entry", "amount", "earned", "buyer", "ago");
    static final MessageKey DELIVERIES_HEADER = MessageKey.ui("orders.deliveries.header");
    static final MessageKey DELIVERIES_HEADER_LORE = MessageKey.ui("orders.deliveries.header-lore", "total", "count");
    static final MessageKey HISTORY_LOADING = MessageKey.info("orders.history.loading");

    // ------------------------------------------------------------------ states
    static final MessageKey STATE_ACTIVE = MessageKey.ui("orders.state.active");
    static final MessageKey STATE_FILLED = MessageKey.ui("orders.state.filled");
    static final MessageKey STATE_CANCELLED = MessageKey.ui("orders.state.cancelled");
    static final MessageKey STATE_EXPIRED = MessageKey.ui("orders.state.expired");

    // ------------------------------------------------------------------ the delivery menu
    static final MessageKey DELIVER_TITLE = MessageKey.ui("orders.deliver.title", "item");
    static final MessageKey DELIVER_RULE = MessageKey.ui("orders.deliver.rule", "item");
    static final MessageKey DELIVER_RULE_BOOK = MessageKey.ui("orders.deliver.rule-book", "item");
    static final MessageKey DELIVER_BUTTON = MessageKey.ui("orders.deliver.button");
    static final MessageKey DELIVER_BUTTON_LORE = MessageKey.ui("orders.deliver.button-lore", "amount", "remaining", "inner", "payout");
    static final MessageKey DELIVER_BUTTON_LORE_TAXED = MessageKey.ui("orders.deliver.button-lore-taxed", "amount", "remaining", "inner",
        "payout", "tax");
    static final MessageKey DELIVER_BUTTON_EMPTY = MessageKey.ui("orders.deliver.button-empty", "item", "remaining");
    static final MessageKey DELIVER_NOT_ACCEPTED = MessageKey.ui("orders.deliver.not-accepted", "count");
    static final MessageKey DELIVER_FILL = MessageKey.ui("orders.deliver.fill");
    static final MessageKey DELIVER_FILL_LORE = MessageKey.ui("orders.deliver.fill-lore", "item");
    static final MessageKey DELIVER_FILL_NOTHING = MessageKey.error("orders.deliver.fill-nothing", "item");
    static final MessageKey DELIVER_RESTORED = MessageKey.chat("orders.deliver.restored");
    static final MessageKey DELIVER_DONE = MessageKey.chat("orders.deliver.done", "amount", "item", "payout");
    static final MessageKey DELIVER_DONE_TAXED = MessageKey.chat("orders.deliver.done-taxed", "amount", "item", "payout", "tax");
    static final MessageKey DELIVER_NOTHING = MessageKey.error("orders.deliver.nothing", "item");
    static final MessageKey DELIVER_GONE = MessageKey.error("orders.deliver.gone");
    static final MessageKey DELIVER_OWN = MessageKey.error("orders.deliver.own");
    static final MessageKey DELIVER_LESS_LEFT = MessageKey.error("orders.deliver.less-left");
    static final MessageKey DELIVER_CHANGED = MessageKey.error("orders.deliver.changed");
    static final MessageKey DELIVER_UNAVAILABLE = MessageKey.error("orders.deliver.unavailable");
    static final MessageKey DELIVER_RELATED = MessageKey.error("orders.deliver.related");
    static final MessageKey DELIVER_CANCELLED = MessageKey.info("orders.deliver.cancelled");
    static final MessageKey DELIVER_TOO_RICH = MessageKey.error("orders.deliver.too-rich");
    static final MessageKey DELIVER_FAILED = MessageKey.chat("orders.deliver.failed");
    static final MessageKey ITEMS_IN_CLAIM_BOX = MessageKey.chat("orders.items-in-claim-box", "amount");

    // ------------------------------------------------------------------ quick deliver
    static final MessageKey QUICK_TITLE = MessageKey.ui("orders.quick.title", "item");
    static final MessageKey QUICK_CARRY = MessageKey.ui("orders.quick.carry", "count", "item", "inner");
    static final MessageKey QUICK_WANTED = MessageKey.ui("orders.quick.wanted", "remaining");
    /** Quick deliver's tax line, only while deliveries are taxed. */
    static final MessageKey QUICK_TAX = MessageKey.ui("orders.quick.tax", "tax");
    static final MessageKey QUICK_SERVER_MORE = MessageKey.ui("orders.quick.server-more", "price");
    static final MessageKey QUICK_NOTHING = MessageKey.ui("orders.quick.nothing", "item");
    static final MessageKey QUICK_BUTTON = MessageKey.ui("orders.quick.button", "units", "payout");
    static final MessageKey QUICK_BUTTON_TOOLTIP = MessageKey.ui("orders.quick.button-tooltip");
    static final MessageKey QUICK_MENU = MessageKey.ui("orders.quick.menu");
    static final MessageKey QUICK_MENU_TOOLTIP = MessageKey.ui("orders.quick.menu-tooltip");
    static final MessageKey QUICK_CHANGED = MessageKey.ui("orders.quick.changed");

    // ------------------------------------------------------------------ the buyer is told
    static final MessageKey NOTIFY_DELIVERED = MessageKey.notify("orders.notify.delivered", "name", "amount", "item");
    static final MessageKey NOTIFY_SOLD = MessageKey.notify("orders.notify.sold", "name", "amount", "item");
    static final MessageKey NOTIFY_SOLD_MANY = MessageKey.notify("orders.notify.sold-many", "name", "amount");
    static final MessageKey NOTIFY_COMPLETE = MessageKey.notify("orders.notify.complete", "quantity", "item");
    /** One line above the hotbar for a sale that also completed orders (a second line there would replace it at once). */
    static final MessageKey NOTIFY_SOLD_DONE = MessageKey.notify("orders.notify.sold-done", "name", "amount", "item", "done");
    /** Auto-collect: everything that arrived went into the inventory. */
    static final MessageKey NOTIFY_AUTO_COLLECTED = MessageKey.notify("orders.notify.auto-collected", "name", "amount", "item", "done");
    /** Auto-collect: what fit went into the inventory, the rest waits in the order. */
    static final MessageKey NOTIFY_AUTO_COLLECTED_SOME = MessageKey.notify("orders.notify.auto-collected-some", "name", "amount", "item",
        "waiting", "done");
    /** Auto-collect: an order is complete and none of its items waits any more. */
    static final MessageKey NOTIFY_AUTO_COMPLETE = MessageKey.notify("orders.notify.auto-complete", "quantity", "item");
    /** The end of a combined line: one order is complete. */
    static final MessageKey NOTIFY_DONE_ONE = MessageKey.ui("orders.notify.done-one");
    /** The end of a combined line: several orders are complete. */
    static final MessageKey NOTIFY_DONE_MANY = MessageKey.ui("orders.notify.done-many", "count");
    /** The item of a line about several kinds of items. */
    static final MessageKey NOTIFY_ITEMS = MessageKey.ui("orders.notify.items");
    static final MessageKey NOTIFY_EXPIRED = MessageKey.notify("orders.notify.expired", "quantity", "item", "refund");
    static final MessageKey NOTIFY_STAFF_CANCELLED = MessageKey.notify("orders.notify.staff-cancelled", "quantity", "item", "refund", "reason");
    static final MessageKey NOTIFY_ENDING = MessageKey.notify("orders.notify.ending", "quantity", "item", "time");
    static final MessageKey NOTIFY_ENDING_HOVER = MessageKey.ui("orders.notify.ending-hover");

    // ------------------------------------------------------------------ join summary
    static final MessageKey AWAY_HEADER = MessageKey.chat("orders.away.header");
    static final MessageKey AWAY_DELIVERED = MessageKey.ui("orders.away.delivered", "count");
    static final MessageKey AWAY_COMPLETE = MessageKey.ui("orders.away.complete", "count");
    static final MessageKey AWAY_REFUNDED = MessageKey.ui("orders.away.refunded", "refund");
    static final MessageKey AWAY_SEPARATOR = MessageKey.ui("orders.away.separator");
    static final MessageKey AWAY_END = MessageKey.ui("orders.away.end");
    static final MessageKey AWAY_LINE_DELIVERED = MessageKey.ui("orders.away.line-delivered", "amount", "item", "name");
    static final MessageKey AWAY_LINE_COMPLETE = MessageKey.ui("orders.away.line-complete", "quantity", "item");
    static final MessageKey AWAY_LINE_EXPIRED = MessageKey.ui("orders.away.line-expired", "quantity", "item", "refund");
    static final MessageKey AWAY_LINE_CANCELLED = MessageKey.ui("orders.away.line-cancelled", "quantity", "item", "refund", "reason");
    static final MessageKey AWAY_LINE_ENDING = MessageKey.ui("orders.away.line-ending", "quantity", "item");
    static final MessageKey AWAY_MORE = MessageKey.ui("orders.away.more", "count");
    static final MessageKey AWAY_WAITING = MessageKey.ui("orders.away.waiting", "amount");
    static final MessageKey AWAY_OPEN = MessageKey.ui("orders.away.open");
    static final MessageKey AWAY_OPEN_HOVER = MessageKey.ui("orders.away.open-hover");
    static final MessageKey AWAY_UNKNOWN_ITEM = MessageKey.ui("orders.away.unknown-item");

    // ------------------------------------------------------------------ announcements
    static final MessageKey ANNOUNCE = MessageKey.chat("orders.announce", "name", "quantity", "item", "price");
    static final MessageKey ANNOUNCE_HOVER = MessageKey.ui("orders.announce-hover");

    // ------------------------------------------------------------------ placing an order
    static final MessageKey CREATE_TITLE = MessageKey.ui("orders.create.title");
    /** The new-order form's Next tooltip, with how many orders the player has up. */
    static final MessageKey CREATE_NEXT_TOOLTIP = MessageKey.ui("orders.create.next-tooltip", "count", "limit");
    static final MessageKey CREATE_NEXT_TOOLTIP_UNLIMITED = MessageKey.ui("orders.create.next-tooltip-unlimited", "count");
    static final MessageKey CREATE_CHOOSE_TOOLTIP = MessageKey.ui("orders.create.choose-tooltip");
    static final MessageKey CREATE_CHOSEN = MessageKey.ui("orders.create.chosen", "item");
    static final MessageKey CREATE_ITEM = MessageKey.ui("orders.create.item");
    static final MessageKey CREATE_QUANTITY = MessageKey.ui("orders.create.quantity", "max");
    static final MessageKey CREATE_PRICE = MessageKey.ui("orders.create.price");
    static final MessageKey CREATE_NEXT = MessageKey.ui("orders.create.next");
    static final MessageKey CREATE_CHOOSE = MessageKey.ui("orders.create.choose");
    static final MessageKey CONFIRM_TITLE = MessageKey.ui("orders.create.confirm-title");
    static final MessageKey CONFIRM_BODY = MessageKey.ui("orders.create.confirm-body", "quantity", "item", "price", "total");
    static final MessageKey CONFIRM_BUTTON_TOOLTIP = MessageKey.ui("orders.create.confirm-button-tooltip", "time");
    static final MessageKey CONFIRM_WORTH = MessageKey.ui("orders.create.confirm-worth", "worth", "item");
    static final MessageKey CONFIRM_SELL_MORE = MessageKey.ui("orders.create.confirm-sell-more");
    static final MessageKey CONFIRM_BUTTON = MessageKey.ui("orders.create.confirm-button");
    static final MessageKey CREATE_DONE = MessageKey.chat("orders.create.done", "quantity", "item", "total");
    static final MessageKey CREATE_UNKNOWN_ITEM = MessageKey.error("orders.create.unknown-item", "input");
    static final MessageKey CREATE_NO_ITEM = MessageKey.error("orders.create.no-item");
    static final MessageKey CREATE_CHOOSE_VARIANT = MessageKey.error("orders.create.choose-variant", "item");
    static final MessageKey CREATE_BLOCKED = MessageKey.error("orders.create.blocked", "item");
    static final MessageKey CREATE_QUANTITY_RANGE = MessageKey.error("orders.create.quantity-range", "max");
    static final MessageKey CREATE_PRICE_LOW = MessageKey.error("orders.create.price-low", "min");
    static final MessageKey CREATE_PRICE_HIGH = MessageKey.error("orders.create.price-high", "max");
    static final MessageKey CREATE_TOTAL_HIGH = MessageKey.error("orders.create.total-high", "max");
    static final MessageKey CREATE_LIMIT = MessageKey.error("orders.create.limit", "limit");
    static final MessageKey CREATE_NO_ORDERS = MessageKey.error("orders.create.no-orders");
    static final MessageKey CREATE_CANCELLED = MessageKey.info("orders.create.cancelled");
    static final MessageKey CREATE_FAILED = MessageKey.chat("orders.create.failed");

    // ------------------------------------------------------------------ the item picker
    static final MessageKey PICKER_TITLE = MessageKey.ui("orders.picker.title");
    static final MessageKey PICKER_SORT_NAME = MessageKey.ui("orders.picker.sort-name");
    static final MessageKey PICKER_SORT_POPULAR = MessageKey.ui("orders.picker.sort-popular");
    static final MessageKey PICKER_WORTH = MessageKey.ui("orders.picker.worth", "price");
    static final MessageKey PICKER_OPEN = MessageKey.ui("orders.picker.open", "count", "price");
    static final MessageKey PICKER_FAMILY = MessageKey.ui("orders.picker.family");
    static final MessageKey PICKER_HINT = MessageKey.ui("orders.picker.hint");
    static final MessageKey ENCHANT_TITLE = MessageKey.ui("orders.picker.enchant-title");
    /** The tooltip of each button of the variant lists: what an order for it takes. */
    static final MessageKey ENCHANT_TOOLTIP = MessageKey.ui("orders.picker.enchant-tooltip");
    static final MessageKey LEVEL_TITLE = MessageKey.ui("orders.picker.level-title");
    static final MessageKey LEVEL_TOOLTIP = MessageKey.ui("orders.picker.level-tooltip");
    static final MessageKey POTION_TITLE = MessageKey.ui("orders.picker.potion-title");
    static final MessageKey POTION_TOOLTIP = MessageKey.ui("orders.picker.potion-tooltip");
    static final MessageKey SPAWNER_TITLE = MessageKey.ui("orders.picker.spawner-title");
    static final MessageKey SPAWNER_TOOLTIP = MessageKey.ui("orders.picker.spawner-tooltip");

    // ------------------------------------------------------------------ the owner's dialog
    static final MessageKey OWN_TITLE = MessageKey.ui("orders.own.title");
    static final MessageKey OWN_BODY = MessageKey.ui("orders.own.body", "quantity", "item", "price", "filled", "waiting", "held", "time");
    static final MessageKey OWN_BODY_ENDED = MessageKey.ui("orders.own.body-ended", "quantity", "item", "price", "filled", "waiting", "state");
    static final MessageKey OWN_UNAVAILABLE = MessageKey.ui("orders.own.unavailable");
    static final MessageKey OWN_COLLECT = MessageKey.ui("orders.own.collect");
    static final MessageKey OWN_COLLECT_TOOLTIP = MessageKey.ui("orders.own.collect-tooltip");
    static final MessageKey OWN_COLLECT_STACK = MessageKey.ui("orders.own.collect-stack");
    static final MessageKey OWN_COLLECT_STACK_TOOLTIP = MessageKey.ui("orders.own.collect-stack-tooltip");
    static final MessageKey OWN_TO_CLAIM_BOX = MessageKey.ui("orders.own.to-claim-box");
    static final MessageKey OWN_TO_CLAIM_BOX_TOOLTIP = MessageKey.ui("orders.own.to-claim-box-tooltip");
    static final MessageKey OWN_RAISE = MessageKey.ui("orders.own.raise");
    static final MessageKey OWN_RAISE_TOOLTIP = MessageKey.ui("orders.own.raise-tooltip");
    static final MessageKey OWN_ADD = MessageKey.ui("orders.own.add");
    static final MessageKey OWN_ADD_TOOLTIP = MessageKey.ui("orders.own.add-tooltip");
    static final MessageKey OWN_EXTEND = MessageKey.ui("orders.own.extend");
    static final MessageKey OWN_EXTEND_TOOLTIP = MessageKey.ui("orders.own.extend-tooltip", "time");
    static final MessageKey OWN_CANCEL = MessageKey.ui("orders.own.cancel");
    static final MessageKey OWN_CANCEL_TOOLTIP = MessageKey.ui("orders.own.cancel-tooltip");
    static final MessageKey OWN_AGAIN = MessageKey.ui("orders.own.again");
    static final MessageKey OWN_AGAIN_TOOLTIP = MessageKey.ui("orders.own.again-tooltip");
    static final MessageKey OWN_DETAILS = MessageKey.ui("orders.own.details");
    static final MessageKey OWN_DETAILS_TOOLTIP = MessageKey.ui("orders.own.details-tooltip");
    static final MessageKey CANCEL_TITLE = MessageKey.ui("orders.cancel.title");
    static final MessageKey CANCEL_BODY = MessageKey.ui("orders.cancel.body", "quantity", "item", "refund");
    static final MessageKey CANCEL_YES = MessageKey.ui("orders.cancel.yes");
    static final MessageKey CANCEL_YES_TOOLTIP = MessageKey.ui("orders.cancel.yes-tooltip");
    static final MessageKey CANCEL_NO = MessageKey.ui("orders.cancel.no");
    static final MessageKey CANCEL_DONE = MessageKey.chat("orders.cancel.done", "quantity", "item", "refund");
    static final MessageKey CANCEL_GONE = MessageKey.error("orders.cancel.gone");
    static final MessageKey CANCEL_KEPT = MessageKey.info("orders.cancel.kept");
    static final MessageKey COLLECT_DONE = MessageKey.success("orders.collect.done", "amount", "item");
    static final MessageKey COLLECT_DONE_MORE = MessageKey.success("orders.collect.done-more", "amount", "item", "waiting");
    static final MessageKey COLLECT_TO_CLAIM_BOX = MessageKey.chat("orders.collect.to-claim-box", "amount", "item");
    static final MessageKey COLLECT_ALL_DONE = MessageKey.success("orders.collect.all-done", "amount", "count");
    static final MessageKey COLLECT_NOTHING = MessageKey.error("orders.collect.nothing");
    static final MessageKey COLLECT_FAILED = MessageKey.error("orders.collect.failed");
    static final MessageKey COLLECT_UNAVAILABLE = MessageKey.error("orders.collect.unavailable");
    static final MessageKey DETAILS_TITLE = MessageKey.ui("orders.details.title");
    static final MessageKey DETAILS_BODY = MessageKey.ui("orders.details.body", "id", "ago", "paid", "collected");
    static final MessageKey DETAILS_HEADER = MessageKey.ui("orders.details.header", "count");
    static final MessageKey DETAILS_LINE = MessageKey.ui("orders.details.line", "name", "amount", "paid", "ago");
    static final MessageKey DETAILS_NONE = MessageKey.ui("orders.details.none");

    // ------------------------------------------------------------------ changing an order
    static final MessageKey EDIT_TITLE = MessageKey.ui("orders.edit.title");
    static final MessageKey EDIT_BODY = MessageKey.ui("orders.edit.body", "quantity", "item", "price", "filled");
    static final MessageKey EDIT_NEXT_TOOLTIP = MessageKey.ui("orders.edit.next-tooltip");
    static final MessageKey EDIT_PRICE = MessageKey.ui("orders.edit.price");
    static final MessageKey EDIT_ADD = MessageKey.ui("orders.edit.add", "max");
    static final MessageKey EDIT_CONFIRM_BODY = MessageKey.ui("orders.edit.confirm-body", "extra", "quantity", "item", "price");
    static final MessageKey EDIT_CONFIRM_BUTTON = MessageKey.ui("orders.edit.confirm-button");
    static final MessageKey EDIT_LOWER = MessageKey.error("orders.edit.lower");
    static final MessageKey EDIT_NOTHING = MessageKey.error("orders.edit.nothing");
    static final MessageKey EDIT_ADD_RANGE = MessageKey.error("orders.edit.add-range", "max");
    static final MessageKey EDIT_CHANGED = MessageKey.error("orders.edit.changed");
    static final MessageKey EDIT_CANCELLED = MessageKey.info("orders.edit.cancelled");
    static final MessageKey EDIT_DONE = MessageKey.chat("orders.edit.done", "quantity", "item", "price", "extra");
    static final MessageKey EXTEND_DONE = MessageKey.success("orders.extend.done", "time");
    static final MessageKey EXTEND_MAX = MessageKey.error("orders.extend.max");

    // ------------------------------------------------------------------ staff
    static final MessageKey STAFF_TITLE = MessageKey.ui("orders.staff.title", "id");
    static final MessageKey STAFF_BODY = MessageKey.ui("orders.staff.body", "owner", "item", "filled", "quantity", "held", "ago", "time",
        "state");
    static final MessageKey STAFF_CANCEL = MessageKey.ui("orders.staff.cancel");
    static final MessageKey STAFF_CANCEL_TOOLTIP = MessageKey.ui("orders.staff.cancel-tooltip");
    static final MessageKey STAFF_OPEN = MessageKey.ui("orders.staff.open", "name");
    static final MessageKey STAFF_REASON_TITLE = MessageKey.ui("orders.staff.reason-title", "id");
    static final MessageKey STAFF_REASON = MessageKey.ui("orders.staff.reason");
    static final MessageKey STAFF_REASON_MISSING = MessageKey.error("orders.staff.reason-missing");
    static final MessageKey STAFF_CONFIRM_BODY = MessageKey.ui("orders.staff.confirm-body", "id", "owner", "refund", "reason");
    static final MessageKey ADMIN_LIST_HEADER = MessageKey.chat("orders.admin.list-header", "count");
    static final MessageKey ADMIN_LIST_LINE = MessageKey.chat("orders.admin.list-line", "id", "owner", "filled", "quantity", "item", "price", "state");
    static final MessageKey ADMIN_LIST_EMPTY = MessageKey.chat("orders.admin.list-empty");
    static final MessageKey ADMIN_LIST_MORE = MessageKey.chat("orders.admin.list-more", "count");
    static final MessageKey ADMIN_INFO = MessageKey.chat("orders.admin.info", "id", "owner", "item", "filled", "quantity", "collected",
        "price", "held", "state", "time");
    static final MessageKey ADMIN_NOT_FOUND = MessageKey.chat("orders.admin.not-found", "id");
    static final MessageKey ADMIN_CANCELLED = MessageKey.chat("orders.admin.cancelled", "id", "owner", "refund");
    static final MessageKey ADMIN_FAILED = MessageKey.chat("orders.admin.failed", "id", "reason");
    static final MessageKey ADMIN_CHECK = MessageKey.chat("orders.admin.check", "orders", "held", "account");
    static final MessageKey ADMIN_CHECK_FAILED = MessageKey.chat("orders.admin.check-failed", "problem");
    static final MessageKey ADMIN_CHECK_UNAVAILABLE = MessageKey.chat("orders.admin.check-unavailable", "id", "item");
    static final MessageKey ADMIN_HISTORY_HEADER = MessageKey.chat("orders.admin.history-header", "name", "count");
    static final MessageKey ADMIN_HISTORY_LINE = MessageKey.chat("orders.admin.history-line", "id", "state", "filled", "quantity", "item",
        "price", "refunded", "ago");
    static final MessageKey ADMIN_HISTORY_EMPTY = MessageKey.chat("orders.admin.history-empty", "name");
    static final MessageKey ADMIN_EXPIRED = MessageKey.chat("orders.admin.expired", "count");
    static final MessageKey NOT_YOURS = MessageKey.error("orders.not-yours");

    static final MessageKey IN_COMBAT = MessageKey.error("orders.in-combat", "time");

    // ------------------------------------------------------------------ hub and settings
    static final MessageKey HUB_LABEL = MessageKey.ui("orders.hub.label");
    static final MessageKey HUB_DESCRIPTION = MessageKey.ui("orders.hub.description");
    static final MessageKey SETTING_NOTIFICATIONS = MessageKey.ui("orders.settings.notifications");
    static final MessageKey SETTING_NOTIFICATIONS_DESCRIPTION = MessageKey.ui("orders.settings.notifications-description");
    static final MessageKey SETTING_ANNOUNCE = MessageKey.ui("orders.settings.announce");
    static final MessageKey SETTING_ANNOUNCE_DESCRIPTION = MessageKey.ui("orders.settings.announce-description");
    static final MessageKey SETTING_NOTIFICATIONS_COMPLETE = MessageKey.ui("orders.settings.notifications-complete");
    static final MessageKey SETTING_ENDING_ALERTS = MessageKey.ui("orders.settings.ending-alerts");
    static final MessageKey SETTING_ENDING_ALERTS_DESCRIPTION = MessageKey.ui("orders.settings.ending-alerts-description");
    static final MessageKey SETTING_JOIN_SUMMARY = MessageKey.ui("orders.settings.join-summary");
    static final MessageKey SETTING_JOIN_SUMMARY_DESCRIPTION = MessageKey.ui("orders.settings.join-summary-description");
    static final MessageKey SETTING_AUTO_COLLECT = MessageKey.ui("orders.settings.auto-collect");
    static final MessageKey SETTING_AUTO_COLLECT_DESCRIPTION = MessageKey.ui("orders.settings.auto-collect-description");
    static final MessageKey SETTING_ANNOUNCE_MINE = MessageKey.ui("orders.settings.announce-mine");
    static final MessageKey SETTING_ANNOUNCE_MINE_DESCRIPTION = MessageKey.ui("orders.settings.announce-mine-description");

    private OrdersMessages() {
    }
}
