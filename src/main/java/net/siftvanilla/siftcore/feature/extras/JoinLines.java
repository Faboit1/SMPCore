package net.siftvanilla.siftcore.feature.extras;

import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * Which join and leave lines of other players a player reads: the values of the {@code join-leave-messages} setting.
 * "Every line" covers every line the server shows (plain join and leave lines, rank and custom join lines of the
 * cosmetics, and the welcome of brand-new players); "new players only" keeps just the welcomes.
 */
public enum JoinLines {
    ALL("all", OptionTexts.ANNOUNCE_ALL),
    FIRST_JOINS("first-joins", ExtrasMessages.JOIN_LINES_FIRST),
    OFF("off", OptionTexts.ANNOUNCE_OFF);

    private final String id;
    private final MessageKey label;

    JoinLines(String id, MessageKey label) {
        this.id = id;
        this.label = label;
    }

    /** The stored id. */
    public String id() {
        return this.id;
    }

    MessageKey label() {
        return this.label;
    }

    /**
     * Whether a reader with this choice gets a line.
     *
     * @param welcome true for the welcome of a brand-new player, false for any other join or leave line
     */
    public boolean shows(boolean welcome) {
        return switch (this) {
            case ALL -> true;
            case FIRST_JOINS -> welcome;
            case OFF -> false;
        };
    }
}
