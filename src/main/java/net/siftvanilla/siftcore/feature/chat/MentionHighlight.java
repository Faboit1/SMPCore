package net.siftvanilla.siftcore.feature.chat;

import net.kyori.adventure.text.format.TextDecoration;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * How a player's own name stands out in public chat lines that mention them: the values of the
 * {@code mention-highlight} setting. Never a colour alone (design system): bold or underlined text reads the same
 * for every eye.
 */
public enum MentionHighlight {
    BOLD("bold", ChatMessages.HIGHLIGHT_BOLD, TextDecoration.BOLD),
    UNDERLINE("underline", ChatMessages.HIGHLIGHT_UNDERLINE, TextDecoration.UNDERLINED),
    OFF("off", ChatMessages.HIGHLIGHT_OFF, null);

    private final String id;
    private final MessageKey label;
    private final TextDecoration decoration;

    MentionHighlight(String id, MessageKey label, TextDecoration decoration) {
        this.id = id;
        this.label = label;
        this.decoration = decoration;
    }

    /** The stored id. */
    public String id() {
        return this.id;
    }

    MessageKey label() {
        return this.label;
    }

    /** The decoration the name gets, or null for {@link #OFF}. */
    public TextDecoration decoration() {
        return this.decoration;
    }
}
