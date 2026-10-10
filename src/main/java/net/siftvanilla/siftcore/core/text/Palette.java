package net.siftvanilla.siftcore.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * The colour system. Lang strings use each colour as a tag:
 * <ul>
 *   <li>{@code <primary>}: main text (white), {@code <secondary>}: less important text (gray);</li>
 *   <li>{@code <money>}: money amounts only (green);</li>
 *   <li>{@code <error>}: warnings; every error message is shown in the error colours, its primary text in
 *       {@code error} and its secondary text in {@code errorSecondary}, whatever tags the text uses;</li>
 *   <li>{@code <shards>}: every shard amount and mention of shards (purple);</li>
 *   <li>{@code <on>} and {@code <off>}: the state of a switch, ON in green and OFF in red;</li>
 *   <li>{@code <accent>}: a value that is not money, shards or a switch (a chosen option, a number, a time).</li>
 * </ul>
 */
public record Palette(TextColor primary, TextColor secondary, TextColor money, TextColor error, TextColor errorSecondary,
                      TextColor shards, TextColor on, TextColor off, TextColor accent) {

    public static final TextColor DEFAULT_ERROR = TextColor.color(0xFF5555);
    public static final TextColor DEFAULT_ERROR_SECONDARY = TextColor.color(0xFF9E9E);
    /** The owner's shard colour (the TAB sidebar's). */
    public static final TextColor DEFAULT_SHARDS = TextColor.color(0x915DFF);
    public static final TextColor DEFAULT_ON = TextColor.color(0x55FF55);
    public static final TextColor DEFAULT_OFF = TextColor.color(0xFF5555);
    public static final TextColor DEFAULT_ACCENT = TextColor.color(0xFFD866);

    public Palette {
        Objects.requireNonNull(primary);
        Objects.requireNonNull(secondary);
        Objects.requireNonNull(money);
        error = error == null ? DEFAULT_ERROR : error;
        errorSecondary = errorSecondary == null ? DEFAULT_ERROR_SECONDARY : errorSecondary;
        shards = shards == null ? DEFAULT_SHARDS : shards;
        on = on == null ? DEFAULT_ON : on;
        off = off == null ? DEFAULT_OFF : off;
        accent = accent == null ? DEFAULT_ACCENT : accent;
    }

    /** The text, money and error colours; shards, switch and accent colours are the defaults. */
    public Palette(TextColor primary, TextColor secondary, TextColor money, TextColor error, TextColor errorSecondary) {
        this(primary, secondary, money, error, errorSecondary, null, null, null, null);
    }

    public Palette(TextColor primary, TextColor secondary, TextColor money) {
        this(primary, secondary, money, DEFAULT_ERROR, DEFAULT_ERROR_SECONDARY);
    }

    public static Palette defaults() {
        return new Palette(NamedTextColor.WHITE, NamedTextColor.GRAY, TextColor.color(0x1AFF1A));
    }

    /** The colour of a switch's state: {@link #on()} or {@link #off()}. */
    public TextColor state(boolean on) {
        return on ? this.on : this.off;
    }

    /**
     * The same text in the error colours: uncoloured and primary parts become {@link #error()}, secondary parts
     * {@link #errorSecondary()}; money amounts and any other colour stay as they are.
     */
    public Component asError(Component text) {
        return recolor(text.colorIfAbsent(this.error));
    }

    private Component recolor(Component component) {
        Component result = component;
        TextColor color = component.color();
        if (color != null && color.value() == this.primary.value()) {
            result = result.color(this.error);
        } else if (color != null && color.value() == this.secondary.value()) {
            result = result.color(this.errorSecondary);
        }
        if (component.children().isEmpty()) {
            return result;
        }
        List<Component> children = new ArrayList<>(component.children().size());
        for (Component child : component.children()) {
            children.add(recolor(child));
        }
        return result.children(children);
    }
}
