package net.siftvanilla.siftcore.feature.settings;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the settings dialog ({@code lang/settings.yml}). */
public final class SettingsMessages {

    public static final MessageKey TITLE = MessageKey.ui("settings.title");
    public static final MessageKey GROUPS_INTRO = MessageKey.ui("settings.groups-intro");
    public static final MessageKey GROUP_LINE = MessageKey.ui("settings.group-line", "label", "description");
    public static final MessageKey GROUP_TITLE = MessageKey.ui("settings.group-title", "label");
    public static final MessageKey INTRO = MessageKey.ui("settings.intro");
    public static final MessageKey LINE = MessageKey.ui("settings.line", "label", "description");
    public static final MessageKey PAGE = MessageKey.ui("settings.page", "page", "pages");
    public static final MessageKey PENDING = MessageKey.ui("settings.pending", "count");
    public static final MessageKey EMPTY = MessageKey.ui("settings.empty");
    public static final MessageKey SAVE = MessageKey.ui("settings.save");
    public static final MessageKey NEXT = MessageKey.ui("settings.next");
    public static final MessageKey PREVIOUS = MessageKey.ui("settings.previous");
    public static final MessageKey SAVED_ONE = MessageKey.success("settings.saved-one", "label", "state");
    public static final MessageKey SAVED_MANY = MessageKey.success("settings.saved-many", "count");
    public static final MessageKey UNCHANGED = MessageKey.info("settings.unchanged");
    public static final MessageKey UNKNOWN_GROUP = MessageKey.error("settings.unknown-group", "name");
    public static final MessageKey STATE_ON = MessageKey.ui("settings.state-on");
    public static final MessageKey STATE_OFF = MessageKey.ui("settings.state-off");
    public static final MessageKey GENERAL = MessageKey.ui("settings.general.label");
    public static final MessageKey GENERAL_DESCRIPTION = MessageKey.ui("settings.general.description");
    public static final MessageKey HUB_LABEL = MessageKey.ui("settings.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("settings.hub.description");

    private SettingsMessages() {
    }
}
