package net.siftvanilla.siftcore.core.player.options;

import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Labels of the shared option vocabularies, written once in {@code lang/settings.yml} under {@code settings.options}
 * so every setting that uses an option calls it the same.
 */
public final class OptionTexts {

    public static final MessageKey ALERT_CHAT = MessageKey.ui("settings.options.alert.chat");
    public static final MessageKey ALERT_ACTIONBAR = MessageKey.ui("settings.options.alert.actionbar");
    public static final MessageKey ALERT_TITLE = MessageKey.ui("settings.options.alert.title");
    public static final MessageKey ALERT_BOSSBAR = MessageKey.ui("settings.options.alert.bossbar");
    public static final MessageKey ALERT_BOTH = MessageKey.ui("settings.options.alert.both");
    public static final MessageKey ALERT_OFF = MessageKey.ui("settings.options.alert.off");

    public static final MessageKey AUDIENCE_EVERYONE = MessageKey.ui("settings.options.audience.everyone");
    public static final MessageKey AUDIENCE_FRIENDS_TEAM = MessageKey.ui("settings.options.audience.friends-team");
    public static final MessageKey AUDIENCE_FRIENDS = MessageKey.ui("settings.options.audience.friends");
    public static final MessageKey AUDIENCE_NOBODY = MessageKey.ui("settings.options.audience.nobody");

    public static final MessageKey PING_DEFAULT = MessageKey.ui("settings.options.ping.default");
    public static final MessageKey PING_BELL = MessageKey.ui("settings.options.ping.bell");
    public static final MessageKey PING_PLING = MessageKey.ui("settings.options.ping.pling");
    public static final MessageKey PING_CHIME = MessageKey.ui("settings.options.ping.chime");
    public static final MessageKey PING_OFF = MessageKey.ui("settings.options.ping.off");

    public static final MessageKey CONFIRM_SERVER = MessageKey.ui("settings.options.confirm.server");
    public static final MessageKey CONFIRM_ALWAYS = MessageKey.ui("settings.options.confirm.always");
    public static final MessageKey CONFIRM_NEVER = MessageKey.ui("settings.options.confirm.never");

    public static final MessageKey ANNOUNCE_ALL = MessageKey.ui("settings.options.announce.all");
    public static final MessageKey ANNOUNCE_OFF = MessageKey.ui("settings.options.announce.off");

    /** A threshold preset: "From $10,000" (money) or "From 1,000" (other amounts). */
    public static final MessageKey FROM_AMOUNT = MessageKey.ui("settings.options.from", "amount");

    public static final MessageKey AUTO_NOBODY = MessageKey.ui("settings.options.auto-accept.nobody");
    public static final MessageKey AUTO_FAVOURITES = MessageKey.ui("settings.options.auto-accept.favourites");
    public static final MessageKey AUTO_ALL = MessageKey.ui("settings.options.auto-accept.all");
    public static final MessageKey AUTO_FRIENDS_TEAM = MessageKey.ui("settings.options.auto-accept.friends-team");

    private OptionTexts() {
    }
}
