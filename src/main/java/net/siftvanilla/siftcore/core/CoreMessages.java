package net.siftvanilla.siftcore.core;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Messages shared by every feature. */
public final class CoreMessages {

    public static final MessageKey PLAYERS_ONLY = MessageKey.error("core.players-only");
    public static final MessageKey NO_PERMISSION = MessageKey.error("core.no-permission");
    public static final MessageKey PLAYER_NOT_FOUND = MessageKey.error("core.player-not-found", "name");
    public static final MessageKey PLAYER_NOT_ONLINE = MessageKey.error("core.player-not-online", "name");
    public static final MessageKey NOT_YOURSELF = MessageKey.error("core.not-yourself");
    public static final MessageKey COOLDOWN = MessageKey.error("core.cooldown", "time");
    public static final MessageKey INVALID_AMOUNT = MessageKey.error("core.invalid-amount", "input");
    public static final MessageKey AMOUNT_NOT_WHOLE = MessageKey.error("core.amount-not-whole", "input");
    public static final MessageKey AMOUNT_NOT_POSITIVE = MessageKey.error("core.amount-not-positive");
    public static final MessageKey AMOUNT_TOO_LARGE = MessageKey.error("core.amount-too-large", "max");
    public static final MessageKey INVALID_NUMBER = MessageKey.error("core.invalid-number", "input", "min", "max");
    public static final MessageKey NOT_ENOUGH_MONEY = MessageKey.error("core.not-enough-money", "amount");
    public static final MessageKey NOT_ENOUGH_SHARDS = MessageKey.error("core.not-enough-shards", "amount");
    public static final MessageKey BALANCE_LIMIT = MessageKey.error("core.balance-limit");
    public static final MessageKey ECONOMY_UNAVAILABLE = MessageKey.error("core.economy-unavailable");
    public static final MessageKey ACTION_FAILED = MessageKey.error("core.action-failed");
    public static final MessageKey SLOW_DOWN = MessageKey.error("core.slow-down");
    public static final MessageKey INVENTORY_FULL = MessageKey.error("core.inventory-full");
    public static final MessageKey DISABLED_WORLD = MessageKey.error("core.disabled-here");
    public static final MessageKey FROZEN = MessageKey.error("core.frozen");
    public static final MessageKey LOADING = MessageKey.info("core.loading");

    /** Dialog and GUI building blocks shared everywhere. */
    public static final MessageKey UI_BACK = MessageKey.ui("ui.back");
    public static final MessageKey UI_CLOSE = MessageKey.ui("ui.close");
    public static final MessageKey UI_CONFIRM = MessageKey.ui("ui.confirm");
    public static final MessageKey UI_CANCEL = MessageKey.ui("ui.cancel");
    public static final MessageKey UI_SUBMIT = MessageKey.ui("ui.submit");
    public static final MessageKey UI_OK = MessageKey.ui("ui.ok");
    public static final MessageKey UI_YES = MessageKey.ui("ui.yes");
    public static final MessageKey UI_NO = MessageKey.ui("ui.no");
    public static final MessageKey UI_NEXT_PAGE = MessageKey.ui("ui.next-page");
    public static final MessageKey UI_NEXT_PAGE_LORE = MessageKey.ui("ui.next-page-lore", "page", "pages");
    public static final MessageKey UI_PREVIOUS_PAGE = MessageKey.ui("ui.previous-page");
    public static final MessageKey UI_PREVIOUS_PAGE_LORE = MessageKey.ui("ui.previous-page-lore", "page", "pages");
    public static final MessageKey UI_SORT = MessageKey.ui("ui.sort");
    public static final MessageKey UI_FILTER = MessageKey.ui("ui.filter");
    public static final MessageKey UI_OPTION = MessageKey.ui("ui.option", "option");
    public static final MessageKey UI_OPTION_SELECTED = MessageKey.ui("ui.option-selected", "option");
    public static final MessageKey UI_CYCLE_HINT = MessageKey.ui("ui.cycle-hint");
    public static final MessageKey UI_SEARCH = MessageKey.ui("ui.search");
    public static final MessageKey UI_SEARCH_LORE = MessageKey.ui("ui.search-lore");
    public static final MessageKey UI_SEARCH_ACTIVE_LORE = MessageKey.ui("ui.search-active-lore", "query");
    public static final MessageKey UI_SEARCH_TITLE = MessageKey.ui("ui.search-title");
    public static final MessageKey UI_SEARCH_INPUT = MessageKey.ui("ui.search-input");
    public static final MessageKey UI_SEARCH_CLEAR = MessageKey.ui("ui.search-clear");
    public static final MessageKey UI_EMPTY = MessageKey.ui("ui.empty");
    public static final MessageKey UI_EMPTY_LORE = MessageKey.ui("ui.empty-lore");
    public static final MessageKey UI_ERROR_LINE = MessageKey.ui("ui.error-line", "error");
    public static final MessageKey UI_INVALID_INPUT = MessageKey.ui("ui.invalid-input", "field");
    public static final MessageKey UI_EXPIRED = MessageKey.error("ui.expired");

    private CoreMessages() {
    }
}
