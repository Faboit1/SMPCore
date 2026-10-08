package net.siftvanilla.siftcore.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * The colour system: primary text, secondary text, the money colour (used for money amounts only) and the two error
 * colours. Lang strings use them as {@code <primary>}, {@code <secondary>}, {@code <money>} and {@code <error>}.
 * Every error message is shown in the error colours: its primary text in {@code error}, its secondary text in
 * {@code errorSecondary}, whatever tags the text uses.
 */
public record Palette(TextColor primary, TextColor secondary, TextColor money, TextColor error, TextColor errorSecondary) {

    public static final TextColor DEFAULT_ERROR = TextColor.color(0xFF5555);
    public static final TextColor DEFAULT_ERROR_SECONDARY = TextColor.color(0xFF9E9E);

    public Palette {
        Objects.requireNonNull(primary);
        Objects.requireNonNull(secondary);
        Objects.requireNonNull(money);
        error = error == null ? DEFAULT_ERROR : error;
        errorSecondary = errorSecondary == null ? DEFAULT_ERROR_SECONDARY : errorSecondary;
    }

    public Palette(TextColor primary, TextColor secondary, TextColor money) {
        this(primary, secondary, money, DEFAULT_ERROR, DEFAULT_ERROR_SECONDARY);
    }

    public static Palette defaults() {
        return new Palette(NamedTextColor.WHITE, NamedTextColor.GRAY, TextColor.color(0x1AFF1A));
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
