package net.siftvanilla.siftcore.core.text;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * The whole colour system: primary text, secondary text, and the money colour (used for money amounts only).
 * Lang strings use them as {@code <primary>}, {@code <secondary>} and {@code <money>}.
 */
public record Palette(TextColor primary, TextColor secondary, TextColor money) {

    public static Palette defaults() {
        return new Palette(NamedTextColor.WHITE, NamedTextColor.GRAY, TextColor.color(0x1AFF1A));
    }
}
