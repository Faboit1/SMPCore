package net.siftvanilla.siftcore.feature.settings;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the settings dialog and commands ({@code lang/settings.yml}). Group and option text is core's (same file). */
public final class SettingsMessages {

    // ---- the group list
    public static final MessageKey TITLE = MessageKey.ui("settings.title");
    public static final MessageKey GROUPS_INTRO = MessageKey.ui("settings.groups-intro");
    public static final MessageKey CHANGED_COUNT = MessageKey.ui("settings.changed-count", "changed", "total");
    public static final MessageKey GROUP_LINE = MessageKey.ui("settings.group-line", "sprite", "label", "description", "count");
    public static final MessageKey GROUP_TOOLTIP = MessageKey.ui("settings.group-tooltip", "description", "count", "changed");
    public static final MessageKey SEARCH_BUTTON = MessageKey.ui("settings.search-button");
    public static final MessageKey CHANGED_BUTTON = MessageKey.ui("settings.changed-button", "count");

    // ---- a page of settings
    public static final MessageKey GROUP_TITLE = MessageKey.ui("settings.group-title", "label");
    public static final MessageKey INTRO = MessageKey.ui("settings.intro");
    public static final MessageKey LINE = MessageKey.ui("settings.line", "label", "description");
    public static final MessageKey LINE_REJOIN = MessageKey.ui("settings.line-rejoin", "label", "description");
    public static final MessageKey REJOIN_NOTE = MessageKey.ui("settings.rejoin-note", "label");
    public static final MessageKey LOCKED_LINE = MessageKey.ui("settings.locked-line", "label", "value");
    public static final MessageKey PAGE = MessageKey.ui("settings.page", "page", "pages");
    public static final MessageKey PENDING = MessageKey.ui("settings.pending", "count");
    public static final MessageKey EMPTY = MessageKey.ui("settings.empty");
    public static final MessageKey SAVE = MessageKey.ui("settings.save");
    public static final MessageKey NEXT = MessageKey.ui("settings.next");
    public static final MessageKey PREVIOUS = MessageKey.ui("settings.previous");
    public static final MessageKey RESET_BUTTON = MessageKey.ui("settings.reset-button");

    // ---- saving
    public static final MessageKey SAVED_ONE = MessageKey.success("settings.saved-one", "label", "state");
    public static final MessageKey SAVED_VALUE = MessageKey.success("settings.saved-value", "label", "value");
    public static final MessageKey SAVED_MANY = MessageKey.success("settings.saved-many", "count");
    public static final MessageKey REFUSED = MessageKey.error("settings.refused", "label");
    public static final MessageKey UNCHANGED = MessageKey.info("settings.unchanged");

    // ---- resetting
    public static final MessageKey RESET_TITLE = MessageKey.ui("settings.reset.title", "label");
    public static final MessageKey RESET_ALL_TITLE = MessageKey.ui("settings.reset.all-title");
    public static final MessageKey RESET_INTRO = MessageKey.ui("settings.reset.intro");
    public static final MessageKey RESET_LINE = MessageKey.ui("settings.reset.line", "label", "value", "default");
    public static final MessageKey RESET_MORE = MessageKey.ui("settings.reset.more", "count");
    public static final MessageKey RESET_CONFIRM = MessageKey.ui("settings.reset.confirm");
    public static final MessageKey RESET_ONE = MessageKey.success("settings.reset.done-one", "label");
    public static final MessageKey RESET_MANY = MessageKey.success("settings.reset.done-many", "count");
    public static final MessageKey RESET_NOTHING = MessageKey.info("settings.reset.nothing", "label");
    public static final MessageKey RESET_NOTHING_ALL = MessageKey.info("settings.reset.nothing-all");

    // ---- search
    public static final MessageKey SEARCH_TITLE = MessageKey.ui("settings.search.title");
    public static final MessageKey SEARCH_INTRO = MessageKey.ui("settings.search.intro");
    public static final MessageKey SEARCH_INPUT = MessageKey.ui("settings.search.input");
    public static final MessageKey SEARCH_SUBMIT = MessageKey.ui("settings.search.submit");
    public static final MessageKey SEARCH_EMPTY = MessageKey.ui("settings.search.empty");
    public static final MessageKey SEARCH_NONE = MessageKey.ui("settings.search.none", "query");
    public static final MessageKey SEARCH_RESULTS_TITLE = MessageKey.ui("settings.search.results-title", "query");
    public static final MessageKey SEARCH_FOUND = MessageKey.ui("settings.search.found", "count", "query");
    public static final MessageKey SEARCH_LINE = MessageKey.ui("settings.search.line", "group", "label", "description");
    public static final MessageKey SEARCH_LINE_SHORT = MessageKey.ui("settings.search.line-short", "group", "label");
    public static final MessageKey SEARCH_LOCKED_LINE = MessageKey.ui("settings.search.locked-line", "group", "label", "value");

    // ---- changed settings
    public static final MessageKey CHANGED_TITLE = MessageKey.ui("settings.changed.title");
    public static final MessageKey CHANGED_INTRO = MessageKey.ui("settings.changed.intro");
    public static final MessageKey CHANGED_NONE = MessageKey.ui("settings.changed.none");
    public static final MessageKey CHANGED_LINE = MessageKey.ui("settings.changed.line", "group", "label", "value", "default");
    public static final MessageKey CHANGED_GROUP_BUTTON = MessageKey.ui("settings.changed.group-button", "label", "count");
    public static final MessageKey RESET_ALL_BUTTON = MessageKey.ui("settings.changed.reset-all");

    // ---- /settings
    public static final MessageKey UNKNOWN_GROUP = MessageKey.error("settings.unknown-group", "name");
    public static final MessageKey UNKNOWN_SETTING = MessageKey.error("settings.command.unknown-setting", "name");
    public static final MessageKey UNKNOWN_WORD = MessageKey.error("settings.command.unknown-word", "name");
    public static final MessageKey UNKNOWN_IN_GROUP = MessageKey.error("settings.command.unknown-in-group", "name", "group");
    public static final MessageKey INFO = MessageKey.chat("settings.command.info", "label", "value", "default", "values");
    public static final MessageKey INFO_LOCKED = MessageKey.chat("settings.command.info-locked", "label", "value");
    public static final MessageKey INFO_HOVER = MessageKey.ui("settings.command.info-hover");
    public static final MessageKey ALREADY = MessageKey.info("settings.command.already", "label", "value");
    public static final MessageKey LOCKED = MessageKey.error("settings.command.locked", "label");
    public static final MessageKey NOT_OFFERED = MessageKey.error("settings.command.not-offered", "label", "value");
    public static final MessageKey INVALID_VALUE = MessageKey.error("settings.command.invalid", "label", "values");
    public static final MessageKey VALUES_TOGGLE = MessageKey.ui("settings.command.values-toggle");
    public static final MessageKey VALUES_RANGE = MessageKey.ui("settings.command.values-range", "min", "max");
    public static final MessageKey VALUES_STEPS = MessageKey.ui("settings.command.values-steps", "min", "max", "step");

    // ---- /sift settings
    public static final MessageKey ADMIN_HELP = MessageKey.chat("settings.admin.help");
    public static final MessageKey ADMIN_HEADER = MessageKey.chat("settings.admin.header", "name", "count");
    public static final MessageKey ADMIN_ROW = MessageKey.chat("settings.admin.row", "id", "value", "default");
    public static final MessageKey ADMIN_ROW_IGNORED = MessageKey.chat("settings.admin.row-ignored", "id", "value", "reason");
    public static final MessageKey ADMIN_NONE = MessageKey.chat("settings.admin.none", "name");
    public static final MessageKey ADMIN_OTHER = MessageKey.chat("settings.admin.other", "keys");
    public static final MessageKey ADMIN_DETAIL = MessageKey.chat("settings.admin.detail", "id", "name", "value", "default", "source");
    public static final MessageKey ADMIN_DETAIL_GROUP = MessageKey.chat("settings.admin.detail-group", "group", "kind", "values");
    public static final MessageKey ADMIN_DETAIL_PERMISSION = MessageKey.chat("settings.admin.detail-permission", "permission", "has");
    public static final MessageKey ADMIN_SET = MessageKey.chat("settings.admin.set", "id", "name", "value", "old");
    public static final MessageKey ADMIN_UNCHANGED = MessageKey.chat("settings.admin.unchanged", "id", "name", "value");
    public static final MessageKey ADMIN_REFUSED = MessageKey.error("settings.admin.refused", "id", "reason");
    public static final MessageKey ADMIN_NO_PERMISSION = MessageKey.chat("settings.admin.no-permission", "name", "permission");
    public static final MessageKey ADMIN_RESET = MessageKey.chat("settings.admin.reset", "count", "name");
    public static final MessageKey ADMIN_RESET_LOCKED = MessageKey.chat("settings.admin.reset-locked", "count", "name", "locked", "ids");
    public static final MessageKey ADMIN_UNKNOWN = MessageKey.error("settings.admin.unknown", "name");
    public static final MessageKey ADMIN_FAILED = MessageKey.error("settings.admin.failed", "reason");
    public static final MessageKey ADMIN_INVALID = MessageKey.error("settings.admin.invalid", "id", "values");
    public static final MessageKey ADMIN_CATALOG = MessageKey.chat("settings.admin.catalog", "count", "file");
    public static final MessageKey SOURCE_STORED = MessageKey.ui("settings.admin.source-stored");
    public static final MessageKey SOURCE_SERVER = MessageKey.ui("settings.admin.source-server");
    public static final MessageKey SOURCE_BUILT_IN = MessageKey.ui("settings.admin.source-built-in");
    public static final MessageKey SOURCE_LOCKED = MessageKey.ui("settings.admin.source-locked");
    public static final MessageKey SOURCE_HIDDEN = MessageKey.ui("settings.admin.source-hidden");
    public static final MessageKey REASON_LOCKED = MessageKey.ui("settings.admin.reason-locked");
    public static final MessageKey REASON_HIDDEN = MessageKey.ui("settings.admin.reason-hidden");
    public static final MessageKey REASON_CANCELLED = MessageKey.ui("settings.admin.reason-cancelled");
    public static final MessageKey HAS_YES = MessageKey.ui("settings.admin.has-yes");
    public static final MessageKey HAS_NO = MessageKey.ui("settings.admin.has-no");
    public static final MessageKey HAS_OFFLINE = MessageKey.ui("settings.admin.has-offline");

    // ---- the main menu
    public static final MessageKey HUB_LABEL = MessageKey.ui("settings.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("settings.hub.description");

    private SettingsMessages() {
    }
}
