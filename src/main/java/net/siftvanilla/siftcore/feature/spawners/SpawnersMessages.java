package net.siftvanilla.siftcore.feature.spawners;

import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Text of the spawners feature ({@code lang/spawners.yml}). Placeholder conventions: {@code <name>} is a mob's
 * display name as configured ("Cave spider", for names and titles), {@code <mob>} the same in lower case for use
 * inside sentences ("cave spider"), {@code <item>} an item name, {@code <owner>} a player name.
 */
public final class SpawnersMessages {

    // The spawner item
    public static final MessageKey ITEM_NAME = MessageKey.ui("spawners.item.name", "name");
    public static final MessageKey ITEM_LORE = MessageKey.ui("spawners.item.lore");

    // Placing
    public static final MessageKey PLACED = MessageKey.success("spawners.place.placed", "mob");
    public static final MessageKey PLACE_DISABLED = MessageKey.error("spawners.place.disabled", "mob");
    public static final MessageKey PLACE_WORLD = MessageKey.error("spawners.place.world");
    public static final MessageKey PLACE_CHUNK_FULL = MessageKey.error("spawners.place.chunk-full", "max");
    public static final MessageKey PLACE_CANCELLED = MessageKey.info("spawners.place.cancelled");

    // Stacking
    public static final MessageKey STACKED = MessageKey.success("spawners.stack.stacked", "amount", "mob", "stack", "cap");
    public static final MessageKey STACKED_OTHER = MessageKey.success("spawners.stack.stacked-other", "amount", "owner", "mob", "stack", "cap");
    public static final MessageKey STACK_FULL = MessageKey.error("spawners.stack.full", "cap");
    public static final MessageKey STACK_WRONG_MOB = MessageKey.error("spawners.stack.wrong-mob", "mob");
    public static final MessageKey STACK_DISABLED = MessageKey.error("spawners.stack.disabled", "mob");
    public static final MessageKey STACK_CANCELLED = MessageKey.info("spawners.stack.cancelled");
    public static final MessageKey SPAWN_EGG = MessageKey.error("spawners.spawn-egg");

    // Access
    public static final MessageKey NOT_YOURS = MessageKey.error("spawners.not-yours", "owner");
    public static final MessageKey GONE = MessageKey.error("spawners.gone");
    public static final MessageKey CHANGED = MessageKey.error("spawners.changed");
    public static final MessageKey IN_COMBAT = MessageKey.error("spawners.in-combat", "time");

    // Picking up
    public static final MessageKey NEED_SILK_TOUCH = MessageKey.error("spawners.break.silk-touch");
    public static final MessageKey TOO_MUCH_STORED = MessageKey.error("spawners.break.too-much", "stacks", "max");
    public static final MessageKey BREAK_CANCELLED = MessageKey.info("spawners.break.cancelled");
    public static final MessageKey PICKED_UP_ONE = MessageKey.success("spawners.break.picked-up-one", "mob");
    public static final MessageKey PICKED_UP_MANY = MessageKey.success("spawners.break.picked-up-many", "amount", "mob");
    public static final MessageKey STORAGE_TO_YOUR_CLAIM_BOX = MessageKey.chat("spawners.break.storage-claim-box", "count");
    public static final MessageKey STORAGE_TO_OWNER_CLAIM_BOX = MessageKey.chat("spawners.break.storage-owner-claim-box", "count", "owner");
    public static final MessageKey STORAGE_SOLD = MessageKey.chat("spawners.break.storage-sold", "count", "total", "booster");
    public static final MessageKey STORAGE_SOLD_FOR_OWNER = MessageKey.chat("spawners.break.storage-sold-owner", "count", "total", "owner",
        "booster");
    public static final MessageKey OWNER_PICKED_UP = MessageKey.notify("spawners.break.owner-notice", "name", "mob", "amount");
    public static final MessageKey OWNER_PICKED_UP_STAFF = MessageKey.notify("spawners.break.owner-notice-staff", "mob", "amount");
    public static final MessageKey NATURAL_PICKED_UP = MessageKey.success("spawners.break.natural", "mob");
    public static final MessageKey CLAIM_BOX = MessageKey.chat("spawners.claim-box", "count");
    public static final MessageKey REFUNDED = MessageKey.notify("spawners.refund.notice", "mob", "amount", "location", "world", "count");
    public static final MessageKey REFUNDED_XP = MessageKey.chat("spawners.refund.xp", "xp");

    // Storage menu
    public static final MessageKey MENU_TITLE = MessageKey.ui("spawners.menu.title", "name");
    public static final MessageKey MENU_ITEM_NAME = MessageKey.ui("spawners.menu.item.name", "item");
    public static final MessageKey MENU_ITEM_LORE = MessageKey.ui("spawners.menu.item.lore", "amount", "price", "value");
    public static final MessageKey MENU_ITEM_LORE_UNSELLABLE = MessageKey.ui("spawners.menu.item.lore-unsellable", "amount");
    public static final MessageKey SORT_AMOUNT = MessageKey.ui("spawners.menu.sort.amount");
    public static final MessageKey SORT_VALUE = MessageKey.ui("spawners.menu.sort.value");
    public static final MessageKey SORT_NAME = MessageKey.ui("spawners.menu.sort.name");
    public static final MessageKey MENU_SELL = MessageKey.ui("spawners.menu.sell.name");
    public static final MessageKey MENU_SELL_LORE = MessageKey.ui("spawners.menu.sell.lore", "count", "total");
    public static final MessageKey MENU_SELL_LORE_BONUS = MessageKey.ui("spawners.menu.sell.lore-bonus", "count", "total", "multiplier");
    public static final MessageKey MENU_SELL_EMPTY = MessageKey.ui("spawners.menu.sell.empty");
    public static final MessageKey MENU_SELL_BOOSTER = MessageKey.ui("spawners.menu.sell.booster", "percent");
    public static final MessageKey MENU_XP = MessageKey.ui("spawners.menu.xp.name");
    public static final MessageKey MENU_XP_LORE = MessageKey.ui("spawners.menu.xp.lore", "xp", "cap");
    public static final MessageKey MENU_XP_EMPTY = MessageKey.ui("spawners.menu.xp.empty", "cap");
    public static final MessageKey MENU_INFO = MessageKey.ui("spawners.menu.info.name", "name");
    public static final MessageKey MENU_INFO_LORE = MessageKey.ui("spawners.menu.info.lore", "stack", "cap", "owner", "used",
        "capacity", "xp", "xp-cap", "location", "world", "interval", "radius");
    public static final MessageKey MENU_INFO_FULL = MessageKey.ui("spawners.menu.info.full");

    // Taking, selling, XP
    public static final MessageKey TOOK = MessageKey.success("spawners.took", "amount", "item");
    public static final MessageKey NONE_LEFT = MessageKey.error("spawners.none-left");
    public static final MessageKey SOLD = MessageKey.chat("spawners.sell.sold", "count", "mob", "total", "booster");
    public static final MessageKey SOLD_BONUS = MessageKey.chat("spawners.sell.sold-bonus", "count", "mob", "total", "multiplier", "booster");
    /** The receipts' {@code <booster>} part while a server sell booster raised the sale: " incl. +10% booster". */
    public static final MessageKey BOOSTER_NOTE = MessageKey.ui("spawners.sell.booster-note", "percent");
    public static final MessageKey RECEIPT_LINE = MessageKey.ui("spawners.sell.receipt-line", "amount", "item", "value");
    public static final MessageKey NOTHING_TO_SELL = MessageKey.error("spawners.sell.nothing");
    public static final MessageKey SELL_CANCELLED = MessageKey.info("spawners.sell.cancelled");
    public static final MessageKey SELL_BALANCE_FULL = MessageKey.error("spawners.sell.balance-full");
    public static final MessageKey SELL_TOO_MUCH = MessageKey.error("spawners.sell.too-much");
    public static final MessageKey XP_COLLECTED = MessageKey.success("spawners.xp.collected", "xp");
    public static final MessageKey XP_WAITING = MessageKey.chat("spawners.xp.waiting", "xp");
    public static final MessageKey XP_NONE = MessageKey.error("spawners.xp.none");

    // /spawners dialogs
    public static final MessageKey LIST_TITLE = MessageKey.ui("spawners.list.title");
    public static final MessageKey LIST_SUMMARY = MessageKey.ui("spawners.list.summary", "count", "stacked", "stored", "xp");
    public static final MessageKey LIST_PAGE = MessageKey.ui("spawners.list.page", "page", "pages");
    public static final MessageKey LIST_EMPTY = MessageKey.ui("spawners.list.empty");
    public static final MessageKey LIST_ENTRY = MessageKey.ui("spawners.list.entry", "name", "stack", "location", "world", "used",
        "capacity");
    public static final MessageKey LIST_BUTTON = MessageKey.ui("spawners.list.button", "name", "stack");
    public static final MessageKey LIST_TOOLTIP = MessageKey.ui("spawners.list.tooltip", "location", "world", "used", "capacity");
    public static final MessageKey PAGE_PREVIOUS = MessageKey.ui("spawners.list.previous");
    public static final MessageKey PAGE_NEXT = MessageKey.ui("spawners.list.next");
    public static final MessageKey LIST_SHOW_TEAM = MessageKey.ui("spawners.list.show-team");
    public static final MessageKey LIST_SHOW_OWN = MessageKey.ui("spawners.list.show-own");
    public static final MessageKey TEAM_LIST_TITLE = MessageKey.ui("spawners.team-list.title");
    public static final MessageKey TEAM_LIST_SUMMARY = MessageKey.ui("spawners.team-list.summary", "count", "stacked", "stored", "xp");
    public static final MessageKey TEAM_LIST_EMPTY = MessageKey.ui("spawners.team-list.empty");
    public static final MessageKey TEAM_LIST_ENTRY = MessageKey.ui("spawners.team-list.entry", "name", "stack", "owner", "location",
        "world", "used", "capacity");
    public static final MessageKey DETAILS_TITLE = MessageKey.ui("spawners.details.title", "name");
    public static final MessageKey DETAILS_BODY = MessageKey.ui("spawners.details.body", "name", "stack", "cap", "owner", "location",
        "world", "used", "capacity", "xp", "xp-cap", "value", "rate");
    public static final MessageKey STATUS_ACTIVE = MessageKey.ui("spawners.details.status.active");
    public static final MessageKey STATUS_FULL = MessageKey.ui("spawners.details.status.full");
    public static final MessageKey STATUS_IDLE = MessageKey.ui("spawners.details.status.idle", "radius");
    public static final MessageKey STATUS_DISABLED = MessageKey.ui("spawners.details.status.disabled");
    public static final MessageKey DETAILS_OPEN = MessageKey.ui("spawners.details.open");
    public static final MessageKey TOO_FAR = MessageKey.error("spawners.details.too-far", "range");

    // Hub
    public static final MessageKey HUB_LABEL = MessageKey.ui("spawners.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("spawners.hub.description");

    // Admin
    public static final MessageKey GIVEN = MessageKey.chat("spawners.admin.given", "amount", "mob", "name");
    public static final MessageKey GIVEN_CLAIM_BOX = MessageKey.chat("spawners.admin.given-claim-box", "amount", "mob", "name");
    public static final MessageKey RECEIVED = MessageKey.notify("spawners.admin.received", "amount", "mob");
    public static final MessageKey UNKNOWN_MOB = MessageKey.error("spawners.admin.unknown-mob", "mob", "mobs");
    public static final MessageKey GIVE_FAILED = MessageKey.error("spawners.admin.give-failed");
    public static final MessageKey ADMIN_LIST_HEADER = MessageKey.chat("spawners.admin.list-header", "name", "count", "stacked");
    public static final MessageKey ADMIN_LIST_LINE = MessageKey.chat("spawners.admin.list-line", "id", "name", "stack", "location",
        "world", "used", "capacity", "xp");
    public static final MessageKey ADMIN_LIST_EMPTY = MessageKey.chat("spawners.admin.list-empty", "name");
    public static final MessageKey ADMIN_CYCLE = MessageKey.chat("spawners.admin.cycle", "chunks");
    public static final MessageKey ADMIN_INFO = MessageKey.chat("spawners.admin.info", "spawners", "stacked", "owners", "chunks",
        "loaded", "dirty", "items", "xp");

    private SpawnersMessages() {
    }
}
