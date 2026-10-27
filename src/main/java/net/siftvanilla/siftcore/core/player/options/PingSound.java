package net.siftvanilla.siftcore.core.player.options;

import java.util.Locale;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * The sound of a personal ping (a mention, a private message, team chat): the shared vocabulary of the ping-sound
 * settings, played by {@code Sounds#ping}. {@link #DEFAULT} is the server's notify sound and follows the player's
 * "Notification pings" switch; the named sounds are an explicit choice and play even with that switch off.
 */
public enum PingSound {
    DEFAULT(OptionTexts.PING_DEFAULT),
    BELL(OptionTexts.PING_BELL),
    PLING(OptionTexts.PING_PLING),
    CHIME(OptionTexts.PING_CHIME),
    OFF(OptionTexts.PING_OFF);

    private final MessageKey label;

    PingSound(MessageKey label) {
        this.label = label;
    }

    /** The stored id. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public MessageKey label() {
        return this.label;
    }
}
