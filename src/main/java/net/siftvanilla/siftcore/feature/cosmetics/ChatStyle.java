package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * How a player's text is coloured: not at all, in one colour, or in a two-colour gradient. Used for chat messages
 * and nicknames. Stored as text: empty, a vanilla colour name ({@code gold}), a hex colour ({@code #FFAA00}) or two
 * hex colours ({@code #FF6AD5:#B26BFF}). A vanilla colour is a basic style (Baron); hex colours and gradients are
 * premium (Tycoon). Pure and thread-safe.
 */
sealed interface ChatStyle {

    /** No colour: the text keeps the surrounding colour. */
    record None() implements ChatStyle {
    }

    /**
     * One colour. {@code vanilla} marks one of the 16 vanilla colours picked by name (a basic style); a hex colour is
     * premium even when it has the same value as a vanilla colour.
     */
    record Solid(TextColor color, boolean vanilla) implements ChatStyle {
    }

    /** A gradient from one colour to another across the whole text. */
    record Gradient(TextColor from, TextColor to) implements ChatStyle {
    }

    ChatStyle NONE = new None();

    /** A vanilla colour picked by name. */
    static Solid vanilla(NamedTextColor color) {
        return new Solid(color, true);
    }

    /** A hex colour. */
    static Solid hex(TextColor color) {
        return new Solid(color, false);
    }

    /** {@code #RRGGBB} in capitals. */
    static String hexText(TextColor color) {
        return String.format(Locale.ROOT, "#%06X", color.value() & 0xFFFFFF);
    }

    /** Parses a stored or typed style; returns null when it is not one. Accepts {@code #RGB} shorthand too. */
    static ChatStyle parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none")) {
            return NONE;
        }
        String[] parts = trimmed.split("\\s*[:\\s,]\\s*");
        if (parts.length == 2) {
            TextColor from = hex(parts[0]);
            TextColor to = hex(parts[1]);
            if (from == null || to == null) {
                return null;
            }
            return from.value() == to.value() ? hex(from) : new Gradient(from, to);
        }
        if (parts.length != 1) {
            return null;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
        if (named != null) {
            return vanilla(named);
        }
        TextColor color = hex(trimmed);
        return color == null ? null : hex(color);
    }

    /** A hex colour such as {@code #FFAA00}, {@code FFAA00} or {@code #FA0}; null when it is not one. */
    static TextColor hex(String text) {
        String value = text.strip();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() == 3 && value.matches("[0-9a-fA-F]{3}")) {
            value = "" + value.charAt(0) + value.charAt(0) + value.charAt(1) + value.charAt(1) + value.charAt(2) + value.charAt(2);
        }
        if (!value.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return TextColor.color(Integer.parseInt(value, 16));
    }

    /** The stored form ({@code ""} for no style). */
    default String serialize() {
        return switch (this) {
            case None none -> "";
            case Solid solid -> solid.vanilla() ? NamedTextColor.NAMES.key((NamedTextColor) solid.color()) : hexText(solid.color());
            case Gradient gradient -> hexText(gradient.from()) + ":" + hexText(gradient.to());
        };
    }

    /** True for no style. */
    default boolean none() {
        return this instanceof None;
    }

    /** True for hex colours and gradients (the premium styles); vanilla colours and no style are not. */
    default boolean premium() {
        return switch (this) {
            case None none -> false;
            case Solid solid -> !solid.vanilla();
            case Gradient gradient -> true;
        };
    }

    /** The first colour, or null for no style. */
    default TextColor first() {
        return switch (this) {
            case None none -> null;
            case Solid solid -> solid.color();
            case Gradient gradient -> gradient.from();
        };
    }

    /** The colour of character {@code index} of {@code length}; null for no style. */
    default TextColor colorAt(int index, int length) {
        return switch (this) {
            case None none -> null;
            case Solid solid -> solid.color();
            case Gradient gradient -> ColorRules.lerp(gradient.from(), gradient.to(), length <= 1 ? 0.0 : index / (double) (length - 1));
        };
    }

    /** Untrusted text as a component in this style (never parsed). */
    default Component apply(String text) {
        return switch (this) {
            case None none -> Component.text(text);
            case Solid solid -> Component.text(text, solid.color());
            case Gradient gradient -> {
                List<Component> parts = runs(this, text, 0, text.codePointCount(0, text.length()));
                yield parts.size() == 1 ? parts.getFirst() : Component.text().append(parts).build();
            }
        };
    }

    /**
     * The text as a MiniMessage string in this style, for plugins that read MiniMessage (the tab list). The text is
     * escaped, so it never turns into tags.
     */
    default String miniMessage(String text) {
        String escaped = text.replace("\\", "\\\\").replace("<", "\\<");
        return switch (this) {
            case None none -> escaped;
            case Solid solid -> {
                String tag = solid.vanilla() ? NamedTextColor.NAMES.key((NamedTextColor) solid.color()) : hexText(solid.color());
                yield "<" + tag + ">" + escaped + "</" + tag + ">";
            }
            case Gradient gradient -> "<gradient:" + hexText(gradient.from()) + ":" + hexText(gradient.to()) + ">" + escaped + "</gradient>";
        };
    }

    /**
     * A chat message in this style. Parts showing an item ({@code [item]}) keep their own look and get
     * {@code itemColor} where they have none; everything else is coloured, a gradient running across all the typed
     * characters. No style returns the message unchanged.
     */
    default Component paint(Component message, TextColor itemColor) {
        return switch (this) {
            case None none -> message;
            case Solid solid -> solidPaint(message, solid.color(), itemColor);
            case Gradient gradient -> new GradientPainter(this, typedLength(message), itemColor).paint(message);
        };
    }

    // ------------------------------------------------------------------ helpers

    private static boolean showsItem(Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        return hover != null && hover.action() == HoverEvent.Action.SHOW_ITEM;
    }

    private static Component solidPaint(Component component, TextColor color, TextColor itemColor) {
        if (showsItem(component)) {
            return component.colorIfAbsent(itemColor);
        }
        Component result = component.colorIfAbsent(color);
        if (component.children().isEmpty()) {
            return result;
        }
        List<Component> children = new ArrayList<>(component.children().size());
        for (Component child : component.children()) {
            children.add(showsItem(child) ? child.colorIfAbsent(itemColor) : child);
        }
        return result.children(children);
    }

    /** Characters a gradient runs over: the text of every part except the ones showing an item. */
    static int typedLength(Component component) {
        if (showsItem(component)) {
            return 0;
        }
        int length = component instanceof TextComponent text ? text.content().codePointCount(0, text.content().length()) : 0;
        for (Component child : component.children()) {
            length += typedLength(child);
        }
        return length;
    }

    /** Coloured runs of {@code text}, which starts at character {@code offset} of a {@code length} long gradient. */
    private static List<Component> runs(ChatStyle style, String text, int offset, int length) {
        List<Component> parts = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        TextColor runColor = null;
        int index = offset;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            TextColor color = style.colorAt(index, length);
            if (runColor != null && color.value() != runColor.value()) {
                parts.add(Component.text(run.toString(), runColor));
                run.setLength(0);
            }
            runColor = color;
            run.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
            index++;
        }
        if (!run.isEmpty()) {
            parts.add(Component.text(run.toString(), runColor));
        }
        return parts;
    }

    /** Walks a message once, handing each typed character its place in the gradient. */
    final class GradientPainter {

        private final ChatStyle style;
        private final int length;
        private final TextColor itemColor;
        private int index;

        GradientPainter(ChatStyle style, int length, TextColor itemColor) {
            this.style = style;
            this.length = length;
            this.itemColor = itemColor;
        }

        Component paint(Component component) {
            if (showsItem(component)) {
                return component.colorIfAbsent(this.itemColor);
            }
            List<Component> children = new ArrayList<>();
            Component base = component;
            if (component instanceof TextComponent text && !text.content().isEmpty()) {
                int count = text.content().codePointCount(0, text.content().length());
                children.addAll(runs(this.style, text.content(), this.index, this.length));
                this.index += count;
                base = text.content("");
            }
            for (Component child : component.children()) {
                children.add(paint(child));
            }
            return base.children(children);
        }
    }
}
