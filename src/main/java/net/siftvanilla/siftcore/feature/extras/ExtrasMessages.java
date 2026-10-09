package net.siftvanilla.siftcore.feature.extras;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the small quality-of-life commands ({@code lang/extras.yml}). */
public final class ExtrasMessages {

    public static final MessageKey RULES_TITLE = MessageKey.ui("extras.rules.title");
    public static final MessageKey RULES_BODY = MessageKey.ui("extras.rules.body");
    public static final MessageKey RULES_BUTTON = MessageKey.ui("extras.rules.button");
    public static final MessageKey RULES_LABEL = MessageKey.ui("extras.rules.label");
    public static final MessageKey RULES_DESCRIPTION = MessageKey.ui("extras.rules.description");
    public static final MessageKey HELP_TITLE = MessageKey.ui("extras.help.title");
    public static final MessageKey HELP_BODY = MessageKey.ui("extras.help.body");
    public static final MessageKey HELP_MENU = MessageKey.ui("extras.help.menu");
    public static final MessageKey PING_SELF = MessageKey.info("extras.ping.self", "ping");
    public static final MessageKey PING_OTHER = MessageKey.info("extras.ping.other", "name", "ping");
    public static final MessageKey SEEN_ONLINE = MessageKey.chat("extras.seen.online", "name", "since");
    public static final MessageKey SEEN_OFFLINE = MessageKey.chat("extras.seen.offline", "name", "ago", "first");
    /** {@code /seen} of a player who keeps their last online time from the asker ({@code seen-privacy}). */
    public static final MessageKey SEEN_HIDDEN = MessageKey.chat("extras.seen.hidden", "name");
    public static final MessageKey SETTING_JOIN_LINES = MessageKey.ui("extras.settings.join-leave-messages");
    public static final MessageKey SETTING_JOIN_LINES_DESCRIPTION = MessageKey.ui("extras.settings.join-leave-messages-description");
    public static final MessageKey JOIN_LINES_FIRST = MessageKey.ui("extras.settings.options.first-joins");
    public static final MessageKey JOIN = MessageKey.ui("extras.join", "name");
    public static final MessageKey QUIT = MessageKey.ui("extras.quit", "name");
    public static final MessageKey FIRST_JOIN = MessageKey.ui("extras.first-join", "name", "number");

    private ExtrasMessages() {
    }
}
