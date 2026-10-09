package net.siftvanilla.siftcore.feature.displays;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of /displays ({@code lang/displays.yml}). What displays show is in features/displays.yml. */
public final class DisplaysMessages {

    public static final MessageKey CREATED = MessageKey.success("displays.created", "id", "where");
    public static final MessageKey MOVED = MessageKey.success("displays.moved", "id", "where");
    public static final MessageKey DELETED = MessageKey.success("displays.deleted", "id");
    public static final MessageKey RESET = MessageKey.success("displays.reset", "id");
    public static final MessageKey HIDDEN = MessageKey.success("displays.hidden", "id");
    public static final MessageKey REFRESHED = MessageKey.success("displays.refreshed", "count");
    public static final MessageKey INVALID_ID = MessageKey.error("displays.invalid-id");
    public static final MessageKey UNKNOWN = MessageKey.error("displays.unknown", "id");
    public static final MessageKey UNKNOWN_TEMPLATE = MessageKey.error("displays.unknown-template", "template");
    public static final MessageKey UNKNOWN_WORLD = MessageKey.error("displays.unknown-world", "world");
    public static final MessageKey EXISTS = MessageKey.error("displays.exists", "id");
    public static final MessageKey IN_CONFIG = MessageKey.error("displays.in-config", "id");
    public static final MessageKey CONFIG_ONLY = MessageKey.error("displays.config-only", "id");
    public static final MessageKey NOT_PLACED = MessageKey.error("displays.not-placed", "id");
    public static final MessageKey BUSY = MessageKey.error("displays.busy", "id");
    public static final MessageKey FAILED = MessageKey.error("displays.failed", "id");
    public static final MessageKey CONFIRM_CONSOLE = MessageKey.info("displays.confirm-console", "id");
    public static final MessageKey CLICK_UNAVAILABLE = MessageKey.error("displays.click-unavailable");
    public static final MessageKey WHERE = MessageKey.ui("displays.where", "world", "x", "y", "z");

    public static final MessageKey LIST_TITLE = MessageKey.ui("displays.list.title");
    public static final MessageKey LIST_HEADER = MessageKey.chat("displays.list.header", "count");
    public static final MessageKey LIST_LINE = MessageKey.chat("displays.list.line", "id", "template", "where", "state");
    public static final MessageKey LIST_LINE_UNPLACED = MessageKey.chat("displays.list.line-unplaced", "id", "template");
    public static final MessageKey LIST_EMPTY = MessageKey.chat("displays.list.empty");
    public static final MessageKey LIST_GO = MessageKey.ui("displays.list.go", "id");
    public static final MessageKey LIST_GO_TOOLTIP = MessageKey.ui("displays.list.go-tooltip");
    /** A display without a position yet, and how to place it. */
    public static final MessageKey LIST_UNPLACED = MessageKey.ui("displays.list.unplaced", "id");
    public static final MessageKey LIST_PLACE_TOOLTIP = MessageKey.ui("displays.list.place-tooltip", "id");

    public static final MessageKey STATE_SHOWN = MessageKey.ui("displays.state.shown");
    public static final MessageKey STATE_MISSING = MessageKey.ui("displays.state.missing");
    public static final MessageKey STATE_NOT_LOADED = MessageKey.ui("displays.state.not-loaded");
    public static final MessageKey STATE_NO_TEMPLATE = MessageKey.ui("displays.state.no-template");
    public static final MessageKey STATE_NO_WORLD = MessageKey.ui("displays.state.no-world");

    public static final MessageKey DELETE_TITLE = MessageKey.ui("displays.delete.title");
    public static final MessageKey DELETE_BODY = MessageKey.ui("displays.delete.body", "id", "template", "where");
    public static final MessageKey RESET_BODY = MessageKey.ui("displays.delete.reset-body", "id");
    public static final MessageKey HIDE_BODY = MessageKey.ui("displays.delete.hide-body", "id");
    public static final MessageKey DELETE_BUTTON = MessageKey.ui("displays.delete.button");
    public static final MessageKey RESET_BUTTON = MessageKey.ui("displays.delete.reset-button");

    /** The player's switch in /settings (Display group). */
    public static final MessageKey SETTING_LABEL = MessageKey.ui("displays.setting.label");
    public static final MessageKey SETTING_DESCRIPTION = MessageKey.ui("displays.setting.description");

    private DisplaysMessages() {
    }
}
