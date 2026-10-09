package net.siftvanilla.siftcore.feature.staff;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the staff tools ({@code lang/staff.yml}). */
public final class StaffMessages {

    // ------------------------------------------------------------------ shared words
    public static final MessageKey CONSOLE = MessageKey.ui("staff.console");
    public static final MessageKey NO_REASON = MessageKey.ui("staff.no-reason");
    public static final MessageKey PERMANENT = MessageKey.ui("staff.permanent");
    public static final MessageKey TYPE_BAN = MessageKey.ui("staff.type.ban");
    public static final MessageKey TYPE_MUTE = MessageKey.ui("staff.type.mute");
    public static final MessageKey TYPE_KICK = MessageKey.ui("staff.type.kick");
    public static final MessageKey TYPE_WARN = MessageKey.ui("staff.type.warn");
    public static final MessageKey PAGE_NEXT = MessageKey.ui("staff.page.next");
    public static final MessageKey PAGE_PREVIOUS = MessageKey.ui("staff.page.previous");

    // ------------------------------------------------------------------ input problems
    public static final MessageKey DURATION_MISSING = MessageKey.error("staff.duration.missing");
    public static final MessageKey DURATION_INVALID = MessageKey.error("staff.duration.invalid", "input");
    public static final MessageKey DURATION_TOO_SHORT = MessageKey.error("staff.duration.too-short");
    public static final MessageKey DURATION_TOO_LONG = MessageKey.error("staff.duration.too-long", "max");
    public static final MessageKey DURATION_TOO_LONG_BAN = MessageKey.error("staff.duration.too-long-ban", "max");
    public static final MessageKey REASON_TOO_LONG = MessageKey.error("staff.reason-too-long", "max");

    // ------------------------------------------------------------------ hierarchy
    public static final MessageKey HIERARCHY_REFUSED = MessageKey.error("staff.hierarchy.refused", "name");
    public static final MessageKey HIERARCHY_UNKNOWN = MessageKey.error("staff.hierarchy.unknown", "name");

    // ------------------------------------------------------------------ vanish
    public static final MessageKey VANISH_ON = MessageKey.success("staff.vanish.on");
    public static final MessageKey VANISH_OFF = MessageKey.success("staff.vanish.off");
    public static final MessageKey VANISH_ON_OTHER = MessageKey.chat("staff.vanish.on-other", "name");
    public static final MessageKey VANISH_OFF_OTHER = MessageKey.chat("staff.vanish.off-other", "name");
    public static final MessageKey VANISH_REMINDER = MessageKey.status("staff.vanish.reminder");
    public static final MessageKey VANISH_CLEARED = MessageKey.chat("staff.vanish.cleared");

    // ------------------------------------------------------------------ freeze
    public static final MessageKey FREEZE_DONE = MessageKey.chat("staff.freeze.done", "name");
    public static final MessageKey UNFREEZE_DONE = MessageKey.chat("staff.freeze.undone", "name");
    public static final MessageKey FROZEN = MessageKey.notify("staff.freeze.frozen");
    public static final MessageKey UNFROZEN = MessageKey.notify("staff.freeze.unfrozen");
    public static final MessageKey FREEZE_REMINDER = MessageKey.info("staff.freeze.reminder");
    public static final MessageKey FREEZE_BLOCKED = MessageKey.error("staff.freeze.blocked");
    public static final MessageKey FREEZE_BLOCKED_COMMAND = MessageKey.error("staff.freeze.blocked-command");
    public static final MessageKey FREEZE_LOGOUT = MessageKey.notify("staff.freeze.logout", "name");
    public static final MessageKey FREEZE_LOGOUT_BANNED = MessageKey.notify("staff.freeze.logout-banned", "name");

    // ------------------------------------------------------------------ mutes
    /** Shown when a muted player tries to talk (temporary mute). The chat feature uses this key too. */
    public static final MessageKey MUTED = MessageKey.error("staff.muted", "reason", "time");
    /** Shown when a muted player tries to talk (permanent mute). */
    public static final MessageKey MUTED_PERMANENT = MessageKey.error("staff.muted-permanent", "reason");
    public static final MessageKey MUTE_DONE = MessageKey.chat("staff.mute.done", "name", "time");
    public static final MessageKey MUTE_DONE_PERMANENT = MessageKey.chat("staff.mute.done-permanent", "name");
    public static final MessageKey MUTE_TARGET = MessageKey.notify("staff.mute.target", "time", "reason");
    public static final MessageKey MUTE_TARGET_PERMANENT = MessageKey.notify("staff.mute.target-permanent", "reason");
    public static final MessageKey UNMUTE_DONE = MessageKey.chat("staff.mute.undone", "name");
    public static final MessageKey UNMUTE_TARGET = MessageKey.notify("staff.mute.lifted");
    public static final MessageKey MUTE_EXPIRED = MessageKey.chat("staff.mute.expired");
    public static final MessageKey NOT_MUTED = MessageKey.error("staff.mute.not-muted", "name");

    // ------------------------------------------------------------------ bans, kicks, warnings
    public static final MessageKey BAN_DONE = MessageKey.chat("staff.ban.done", "name", "time");
    public static final MessageKey BAN_DONE_PERMANENT = MessageKey.chat("staff.ban.done-permanent", "name");
    public static final MessageKey UNBAN_DONE = MessageKey.chat("staff.ban.undone", "name");
    public static final MessageKey NOT_BANNED = MessageKey.error("staff.ban.not-banned", "name");
    public static final MessageKey BAN_SCREEN = MessageKey.ui("staff.ban.screen", "reason", "time", "appeal");
    public static final MessageKey BAN_SCREEN_PERMANENT = MessageKey.ui("staff.ban.screen-permanent", "reason", "appeal");
    public static final MessageKey KICK_DONE = MessageKey.chat("staff.kick.done", "name");
    public static final MessageKey KICK_SCREEN = MessageKey.ui("staff.kick.screen", "reason");
    public static final MessageKey WARN_DONE = MessageKey.chat("staff.warn.done", "name");
    public static final MessageKey WARN_DONE_OFFLINE = MessageKey.chat("staff.warn.done-offline", "name");
    public static final MessageKey WARN_TARGET = MessageKey.notify("staff.warn.target", "reason");
    public static final MessageKey WARN_MISSED = MessageKey.notify("staff.warn.missed", "ago", "reason");

    // ------------------------------------------------------------------ notifications to staff
    public static final MessageKey NOTIFY_BAN = MessageKey.chat("staff.notify.ban", "staff", "name", "time", "reason");
    public static final MessageKey NOTIFY_BAN_PERMANENT = MessageKey.chat("staff.notify.ban-permanent", "staff", "name", "reason");
    public static final MessageKey NOTIFY_UNBAN = MessageKey.chat("staff.notify.unban", "staff", "name");
    public static final MessageKey NOTIFY_MUTE = MessageKey.chat("staff.notify.mute", "staff", "name", "time", "reason");
    public static final MessageKey NOTIFY_MUTE_PERMANENT = MessageKey.chat("staff.notify.mute-permanent", "staff", "name", "reason");
    public static final MessageKey NOTIFY_UNMUTE = MessageKey.chat("staff.notify.unmute", "staff", "name");
    public static final MessageKey NOTIFY_KICK = MessageKey.chat("staff.notify.kick", "staff", "name", "reason");
    public static final MessageKey NOTIFY_WARN = MessageKey.chat("staff.notify.warn", "staff", "name", "reason");
    public static final MessageKey NOTIFY_FREEZE = MessageKey.chat("staff.notify.freeze", "staff", "name");
    public static final MessageKey NOTIFY_UNFREEZE = MessageKey.chat("staff.notify.unfreeze", "staff", "name");

    // ------------------------------------------------------------------ history
    public static final MessageKey HISTORY_TITLE = MessageKey.ui("staff.history.title", "name");
    public static final MessageKey HISTORY_HEADER = MessageKey.chat("staff.history.header", "name", "count");
    public static final MessageKey HISTORY_PAGE = MessageKey.ui("staff.history.page", "count", "page", "pages");
    public static final MessageKey HISTORY_EMPTY = MessageKey.ui("staff.history.empty", "name");
    public static final MessageKey HISTORY_ENTRY = MessageKey.ui("staff.history.entry", "type", "ago", "staff");
    public static final MessageKey HISTORY_REASON = MessageKey.ui("staff.history.reason", "reason");
    public static final MessageKey HISTORY_LENGTH = MessageKey.ui("staff.history.length", "length", "state");
    public static final MessageKey STATE_ACTIVE = MessageKey.ui("staff.state.active", "left");
    public static final MessageKey STATE_ACTIVE_PERMANENT = MessageKey.ui("staff.state.active-permanent");
    public static final MessageKey STATE_EXPIRED = MessageKey.ui("staff.state.expired");
    public static final MessageKey STATE_LIFTED = MessageKey.ui("staff.state.lifted", "name");

    // ------------------------------------------------------------------ staff chat
    public static final MessageKey CHAT_FORMAT = MessageKey.chat("staff.chat.format", "name", "message");
    public static final MessageKey CHAT_ON = MessageKey.success("staff.chat.on");
    public static final MessageKey CHAT_OFF = MessageKey.success("staff.chat.off");

    // ------------------------------------------------------------------ reports (players)
    public static final MessageKey REPORT_SENT = MessageKey.success("staff.report.sent", "name");
    public static final MessageKey REPORT_TOO_SHORT = MessageKey.error("staff.report.too-short", "min");
    public static final MessageKey REPORT_TOO_LONG = MessageKey.error("staff.report.too-long", "max");
    public static final MessageKey REPORT_DUPLICATE = MessageKey.error("staff.report.duplicate", "name");
    public static final MessageKey REPORT_TOO_MANY = MessageKey.error("staff.report.too-many", "count");
    public static final MessageKey REPORT_HANDLED = MessageKey.notify("staff.report.handled", "name");
    public static final MessageKey REPORT_FORM_TITLE = MessageKey.ui("staff.report.form-title");
    public static final MessageKey REPORT_FORM_BODY = MessageKey.ui("staff.report.form-body");
    public static final MessageKey REPORT_FORM_PLAYER = MessageKey.ui("staff.report.form-player");
    public static final MessageKey REPORT_FORM_REASON = MessageKey.ui("staff.report.form-reason");
    public static final MessageKey REPORT_FORM_SUBMIT = MessageKey.ui("staff.report.form-submit");
    public static final MessageKey HUB_REPORT = MessageKey.ui("staff.hub.report");
    public static final MessageKey HUB_REPORT_DESCRIPTION = MessageKey.ui("staff.hub.report-description");

    // ------------------------------------------------------------------ reports (staff)
    public static final MessageKey REPORT_NOTIFY = MessageKey.notify("staff.report.notify", "id", "reporter", "target", "reason");
    public static final MessageKey REPORT_NOTIFY_HOVER = MessageKey.ui("staff.report.notify-hover");
    public static final MessageKey REPORTS_TITLE = MessageKey.ui("staff.reports.title");
    public static final MessageKey REPORTS_SUMMARY = MessageKey.ui("staff.reports.summary", "count", "page", "pages");
    public static final MessageKey REPORTS_EMPTY = MessageKey.ui("staff.reports.empty");
    public static final MessageKey REPORTS_LINE = MessageKey.ui("staff.reports.line", "id", "target", "reporter", "age", "status");
    public static final MessageKey REPORTS_ONLINE = MessageKey.ui("staff.reports.online");
    public static final MessageKey REPORTS_OFFLINE = MessageKey.ui("staff.reports.offline");
    public static final MessageKey REPORTS_BUTTON = MessageKey.ui("staff.reports.button", "id", "target");
    public static final MessageKey REPORTS_DETAIL_TITLE = MessageKey.ui("staff.reports.detail-title", "id");
    public static final MessageKey REPORTS_DETAIL = MessageKey.ui("staff.reports.detail", "target", "status", "reporter", "age", "reason");
    public static final MessageKey REPORTS_TELEPORT = MessageKey.ui("staff.reports.teleport", "name");
    public static final MessageKey REPORTS_HANDLE = MessageKey.ui("staff.reports.handle");
    public static final MessageKey REPORTS_DISMISS = MessageKey.ui("staff.reports.dismiss");
    public static final MessageKey REPORTS_HANDLED = MessageKey.success("staff.reports.handled", "id");
    public static final MessageKey REPORTS_DISMISSED = MessageKey.success("staff.reports.dismissed", "id");
    public static final MessageKey REPORTS_CLOSED = MessageKey.error("staff.reports.closed", "id");
    public static final MessageKey REPORTS_TELEPORTED = MessageKey.success("staff.reports.teleported", "name");
    public static final MessageKey REPORTS_CONSOLE_LINE = MessageKey.chat("staff.reports.console-line", "id", "target", "reporter", "age", "status", "reason");

    // ------------------------------------------------------------------ inventory inspection
    public static final MessageKey INSPECT_TITLE_INVENTORY = MessageKey.ui("staff.inspect.title-inventory", "name");
    public static final MessageKey INSPECT_TITLE_ENDER = MessageKey.ui("staff.inspect.title-ender", "name");
    public static final MessageKey INSPECT_SLOT_HOTBAR = MessageKey.ui("staff.inspect.slot.hotbar", "slot");
    public static final MessageKey INSPECT_SLOT_STORAGE = MessageKey.ui("staff.inspect.slot.storage");
    public static final MessageKey INSPECT_SLOT_HELMET = MessageKey.ui("staff.inspect.slot.helmet");
    public static final MessageKey INSPECT_SLOT_CHESTPLATE = MessageKey.ui("staff.inspect.slot.chestplate");
    public static final MessageKey INSPECT_SLOT_LEGGINGS = MessageKey.ui("staff.inspect.slot.leggings");
    public static final MessageKey INSPECT_SLOT_BOOTS = MessageKey.ui("staff.inspect.slot.boots");
    public static final MessageKey INSPECT_SLOT_OFF_HAND = MessageKey.ui("staff.inspect.slot.off-hand");
    public static final MessageKey INSPECT_SLOT_ENDER = MessageKey.ui("staff.inspect.slot.ender-chest");
    public static final MessageKey INSPECT_TAKE_HINT = MessageKey.ui("staff.inspect.take-hint");
    public static final MessageKey INSPECT_INFO_NAME = MessageKey.ui("staff.inspect.info-name", "name");
    public static final MessageKey INSPECT_INFO_LORE = MessageKey.ui("staff.inspect.info-lore", "health", "food", "level", "mode");
    public static final MessageKey INSPECT_REFRESH = MessageKey.ui("staff.inspect.refresh");
    public static final MessageKey INSPECT_REFRESH_LORE = MessageKey.ui("staff.inspect.refresh-lore");
    public static final MessageKey INSPECT_CLEAR_INVENTORY = MessageKey.ui("staff.inspect.clear-inventory");
    public static final MessageKey INSPECT_CLEAR_ENDER = MessageKey.ui("staff.inspect.clear-ender");
    public static final MessageKey INSPECT_CLEAR_LORE = MessageKey.ui("staff.inspect.clear-lore");
    public static final MessageKey INSPECT_SWITCH_ENDER = MessageKey.ui("staff.inspect.switch-ender");
    public static final MessageKey INSPECT_SWITCH_ENDER_LORE = MessageKey.ui("staff.inspect.switch-ender-lore", "name");
    public static final MessageKey INSPECT_SWITCH_INVENTORY = MessageKey.ui("staff.inspect.switch-inventory");
    public static final MessageKey INSPECT_SWITCH_INVENTORY_LORE = MessageKey.ui("staff.inspect.switch-inventory-lore", "name");
    public static final MessageKey INSPECT_TAKEN = MessageKey.success("staff.inspect.taken", "amount", "item", "name");
    public static final MessageKey INSPECT_CLAIM_BOX = MessageKey.chat("staff.inspect.claim-box");
    public static final MessageKey INSPECT_CHANGED = MessageKey.error("staff.inspect.changed");
    public static final MessageKey INSPECT_CLEARED = MessageKey.success("staff.inspect.cleared", "count", "name");
    public static final MessageKey INSPECT_CLEAR_TITLE = MessageKey.ui("staff.inspect.clear-title");
    public static final MessageKey INSPECT_CLEAR_BODY_INVENTORY = MessageKey.ui("staff.inspect.clear-body-inventory", "name");
    public static final MessageKey INSPECT_CLEAR_BODY_ENDER = MessageKey.ui("staff.inspect.clear-body-ender", "name");
    public static final MessageKey INSPECT_CLEAR_CONFIRM = MessageKey.ui("staff.inspect.clear-confirm");
    public static final MessageKey INSPECT_SELF = MessageKey.error("staff.inspect.self");
    public static final MessageKey INSPECT_VIEW_ONLY = MessageKey.error("staff.inspect.view-only");
    public static final MessageKey INSPECT_GONE = MessageKey.error("staff.inspect.gone", "name");

    // ------------------------------------------------------------------ lookups
    public static final MessageKey ALTS_HEADER = MessageKey.chat("staff.alts.header", "name", "count");
    public static final MessageKey ALTS_LINE = MessageKey.chat("staff.alts.line", "name", "seen", "status");
    public static final MessageKey ALTS_NONE = MessageKey.chat("staff.alts.none", "name");
    public static final MessageKey ALTS_UNKNOWN = MessageKey.chat("staff.alts.unknown", "name");
    public static final MessageKey SEEN_NOW = MessageKey.ui("staff.seen.now");
    public static final MessageKey SEEN_AGO = MessageKey.ui("staff.seen.ago", "ago");
    public static final MessageKey STATUS_BANNED = MessageKey.ui("staff.status.banned");
    public static final MessageKey STATUS_MUTED = MessageKey.ui("staff.status.muted");
    public static final MessageKey WHOIS_TITLE = MessageKey.ui("staff.whois.title", "name");
    public static final MessageKey WHOIS_HEADER = MessageKey.chat("staff.whois.header", "name");
    public static final MessageKey WHOIS_IDENTITY = MessageKey.ui("staff.whois.identity", "uuid", "first", "seen");
    public static final MessageKey WHOIS_ONLINE = MessageKey.ui("staff.whois.online", "world", "x", "y", "z", "ping", "mode");
    public static final MessageKey WHOIS_MONEY = MessageKey.ui("staff.whois.money", "balance", "shards");
    public static final MessageKey WHOIS_ALTS = MessageKey.ui("staff.whois.alts", "count");
    public static final MessageKey WHOIS_NOW = MessageKey.ui("staff.whois.now", "list");
    public static final MessageKey WHOIS_CLEAN = MessageKey.ui("staff.whois.clean");
    public static final MessageKey WHOIS_BANNED = MessageKey.ui("staff.whois.banned", "time");
    public static final MessageKey WHOIS_MUTED = MessageKey.ui("staff.whois.muted", "time");
    public static final MessageKey WHOIS_FROZEN = MessageKey.ui("staff.whois.frozen");
    public static final MessageKey WHOIS_VANISHED = MessageKey.ui("staff.whois.vanished");
    public static final MessageKey WHOIS_LEFT = MessageKey.ui("staff.whois.left", "time");
    public static final MessageKey WHOIS_HISTORY = MessageKey.ui("staff.whois.history");
    public static final MessageKey WHOIS_INVENTORY = MessageKey.ui("staff.whois.inventory");
    public static final MessageKey WHOIS_ENDER = MessageKey.ui("staff.whois.ender");

    // ------------------------------------------------------------------ announcements
    public static final MessageKey BROADCAST = MessageKey.notify("staff.broadcast", "message");
    public static final MessageKey CLEARCHAT_DONE = MessageKey.chat("staff.clearchat.done");
    public static final MessageKey CLEARCHAT_CONFIRM = MessageKey.chat("staff.clearchat.confirm", "count");

    private StaffMessages() {
    }
}
