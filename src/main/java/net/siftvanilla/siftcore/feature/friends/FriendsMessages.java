package net.siftvanilla.siftcore.feature.friends;

import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Every text of the friends system ({@code lang/friends.yml}). */
public final class FriendsMessages {

    public static final MessageKey HUB_LABEL = MessageKey.ui("friends.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("friends.hub.description");

    public static final MessageKey LOADING = MessageKey.info("friends.loading");
    public static final MessageKey BUSY = MessageKey.info("friends.busy");
    public static final MessageKey CANCELLED = MessageKey.error("friends.cancelled");
    public static final MessageKey NOT_FRIENDS = MessageKey.error("friends.not-friends", "name");
    public static final MessageKey ALREADY_FRIENDS = MessageKey.info("friends.already-friends", "name");
    public static final MessageKey INVALID_NAME = MessageKey.error("friends.invalid-name");

    // ------------------------------------------------------------------ requests

    public static final MessageKey REQUEST_SENT = MessageKey.success("friends.request.sent", "name");
    public static final MessageKey REQUEST_SELF = MessageKey.error("friends.request.self");
    public static final MessageKey REQUEST_VANISHED = MessageKey.error("friends.request.vanished");
    public static final MessageKey REQUEST_TOO_NEW = MessageKey.error("friends.request.too-new", "time");
    public static final MessageKey REQUEST_SLOW_DOWN = MessageKey.error("friends.request.slow-down", "time");
    public static final MessageKey REQUEST_ALREADY_SENT = MessageKey.error("friends.request.already-sent", "name");
    public static final MessageKey REQUEST_OUTGOING_FULL = MessageKey.error("friends.request.outgoing-full", "count");
    public static final MessageKey REQUEST_DAILY_CAP = MessageKey.error("friends.request.daily-cap");
    public static final MessageKey REQUEST_SENDER_FULL = MessageKey.error("friends.request.sender-full", "limit");
    public static final MessageKey REQUEST_SENDER_FULL_MAX = MessageKey.error("friends.request.sender-full-max", "limit");
    public static final MessageKey REQUEST_TARGET_FULL = MessageKey.error("friends.request.target-full", "name");
    public static final MessageKey REQUEST_PRIVATE = MessageKey.error("friends.request.private", "name");
    public static final MessageKey REQUEST_GONE = MessageKey.error("friends.request.gone", "name");
    public static final MessageKey REQUEST_NONE = MessageKey.info("friends.request.no-requests");
    public static final MessageKey REQUEST_DENIED = MessageKey.success("friends.request.denied", "name");
    public static final MessageKey REQUEST_DENIED_ALL = MessageKey.success("friends.request.denied-all", "count");
    public static final MessageKey REQUEST_CANCELLED = MessageKey.success("friends.request.cancelled", "name");
    public static final MessageKey REQUEST_NOT_SENT = MessageKey.error("friends.request.not-sent", "name");
    public static final MessageKey ACCEPT_VANISHED = MessageKey.error("friends.request.accept-vanished");

    // ------------------------------------------------------------------ chat notifications

    public static final MessageKey ALERT_REQUEST = MessageKey.notify("friends.alert.request", "name", "accept", "deny");
    public static final MessageKey ALERT_REQUESTS = MessageKey.notify("friends.alert.requests", "count", "view");
    public static final MessageKey ALERT_ACCEPTED = MessageKey.chat("friends.alert.accepted", "name").withFeedback(Feedback.SUCCESS);
    public static final MessageKey ALERT_NOW_FRIENDS = MessageKey.chat("friends.alert.now-friends", "name").withFeedback(Feedback.SUCCESS);
    public static final MessageKey ALERT_ONLINE = MessageKey.chat("friends.alert.online", "name");
    public static final MessageKey ALERT_ONLINE_MANY = MessageKey.chat("friends.alert.online-many", "names");
    public static final MessageKey ALERT_OFFLINE = MessageKey.chat("friends.alert.offline", "name");

    public static final MessageKey SUMMARY_ONLINE = MessageKey.chat("friends.summary.online", "names");
    public static final MessageKey SUMMARY_REQUESTS = MessageKey.chat("friends.summary.requests", "count", "view");
    public static final MessageKey SUMMARY_NEW_FRIENDS = MessageKey.chat("friends.summary.new-friends", "names");

    public static final MessageKey LINK_ACCEPT = MessageKey.ui("friends.link.accept");
    public static final MessageKey LINK_ACCEPT_HOVER = MessageKey.ui("friends.link.accept-hover", "name");
    public static final MessageKey LINK_DENY = MessageKey.ui("friends.link.deny");
    public static final MessageKey LINK_DENY_HOVER = MessageKey.ui("friends.link.deny-hover", "name");
    public static final MessageKey LINK_VIEW = MessageKey.ui("friends.link.view");
    public static final MessageKey LINK_VIEW_HOVER = MessageKey.ui("friends.link.view-hover");
    public static final MessageKey LINK_PROFILE_HOVER = MessageKey.ui("friends.link.profile-hover", "name");
    public static final MessageKey LINK_COMMAND = MessageKey.chat("friends.link.command", "command");
    public static final MessageKey LINK_COMMAND_HOVER = MessageKey.ui("friends.link.command-hover");

    public static final MessageKey NAMES_AND = MessageKey.ui("friends.names.and");
    public static final MessageKey NAMES_MORE = MessageKey.ui("friends.names.more", "count");

    public static final MessageKey STATUS_ONLINE = MessageKey.ui("friends.status.online");
    public static final MessageKey STATUS_AFK = MessageKey.ui("friends.status.afk");
    public static final MessageKey STATUS_SEEN = MessageKey.ui("friends.status.seen", "ago");
    /** Offline, when the friend doesn't show the viewer when they were last online ({@code seen-privacy}). */
    public static final MessageKey STATUS_OFFLINE = MessageKey.ui("friends.status.offline");

    // ------------------------------------------------------------------ list dialog and chat list

    public static final MessageKey LIST_TITLE = MessageKey.ui("friends.list.title");
    public static final MessageKey LIST_SUMMARY = MessageKey.ui("friends.list.summary", "online", "total", "limit");
    public static final MessageKey LIST_FULL = MessageKey.ui("friends.list.full");
    public static final MessageKey PAGE = MessageKey.ui("friends.page", "page", "pages");
    public static final MessageKey LIST_EMPTY = MessageKey.ui("friends.list.empty");
    public static final MessageKey LIST_FILTERED = MessageKey.ui("friends.list.filtered", "query", "count");
    public static final MessageKey LIST_ROW = MessageKey.ui("friends.list.row", "name", "status");
    public static final MessageKey LIST_TOOLTIP_FAVOURITE = MessageKey.ui("friends.list.tooltip-favourite");
    public static final MessageKey LIST_TOOLTIP_SINCE = MessageKey.ui("friends.list.tooltip-since", "date");
    public static final MessageKey LIST_TOOLTIP_NOTE = MessageKey.ui("friends.list.tooltip-note", "note");
    public static final MessageKey LIST_ADD = MessageKey.ui("friends.list.add");
    public static final MessageKey LIST_REQUESTS = MessageKey.ui("friends.list.requests", "count");
    public static final MessageKey LIST_SETTINGS = MessageKey.ui("friends.list.settings");
    public static final MessageKey LIST_FIND = MessageKey.ui("friends.list.find");
    public static final MessageKey LIST_SHOW_ALL = MessageKey.ui("friends.list.show-all");
    public static final MessageKey LIST_PREVIOUS = MessageKey.ui("friends.list.previous");
    public static final MessageKey LIST_NEXT = MessageKey.ui("friends.list.next");
    public static final MessageKey LIST_CHAT_HEADER = MessageKey.chat("friends.list.chat-header", "total", "online", "page", "pages");
    public static final MessageKey LIST_CHAT_ROW = MessageKey.chat("friends.list.chat-row", "name", "status");
    public static final MessageKey LIST_CHAT_EMPTY = MessageKey.chat("friends.list.chat-empty");
    public static final MessageKey LIST_CHAT_PREVIOUS = MessageKey.ui("friends.list.chat-previous");
    public static final MessageKey LIST_CHAT_NEXT = MessageKey.ui("friends.list.chat-next");
    public static final MessageKey LIST_CHAT_PAGE_HOVER = MessageKey.ui("friends.list.chat-page-hover", "page");

    public static final MessageKey FIND_TITLE = MessageKey.ui("friends.find.title");
    public static final MessageKey FIND_INPUT = MessageKey.ui("friends.find.input");
    public static final MessageKey FIND_SUBMIT = MessageKey.ui("friends.find.submit");

    // ------------------------------------------------------------------ requests dialogs

    public static final MessageKey REQUESTS_TITLE = MessageKey.ui("friends.requests.title");
    public static final MessageKey REQUESTS_BODY = MessageKey.ui("friends.requests.body", "incoming", "sent");
    public static final MessageKey REQUESTS_INCOMING_ROW = MessageKey.ui("friends.requests.incoming-row", "name", "ago");
    public static final MessageKey REQUESTS_OUTGOING_ROW = MessageKey.ui("friends.requests.outgoing-row", "name", "ago");
    public static final MessageKey REQUESTS_TOOLTIP_MUTUAL = MessageKey.ui("friends.requests.tooltip-mutual", "count");
    public static final MessageKey REQUESTS_TOOLTIP_TEAM = MessageKey.ui("friends.requests.tooltip-team", "team");
    public static final MessageKey REQUESTS_TOOLTIP_CANCEL = MessageKey.ui("friends.requests.tooltip-cancel");
    public static final MessageKey REQUESTS_SENT_HINT = MessageKey.ui("friends.requests.sent-hint");
    public static final MessageKey REQUESTS_CANCEL_TITLE = MessageKey.ui("friends.requests.cancel-title");
    public static final MessageKey REQUESTS_CANCEL_BODY = MessageKey.ui("friends.requests.cancel-body", "name");
    public static final MessageKey REQUESTS_CANCEL_YES = MessageKey.ui("friends.requests.cancel-yes");
    public static final MessageKey REQUESTS_DENY_ALL = MessageKey.ui("friends.requests.deny-all");
    public static final MessageKey REQUESTS_DENY_ALL_TITLE = MessageKey.ui("friends.requests.deny-all-title");
    public static final MessageKey REQUESTS_DENY_ALL_BODY = MessageKey.ui("friends.requests.deny-all-body", "count");
    public static final MessageKey REQUESTS_DENY_ALL_YES = MessageKey.ui("friends.requests.deny-all-yes");
    public static final MessageKey REQUESTS_PREVIOUS = MessageKey.ui("friends.requests.previous");
    public static final MessageKey REQUESTS_NEXT = MessageKey.ui("friends.requests.next");

    public static final MessageKey REQUEST_VIEW_TITLE = MessageKey.ui("friends.request-view.title");
    public static final MessageKey REQUEST_VIEW_WANTS = MessageKey.ui("friends.request-view.wants", "name");
    public static final MessageKey REQUEST_VIEW_MUTUAL = MessageKey.ui("friends.request-view.mutual", "count");
    public static final MessageKey REQUEST_VIEW_MUTUAL_NAMES = MessageKey.ui("friends.request-view.mutual-names", "count", "names");
    public static final MessageKey REQUEST_VIEW_TEAM = MessageKey.ui("friends.request-view.team", "team");
    public static final MessageKey REQUEST_VIEW_SENT = MessageKey.ui("friends.request-view.sent", "ago");
    public static final MessageKey REQUEST_VIEW_ACCEPT = MessageKey.ui("friends.request-view.accept");
    public static final MessageKey REQUEST_VIEW_DENY = MessageKey.ui("friends.request-view.deny");
    public static final MessageKey REQUEST_VIEW_DENY_IGNORE = MessageKey.ui("friends.request-view.deny-ignore");

    // ------------------------------------------------------------------ add dialog

    public static final MessageKey ADD_TITLE = MessageKey.ui("friends.add.title");
    public static final MessageKey ADD_BODY = MessageKey.ui("friends.add.body");
    public static final MessageKey ADD_BODY_EMPTY = MessageKey.ui("friends.add.body-empty");
    public static final MessageKey ADD_ENTER = MessageKey.ui("friends.add.enter");
    public static final MessageKey ADD_SUGGESTION = MessageKey.ui("friends.add.suggestion", "name", "count");
    public static final MessageKey ADD_SUGGESTION_ONE = MessageKey.ui("friends.add.suggestion-one", "name");
    public static final MessageKey ADD_SUGGESTION_TEAM = MessageKey.ui("friends.add.suggestion-team", "name");
    public static final MessageKey ADD_SUGGESTION_TOOLTIP = MessageKey.ui("friends.add.suggestion-tooltip", "name");
    public static final MessageKey ADD_FORM_TITLE = MessageKey.ui("friends.add.form-title");
    public static final MessageKey ADD_FORM_BODY = MessageKey.ui("friends.add.form-body");
    public static final MessageKey ADD_FORM_INPUT = MessageKey.ui("friends.add.form-input");
    public static final MessageKey ADD_FORM_SUBMIT = MessageKey.ui("friends.add.form-submit");

    // ------------------------------------------------------------------ profiles

    public static final MessageKey PROFILE_TITLE = MessageKey.ui("friends.profile.title", "name");
    public static final MessageKey PROFILE_STATUS_ONLINE = MessageKey.ui("friends.profile.status-online");
    public static final MessageKey PROFILE_STATUS_AFK = MessageKey.ui("friends.profile.status-afk");
    public static final MessageKey PROFILE_STATUS_SEEN = MessageKey.ui("friends.profile.status-seen", "ago");
    /** Offline, without the time ({@code seen-privacy} keeps it from the viewer). */
    public static final MessageKey PROFILE_STATUS_OFFLINE = MessageKey.ui("friends.profile.status-offline");
    public static final MessageKey PROFILE_SINCE = MessageKey.ui("friends.profile.since", "date");
    public static final MessageKey PROFILE_TEAM = MessageKey.ui("friends.profile.team", "team");
    public static final MessageKey PROFILE_RANK = MessageKey.ui("friends.profile.rank", "rank");
    public static final MessageKey PROFILE_MUTUAL = MessageKey.ui("friends.profile.mutual", "count");
    public static final MessageKey PROFILE_MUTUAL_NAMES = MessageKey.ui("friends.profile.mutual-names", "count", "names");
    public static final MessageKey PROFILE_NOTE = MessageKey.ui("friends.profile.note", "note");
    public static final MessageKey PROFILE_MESSAGE = MessageKey.ui("friends.profile.message");
    public static final MessageKey PROFILE_TELEPORT = MessageKey.ui("friends.profile.teleport");
    public static final MessageKey PROFILE_INVITE = MessageKey.ui("friends.profile.invite");
    public static final MessageKey PROFILE_PAY = MessageKey.ui("friends.profile.pay");
    public static final MessageKey PROFILE_STATS = MessageKey.ui("friends.profile.stats");
    public static final MessageKey PROFILE_FAVOURITE = MessageKey.ui("friends.profile.favourite");
    public static final MessageKey PROFILE_UNFAVOURITE = MessageKey.ui("friends.profile.unfavourite");
    public static final MessageKey PROFILE_EDIT_NOTE = MessageKey.ui("friends.profile.edit-note");
    public static final MessageKey PROFILE_REMOVE = MessageKey.ui("friends.profile.remove");
    public static final MessageKey PROFILE_ADD_FRIEND = MessageKey.ui("friends.profile.add-friend");
    public static final MessageKey PROFILE_ACCEPT_REQUEST = MessageKey.ui("friends.profile.accept-request");
    public static final MessageKey PROFILE_CANCEL_REQUEST = MessageKey.ui("friends.profile.cancel-request");
    public static final MessageKey PROFILE_MESSAGE_TITLE = MessageKey.ui("friends.profile.message-title", "name");
    public static final MessageKey PROFILE_MESSAGE_INPUT = MessageKey.ui("friends.profile.message-input");
    public static final MessageKey PROFILE_MESSAGE_SUBMIT = MessageKey.ui("friends.profile.message-submit");
    public static final MessageKey PROFILE_MUTED = MessageKey.error("friends.profile.muted");
    public static final MessageKey PROFILE_NOTE_TITLE = MessageKey.ui("friends.profile.note-title", "name");
    public static final MessageKey PROFILE_NOTE_BODY = MessageKey.ui("friends.profile.note-body");
    public static final MessageKey PROFILE_NOTE_INPUT = MessageKey.ui("friends.profile.note-input");
    public static final MessageKey PROFILE_NOTE_SUBMIT = MessageKey.ui("friends.profile.note-submit");
    public static final MessageKey PROFILE_NOTE_SAVED = MessageKey.success("friends.profile.note-saved", "name");
    public static final MessageKey PROFILE_NOTE_CLEARED = MessageKey.success("friends.profile.note-cleared", "name");
    public static final MessageKey PROFILE_REMOVE_TITLE = MessageKey.ui("friends.profile.remove-title");
    public static final MessageKey PROFILE_REMOVE_BODY = MessageKey.ui("friends.profile.remove-body", "name");
    public static final MessageKey PROFILE_REMOVE_YES = MessageKey.ui("friends.profile.remove-yes");
    public static final MessageKey PROFILE_REMOVED = MessageKey.success("friends.profile.removed", "name");
    public static final MessageKey PROFILE_FAVOURITED = MessageKey.success("friends.profile.favourited", "name");
    public static final MessageKey PROFILE_UNFAVOURITED = MessageKey.success("friends.profile.unfavourited", "name");
    public static final MessageKey PROFILE_FAVOURITES_FULL = MessageKey.error("friends.profile.favourites-full", "count");
    public static final MessageKey PROFILE_FAVOURITES_OFF = MessageKey.error("friends.profile.favourites-off");

    // ------------------------------------------------------------------ settings

    public static final MessageKey SETTINGS_SET = MessageKey.success("friends.settings.set", "setting", "value");
    public static final MessageKey SETTINGS_CURRENT = MessageKey.chat("friends.settings.current", "setting", "value");
    public static final MessageKey SETTINGS_UNKNOWN_KEY = MessageKey.error("friends.settings.unknown-key", "keys");
    public static final MessageKey SETTINGS_UNKNOWN_VALUE = MessageKey.error("friends.settings.unknown-value", "setting", "values");
    /** The server fixed the setting for everyone ({@code locked} in features/settings.yml). */
    public static final MessageKey SETTINGS_LOCKED = MessageKey.error("friends.settings.locked", "setting", "value");
    /** The server hides the setting ({@code hidden} in features/settings.yml), or it is not offered now. */
    public static final MessageKey SETTINGS_UNAVAILABLE = MessageKey.error("friends.settings.unavailable", "setting");
    /** Another plugin stopped the change. */
    public static final MessageKey SETTINGS_REFUSED = MessageKey.error("friends.settings.refused", "setting");
    /** The friends list's Settings button when the player can change none of the friends and teams settings. */
    public static final MessageKey SETTINGS_NONE = MessageKey.info("friends.settings.none");

    /** The friends settings in Settings, Friends &amp; teams: labels, descriptions and option names. */
    public static final MessageKey SETTING_REQUESTS = MessageKey.ui("friends.settings.requests");
    public static final MessageKey SETTING_REQUESTS_DESCRIPTION = MessageKey.ui("friends.settings.requests-description");
    public static final MessageKey SETTING_REQUESTS_KNOWN = MessageKey.ui("friends.settings.requests-known");
    public static final MessageKey SETTING_JOIN_ALERTS = MessageKey.ui("friends.settings.join-alerts");
    public static final MessageKey SETTING_JOIN_ALERTS_DESCRIPTION = MessageKey.ui("friends.settings.join-alerts-description");
    public static final MessageKey SETTING_JOIN_ALERTS_ALL = MessageKey.ui("friends.settings.join-alerts-all");
    public static final MessageKey SETTING_JOIN_ALERTS_FAVOURITES = MessageKey.ui("friends.settings.join-alerts-favourites");
    public static final MessageKey SETTING_REQUEST_ALERTS = MessageKey.ui("friends.settings.toggle-requests");
    public static final MessageKey SETTING_REQUEST_ALERTS_DESCRIPTION = MessageKey.ui("friends.settings.toggle-requests-description");
    public static final MessageKey SETTING_ANNOUNCE = MessageKey.ui("friends.settings.toggle-announce");
    public static final MessageKey SETTING_ANNOUNCE_DESCRIPTION = MessageKey.ui("friends.settings.toggle-announce-description");
    public static final MessageKey SETTING_JOIN_SUMMARY = MessageKey.ui("friends.settings.join-summary");
    public static final MessageKey SETTING_JOIN_SUMMARY_DESCRIPTION = MessageKey.ui("friends.settings.join-summary-description");
    public static final MessageKey SETTING_LEAVE_ALERTS = MessageKey.ui("friends.settings.toggle-leave");
    public static final MessageKey SETTING_LEAVE_ALERTS_DESCRIPTION = MessageKey.ui("friends.settings.toggle-leave-description");
    public static final MessageKey SETTING_LIST_ORDER = MessageKey.ui("friends.settings.list-order");
    public static final MessageKey SETTING_LIST_ORDER_DESCRIPTION = MessageKey.ui("friends.settings.list-order-description");
    public static final MessageKey SETTING_LIST_ORDER_STATUS = MessageKey.ui("friends.settings.list-order-status");
    public static final MessageKey SETTING_LIST_ORDER_NAME = MessageKey.ui("friends.settings.list-order-name");
    public static final MessageKey SETTING_LIST_ORDER_LAST_SEEN = MessageKey.ui("friends.settings.list-order-last-seen");
    public static final MessageKey SETTING_LIST_ORDER_OLDEST = MessageKey.ui("friends.settings.list-order-oldest");

    public static final MessageKey HELP = MessageKey.chat("friends.help");
    public static final MessageKey CONSOLE_HELP = MessageKey.chat("friends.console-help");

    // ------------------------------------------------------------------ staff

    public static final MessageKey STAFF_HELP = MessageKey.chat("friends.staff.help");
    public static final MessageKey STAFF_LIST_HEADER = MessageKey.chat("friends.staff.list-header", "name", "count", "limit");
    public static final MessageKey STAFF_LIST_ROW = MessageKey.chat("friends.staff.list-row", "friend", "date", "favourite");
    public static final MessageKey STAFF_LIST_FAVOURITE = MessageKey.ui("friends.staff.list-favourite");
    public static final MessageKey STAFF_REQUESTS_HEADER = MessageKey.chat("friends.staff.requests-header", "name", "count");
    public static final MessageKey STAFF_REQUESTS_ROW = MessageKey.chat("friends.staff.requests-row", "sender", "target", "state", "date", "decided");
    public static final MessageKey STAFF_REQUESTS_DECIDED = MessageKey.ui("friends.staff.requests-decided", "date");
    public static final MessageKey STAFF_HISTORY_HEADER = MessageKey.chat("friends.staff.history-header", "name", "page");
    public static final MessageKey STAFF_HISTORY_ROW = MessageKey.chat("friends.staff.history-row", "date", "player", "action", "other", "actor");
    public static final MessageKey STAFF_EMPTY = MessageKey.chat("friends.staff.empty");
    public static final MessageKey STAFF_ADDED = MessageKey.chat("friends.staff.added", "first", "second");
    public static final MessageKey STAFF_ALREADY_FRIENDS = MessageKey.chat("friends.staff.already-friends", "first", "second");
    public static final MessageKey STAFF_FULL = MessageKey.chat("friends.staff.full", "name", "cap");
    public static final MessageKey STAFF_SAME = MessageKey.chat("friends.staff.same");
    public static final MessageKey STAFF_REMOVED = MessageKey.chat("friends.staff.removed", "first", "second");
    public static final MessageKey STAFF_NOT_FRIENDS = MessageKey.chat("friends.staff.not-friends", "first", "second");
    public static final MessageKey STAFF_CLEARED = MessageKey.chat("friends.staff.cleared", "name", "count");
    public static final MessageKey STAFF_CANCELLED = MessageKey.chat("friends.staff.cancelled");
    public static final MessageKey STAFF_FAILED = MessageKey.chat("friends.staff.failed", "reason");

    private FriendsMessages() {
    }
}
