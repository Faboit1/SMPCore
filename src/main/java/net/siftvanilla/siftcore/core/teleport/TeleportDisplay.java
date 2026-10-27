package net.siftvanilla.siftcore.core.teleport;

import java.time.Duration;
import net.kyori.adventure.title.Title;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;

/**
 * Where a teleport's warmup countdown and arrival line show, by the player's {@code teleport-display} setting: the
 * pure rules of {@link Teleports}, unit tested. Cancel messages (moved, took damage, in combat, frozen) and failures are
 * not decided here: they always show.
 */
public final class TeleportDisplay {

    /** How one line is delivered. */
    public enum Place {
        /** A repeating status line above the hotbar (the countdown as it always was). */
        STATUS,
        /** The line's own channel: above the hotbar, or where the player's feedback channel puts results. */
        FEEDBACK,
        /** A title in the middle of the screen. */
        TITLE,
        /** One chat line. */
        CHAT,
        /** Not shown. */
        NONE
    }

    private TeleportDisplay() {
    }

    /**
     * Where one second of the warmup countdown goes. In chat only the first second shows (one line, not one per
     * second); above the hotbar and as a title every second updates the count.
     *
     * @param first whether this is the first second of the warmup
     */
    public static Place countdown(AlertStyle style, boolean first) {
        return switch (style) {
            case TITLE -> Place.TITLE;
            case CHAT -> first ? Place.CHAT : Place.NONE;
            case OFF -> Place.NONE;
            default -> Place.STATUS;
        };
    }

    /** How long one second of a title countdown stays: a little over the second, so the next one meets it. */
    static final Duration COUNTDOWN_STAY = Duration.ofMillis(1500);

    /**
     * The timing of one second of a title countdown. Only the first second fades in; the later ones replace the number
     * in place instead of fading it in again. Each stays a little over a second, so the count never blinks out between
     * two seconds, and the last one leaves on its own soon after the warmup (a cancel or failure clears it at once).
     */
    public static Title.Times countdownTimes(boolean first) {
        return Title.Times.times(first ? Duration.ofMillis(250) : Duration.ZERO, COUNTDOWN_STAY, Duration.ofMillis(200));
    }

    /**
     * Where the arrival line goes ("Teleported.", or a feature's own line such as a home's welcome).
     *
     * @param essential the line carries something the player must learn even with the display off (money paid for the
     *                  teleport): then "off" shows it in its usual place
     */
    public static Place arrival(AlertStyle style, boolean essential) {
        return switch (style) {
            case TITLE -> Place.TITLE;
            case CHAT -> Place.CHAT;
            case OFF -> essential ? Place.FEEDBACK : Place.NONE;
            default -> Place.FEEDBACK;
        };
    }
}
