package net.siftvanilla.siftcore.feature.crates;

import net.kyori.adventure.text.format.TextColor;

/**
 * A rarity tier rewards belong to.
 *
 * @param id       stable id (config key)
 * @param label    plain text shown in previews and results ({@code Rare})
 * @param audit    whether winning a reward of this rarity is written to the audit log
 * @param announce whether winning a reward of this rarity is announced in chat to everyone
 * @param color    the rarity's colour: the reward's name everywhere
 * @param glass    the item key of the glass the opening animation shows around a reward of this rarity, or null for
 *                 the stained glass pane nearest to {@code color}
 */
public record Rarity(String id, String label, boolean audit, boolean announce, TextColor color, String glass) {

    /** The colour of a rarity without one of its own. */
    public static final TextColor DEFAULT_COLOR = TextColor.color(0xFFFFFF);

    public Rarity {
        color = color == null ? DEFAULT_COLOR : color;
    }

    /** A rarity whose glass follows its colour. */
    public Rarity(String id, String label, boolean audit, boolean announce, TextColor color) {
        this(id, label, audit, announce, color, null);
    }

    /** A rarity in the default colour. */
    public Rarity(String id, String label, boolean audit, boolean announce) {
        this(id, label, audit, announce, DEFAULT_COLOR, null);
    }
}
