package net.siftvanilla.siftcore.feature.afk;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the AFK feature ({@code lang/afk.yml}). */
public final class AfkMessages {

    public static final MessageKey NOW_AFK = MessageKey.info("afk.now-afk");
    public static final MessageKey NOW_AFK_MANUAL = MessageKey.info("afk.now-afk-manual");
    public static final MessageKey BACK = MessageKey.info("afk.back");
    public static final MessageKey KICK_WARNING = MessageKey.notify("afk.kick-warning", "time");
    public static final MessageKey KICK_WARNING_TITLE = MessageKey.title("afk.kick-warning-title", "time");
    public static final MessageKey KICK_WARNING_SUBTITLE = MessageKey.ui("afk.kick-warning-subtitle", "time");
    public static final MessageKey RETURN_SUMMARY = MessageKey.chat("afk.return-summary", "time");
    public static final MessageKey RETURN_SUMMARY_SHARD = MessageKey.chat("afk.return-summary-shard", "time");
    public static final MessageKey RETURN_SUMMARY_SHARDS = MessageKey.chat("afk.return-summary-shards", "time", "amount");
    public static final MessageKey KICK_REASON = MessageKey.ui("afk.kick-reason");
    public static final MessageKey PLACEHOLDER = MessageKey.ui("afk.placeholder");

    public static final MessageKey LIST_HEADER = MessageKey.chat("afk.list.header", "count");
    public static final MessageKey LIST_LINE = MessageKey.chat("afk.list.line", "name", "time");
    public static final MessageKey LIST_LINE_ZONE = MessageKey.chat("afk.list.line-zone", "name", "time");
    public static final MessageKey LIST_LINE_MANUAL = MessageKey.chat("afk.list.line-manual", "name", "time");
    public static final MessageKey LIST_EMPTY = MessageKey.chat("afk.list.empty");

    public static final MessageKey ZONE_ENTERED = MessageKey.info("afk.zone.entered", "amount", "time");
    public static final MessageKey ZONE_LEFT = MessageKey.info("afk.zone.left");
    public static final MessageKey ZONE_STATUS = MessageKey.status("afk.zone.status", "time");
    public static final MessageKey ZONE_STATUS_MANY = MessageKey.status("afk.zone.status-many", "amount", "time");
    public static final MessageKey ZONE_WAITING_ALT = MessageKey.info("afk.zone.waiting-alt");
    public static final MessageKey ZONE_COMBAT = MessageKey.info("afk.zone.combat");
    public static final MessageKey ZONE_CAPPED = MessageKey.info("afk.zone.capped", "cap");
    public static final MessageKey ZONE_EARNED_ONE = MessageKey.info("afk.zone.earned-one", "balance");
    public static final MessageKey ZONE_EARNED_MANY = MessageKey.info("afk.zone.earned-many", "amount", "balance");
    public static final MessageKey ZONE_CAPPED_NOW = MessageKey.notify("afk.zone.capped-now", "cap");
    public static final MessageKey ZONE_CLOSED = MessageKey.error("afk.zone.closed");
    public static final MessageKey ZONE_NO_FIGHTING = MessageKey.error("afk.zone.no-fighting");
    public static final MessageKey ZONE_ALREADY_THERE = MessageKey.info("afk.zone.already-there");

    public static final MessageKey ADMIN_HEADER = MessageKey.chat("afk.admin.header", "where");
    public static final MessageKey ADMIN_SOURCE_CONFIG = MessageKey.chat("afk.admin.source-config");
    public static final MessageKey ADMIN_SOURCE_GAME = MessageKey.chat("afk.admin.source-game");
    public static final MessageKey ADMIN_OFF = MessageKey.chat("afk.admin.turned-off");
    public static final MessageKey ADMIN_WORLD_MISSING = MessageKey.chat("afk.admin.world-missing", "world");
    public static final MessageKey ADMIN_ARRIVAL = MessageKey.chat("afk.admin.arrival", "where");
    public static final MessageKey ADMIN_ARRIVAL_AUTO = MessageKey.chat("afk.admin.arrival-auto");
    public static final MessageKey ADMIN_PLAYERS = MessageKey.chat("afk.admin.players", "count", "earning");
    public static final MessageKey ADMIN_REWARDS = MessageKey.chat("afk.admin.rewards", "amount", "time");
    public static final MessageKey ADMIN_RANK_REWARDS = MessageKey.chat("afk.admin.rank-rewards", "ranks");
    public static final MessageKey ADMIN_CAP = MessageKey.chat("afk.admin.cap", "cap");
    public static final MessageKey ADMIN_NO_CAP = MessageKey.chat("afk.admin.no-cap");
    public static final MessageKey ADMIN_PROTECTED = MessageKey.chat("afk.admin.protected");
    public static final MessageKey ADMIN_SAFE = MessageKey.chat("afk.admin.safe");
    public static final MessageKey ADMIN_UNSAFE = MessageKey.chat("afk.admin.unsafe");
    public static final MessageKey ADMIN_CORNER_SET = MessageKey.chat("afk.admin.corner-set", "corner", "where");
    public static final MessageKey ADMIN_CORNER_NEXT = MessageKey.chat("afk.admin.corner-next", "corner");
    public static final MessageKey ADMIN_ZONE_SET = MessageKey.chat("afk.admin.zone-set", "where");
    public static final MessageKey ADMIN_ARRIVAL_SET = MessageKey.chat("afk.admin.arrival-set", "where");
    public static final MessageKey ADMIN_ARRIVAL_OUTSIDE = MessageKey.chat("afk.admin.arrival-outside");
    public static final MessageKey ADMIN_RESET = MessageKey.chat("afk.admin.reset", "where");
    public static final MessageKey ADMIN_NOTHING_TO_RESET = MessageKey.chat("afk.admin.nothing-to-reset");
    public static final MessageKey ADMIN_UNKNOWN_WORLD = MessageKey.chat("afk.admin.unknown-world", "world");
    public static final MessageKey ADMIN_SAVE_FAILED = MessageKey.chat("afk.admin.save-failed");

    public static final MessageKey SETTING_STATUS = MessageKey.ui("afk.settings.status");
    public static final MessageKey SETTING_STATUS_DESCRIPTION = MessageKey.ui("afk.settings.status-description");
    public static final MessageKey SETTING_PAYOUTS = MessageKey.ui("afk.settings.payouts");
    public static final MessageKey SETTING_PAYOUTS_DESCRIPTION = MessageKey.ui("afk.settings.payouts-description");
    public static final MessageKey SETTING_KICK_WARNING = MessageKey.ui("afk.settings.kick-warning");
    public static final MessageKey SETTING_KICK_WARNING_DESCRIPTION = MessageKey.ui("afk.settings.kick-warning-description");
    public static final MessageKey SETTING_STATUS_MESSAGES = MessageKey.ui("afk.settings.status-messages");
    public static final MessageKey SETTING_STATUS_MESSAGES_DESCRIPTION = MessageKey.ui("afk.settings.status-messages-description");
    public static final MessageKey SETTING_RETURN_SUMMARY = MessageKey.ui("afk.settings.return-summary");
    public static final MessageKey SETTING_RETURN_SUMMARY_DESCRIPTION = MessageKey.ui("afk.settings.return-summary-description");

    private AfkMessages() {
    }
}
