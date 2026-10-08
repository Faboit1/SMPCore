package net.siftvanilla.siftcore.feature.stats;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the stats feature ({@code lang/stats.yml}). */
public final class StatsMessages {

    public static final MessageKey VIEW_TITLE_SELF = MessageKey.ui("stats.view.title-self");
    public static final MessageKey VIEW_TITLE_OTHER = MessageKey.ui("stats.view.title-other", "name");
    public static final MessageKey VIEW_BODY = MessageKey.ui("stats.view.body",
        "kills", "deaths", "kdr", "streak", "best", "playtime", "mobs", "blocks", "earned", "balance");
    public static final MessageKey VIEW_BOARDS = MessageKey.ui("stats.view.boards");
    public static final MessageKey VIEW_CONSOLE = MessageKey.chat("stats.view.console",
        "name", "kills", "deaths", "kdr", "streak", "best", "playtime", "mobs", "blocks", "earned", "balance");
    public static final MessageKey LOAD_FAILED = MessageKey.error("stats.view.load-failed");

    public static final MessageKey BOARD_KILLS = MessageKey.ui("stats.boards.kills.button");
    public static final MessageKey BOARD_KILLS_TITLE = MessageKey.ui("stats.boards.kills.title");
    public static final MessageKey BOARD_DEATHS = MessageKey.ui("stats.boards.deaths.button");
    public static final MessageKey BOARD_DEATHS_TITLE = MessageKey.ui("stats.boards.deaths.title");
    public static final MessageKey BOARD_KDR = MessageKey.ui("stats.boards.kdr.button");
    public static final MessageKey BOARD_KDR_TITLE = MessageKey.ui("stats.boards.kdr.title");
    public static final MessageKey BOARD_STREAK = MessageKey.ui("stats.boards.streak.button");
    public static final MessageKey BOARD_STREAK_TITLE = MessageKey.ui("stats.boards.streak.title");
    public static final MessageKey BOARD_PLAYTIME = MessageKey.ui("stats.boards.playtime.button");
    public static final MessageKey BOARD_PLAYTIME_TITLE = MessageKey.ui("stats.boards.playtime.title");
    public static final MessageKey BOARD_MOBS = MessageKey.ui("stats.boards.mobs.button");
    public static final MessageKey BOARD_MOBS_TITLE = MessageKey.ui("stats.boards.mobs.title");
    public static final MessageKey BOARD_BLOCKS = MessageKey.ui("stats.boards.blocks.button");
    public static final MessageKey BOARD_BLOCKS_TITLE = MessageKey.ui("stats.boards.blocks.title");
    public static final MessageKey BOARD_EARNED = MessageKey.ui("stats.boards.earned.button");
    public static final MessageKey BOARD_EARNED_TITLE = MessageKey.ui("stats.boards.earned.title");
    public static final MessageKey BOARD_MONEY = MessageKey.ui("stats.boards.money.button");
    public static final MessageKey BOARD_MONEY_TITLE = MessageKey.ui("stats.boards.money.title");

    public static final MessageKey TOP_PAGE = MessageKey.ui("stats.top.page", "page", "pages");
    public static final MessageKey TOP_LINE = MessageKey.ui("stats.top.line", "rank", "name", "value");
    public static final MessageKey TOP_YOU = MessageKey.ui("stats.top.you", "rank", "value");
    public static final MessageKey TOP_NOT_LISTED = MessageKey.ui("stats.top.not-listed");
    public static final MessageKey TOP_EMPTY = MessageKey.ui("stats.top.empty");
    public static final MessageKey TOP_KDR_RULE = MessageKey.ui("stats.top.kdr-rule", "kills");
    public static final MessageKey TOP_UPDATED = MessageKey.ui("stats.top.updated", "time");
    public static final MessageKey TOP_NOT_READY = MessageKey.ui("stats.top.not-ready");
    public static final MessageKey TOP_NEXT = MessageKey.ui("stats.top.next");
    public static final MessageKey TOP_PREVIOUS = MessageKey.ui("stats.top.previous");
    public static final MessageKey TOP_UNKNOWN = MessageKey.error("stats.top.unknown", "input", "boards");
    public static final MessageKey TOP_LIST = MessageKey.chat("stats.top.list", "boards");
    public static final MessageKey TOP_CONSOLE_HEADER = MessageKey.chat("stats.top.console-header", "title", "page", "pages");
    public static final MessageKey TOP_CONSOLE_LINE = MessageKey.chat("stats.top.console-line", "rank", "name", "value");
    public static final MessageKey TOP_CONSOLE_EMPTY = MessageKey.chat("stats.top.console-empty");
    public static final MessageKey PICKER_TITLE = MessageKey.ui("stats.picker.title");
    public static final MessageKey PICKER_BODY = MessageKey.ui("stats.picker.body");

    public static final MessageKey PLAYTIME_SELF = MessageKey.chat("stats.playtime.self", "time");
    public static final MessageKey PLAYTIME_OTHER = MessageKey.chat("stats.playtime.other", "name", "time");

    public static final MessageKey STAT_KILLS = MessageKey.ui("stats.names.kills");
    public static final MessageKey STAT_DEATHS = MessageKey.ui("stats.names.deaths");
    public static final MessageKey STAT_MOBS = MessageKey.ui("stats.names.mobs");
    public static final MessageKey STAT_BLOCKS = MessageKey.ui("stats.names.blocks");
    public static final MessageKey STAT_EARNED = MessageKey.ui("stats.names.earned");
    public static final MessageKey STAT_PLAYTIME = MessageKey.ui("stats.names.playtime");

    public static final MessageKey ADMIN_SET = MessageKey.chat("stats.admin.set", "name", "stat", "value");
    public static final MessageKey ADMIN_ADDED = MessageKey.chat("stats.admin.added", "name", "stat", "value");
    public static final MessageKey ADMIN_RESET = MessageKey.chat("stats.admin.reset", "name");
    public static final MessageKey ADMIN_SAVE_FAILED = MessageKey.chat("stats.admin.save-failed", "name");
    public static final MessageKey ADMIN_UNKNOWN_STAT = MessageKey.chat("stats.admin.unknown-stat", "input", "stats");
    public static final MessageKey ADMIN_INVALID_VALUE = MessageKey.chat("stats.admin.invalid-value", "input", "stat");
    public static final MessageKey ADMIN_REFRESHED = MessageKey.chat("stats.admin.refreshed", "time");
    public static final MessageKey ADMIN_REFRESH_BUSY = MessageKey.chat("stats.admin.refresh-busy");
    public static final MessageKey ADMIN_REFRESH_FAILED = MessageKey.chat("stats.admin.refresh-failed");

    public static final MessageKey HUB_LABEL = MessageKey.ui("stats.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("stats.hub.description");

    private StatsMessages() {
    }

    /** The button label of a board. */
    public static MessageKey button(Board board) {
        return switch (board) {
            case KILLS -> BOARD_KILLS;
            case DEATHS -> BOARD_DEATHS;
            case KDR -> BOARD_KDR;
            case STREAK -> BOARD_STREAK;
            case PLAYTIME -> BOARD_PLAYTIME;
            case MOBS -> BOARD_MOBS;
            case BLOCKS -> BOARD_BLOCKS;
            case EARNED -> BOARD_EARNED;
            case MONEY -> BOARD_MONEY;
        };
    }

    /** The dialog title of a board. */
    public static MessageKey title(Board board) {
        return switch (board) {
            case KILLS -> BOARD_KILLS_TITLE;
            case DEATHS -> BOARD_DEATHS_TITLE;
            case KDR -> BOARD_KDR_TITLE;
            case STREAK -> BOARD_STREAK_TITLE;
            case PLAYTIME -> BOARD_PLAYTIME_TITLE;
            case MOBS -> BOARD_MOBS_TITLE;
            case BLOCKS -> BOARD_BLOCKS_TITLE;
            case EARNED -> BOARD_EARNED_TITLE;
            case MONEY -> BOARD_MONEY_TITLE;
        };
    }

    /** The name of a counter in staff messages. */
    public static MessageKey name(Counter counter) {
        return switch (counter) {
            case KILLS -> STAT_KILLS;
            case DEATHS -> STAT_DEATHS;
            case MOBS -> STAT_MOBS;
            case BLOCKS -> STAT_BLOCKS;
            case EARNED -> STAT_EARNED;
            case PLAYTIME -> STAT_PLAYTIME;
        };
    }
}
