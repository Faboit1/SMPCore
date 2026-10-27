package net.siftvanilla.siftcore.core.player.options;

import java.util.Locale;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Where a notification shows: the shared vocabulary of every "how am I told" setting. A setting offers the styles
 * that make sense for it ({@link Choices#alert}); {@code Messenger.alert} delivers them.
 */
public enum AlertStyle {
    /** A chat line (kept in the chat history). */
    CHAT(OptionTexts.ALERT_CHAT),
    /** Above the hotbar (transient). */
    ACTIONBAR(OptionTexts.ALERT_ACTIONBAR),
    /** A title in the middle of the screen. */
    TITLE(OptionTexts.ALERT_TITLE),
    /** A boss bar at the top of the screen (for countdowns and other lasting status). */
    BOSSBAR(OptionTexts.ALERT_BOSSBAR),
    /** Two places at once; which two depends on the setting (chat and hotbar, or hotbar and boss bar). */
    BOTH(OptionTexts.ALERT_BOTH),
    /** Not shown at all. */
    OFF(OptionTexts.ALERT_OFF);

    private final MessageKey label;

    AlertStyle(MessageKey label) {
        this.label = label;
    }

    /** The stored id: {@code chat}, {@code actionbar}, {@code title}, {@code bossbar}, {@code both} or {@code off}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public MessageKey label() {
        return this.label;
    }

    /** Whether the style includes a chat line. */
    public boolean chat() {
        return this == CHAT || this == BOTH;
    }
}
