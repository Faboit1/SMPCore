package net.siftvanilla.siftcore.feature.spawn;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the spawn feature ({@code lang/spawn.yml}). */
public final class SpawnMessages {

    public static final MessageKey NOT_AVAILABLE = MessageKey.error("spawn.not-available");
    public static final MessageKey SET = MessageKey.chat("spawn.set", "world", "x", "y", "z");
    public static final MessageKey UNKNOWN_WORLD = MessageKey.error("spawn.unknown-world", "name");
    public static final MessageKey OUTSIDE_BORDER = MessageKey.error("spawn.outside-border", "world");
    public static final MessageKey SENT = MessageKey.chat("spawn.sent", "name");
    public static final MessageKey SENT_BY_STAFF = MessageKey.info("spawn.sent-by-staff");

    public static final MessageKey BUILD_DENIED = MessageKey.error("spawn.protection.build");
    public static final MessageKey USE_DENIED = MessageKey.error("spawn.protection.use");
    public static final MessageKey PVP_DENIED = MessageKey.error("spawn.protection.pvp");

    public static final MessageKey FLY_ON = MessageKey.success("spawn.fly.on");
    public static final MessageKey FLY_OFF = MessageKey.success("spawn.fly.off");
    public static final MessageKey FLY_OFF_LEFT = MessageKey.info("spawn.fly.off-left");
    public static final MessageKey FLY_OFF_LEFT_FALLING = MessageKey.info("spawn.fly.off-left-falling");
    public static final MessageKey FLY_OFF_COMBAT = MessageKey.error("spawn.fly.off-combat");
    public static final MessageKey FLY_OFF_OTHER = MessageKey.info("spawn.fly.off-other");
    public static final MessageKey FLY_OUTSIDE = MessageKey.error("spawn.fly.outside");
    public static final MessageKey FLY_IN_COMBAT = MessageKey.error("spawn.fly.in-combat", "time");
    public static final MessageKey FLY_GAME_MODE = MessageKey.info("spawn.fly.game-mode");
    public static final MessageKey FLY_DISABLED = MessageKey.error("spawn.fly.disabled");
    /** Flying above fly.max-height: flight drops them (it stays allowed lower down). */
    public static final MessageKey FLY_CEILING = MessageKey.info("spawn.fly.ceiling", "height");
    /** /fly above fly.max-height. */
    public static final MessageKey FLY_TOO_HIGH = MessageKey.error("spawn.fly.too-high", "height");

    public static final MessageKey WELCOME_TITLE = MessageKey.title("spawn.welcome.title");
    public static final MessageKey WELCOME_SUBTITLE = MessageKey.ui("spawn.welcome.subtitle", "name");
    public static final MessageKey WELCOME_CHAT = MessageKey.chat("spawn.welcome.chat");

    public static final MessageKey HUB_LABEL = MessageKey.ui("spawn.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("spawn.hub.description");

    private SpawnMessages() {
    }
}
