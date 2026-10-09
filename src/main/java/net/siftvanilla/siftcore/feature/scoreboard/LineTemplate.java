package net.siftvanilla.siftcore.feature.scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;

/**
 * A line of text from the lang file with {@code {name}} placeholders: a sidebar line, or the tab list header or
 * footer. The text is parsed once (by the lang system, with the design-system tags); rendering only swaps the
 * placeholders for their values. A value is inserted as plain text that keeps the style around the placeholder,
 * so a value (a team name chosen by a player) can never add formatting. Pure: no server calls.
 */
public final class LineTemplate {

    /** A placeholder: lowercase letters, digits and _ in braces, like {@code {balance}}. */
    static final Pattern TOKEN = Pattern.compile("\\{([a-z0-9_]{1,64})}");
    /** Shown for a placeholder nothing provides. */
    static final String MISSING = "-";
    /** Longest value inserted for one placeholder; longer values are cut. */
    static final int MAX_VALUE_LENGTH = 48;

    private final Component source;
    private final List<String> tokens;

    private LineTemplate(Component source, List<String> tokens) {
        this.source = source;
        this.tokens = List.copyOf(tokens);
    }

    /** Reads the distinct placeholders of a parsed line, in order of first use. */
    public static LineTemplate of(Component source) {
        Objects.requireNonNull(source);
        List<String> tokens = new ArrayList<>();
        collect(source, tokens);
        return new LineTemplate(source, tokens);
    }

    private static void collect(Component component, List<String> tokens) {
        if (component instanceof TextComponent text) {
            Matcher matcher = TOKEN.matcher(text.content());
            while (matcher.find()) {
                if (!tokens.contains(matcher.group(1))) {
                    tokens.add(matcher.group(1));
                }
            }
        }
        for (Component child : component.children()) {
            collect(child, tokens);
        }
    }

    public Component source() {
        return this.source;
    }

    /** The distinct placeholder names, in order of first use. */
    public List<String> tokens() {
        return this.tokens;
    }

    /**
     * Whether a line with these values is left out: a line disappears while one of its placeholders is empty (the
     * team line without a team, the combat line out of combat). {@code values} lines up with {@link #tokens()}.
     */
    public static boolean hidden(List<String> values) {
        for (String value : values) {
            if (value != null && value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The line with each placeholder replaced by its value. {@code values} lines up with {@link #tokens()}; a null
     * value (nothing provides that placeholder) shows as "-".
     */
    public Component render(List<String> values) {
        if (values.size() != this.tokens.size()) {
            throw new IllegalArgumentException("expected " + this.tokens.size() + " values, got " + values.size());
        }
        if (this.tokens.isEmpty()) {
            return this.source;
        }
        TextReplacementConfig replacement = TextReplacementConfig.builder()
            .match(TOKEN)
            .replacement((match, builder) -> {
                int index = this.tokens.indexOf(match.group(1));
                return index < 0 ? builder : builder.content(clean(values.get(index)));
            })
            .build();
        return this.source.replaceText(replacement);
    }

    /** A value as it is shown: "-" for none, on one line, cut to a sane length. */
    static String clean(String value) {
        if (value == null) {
            return MISSING;
        }
        String oneLine = value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
            ? value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ') : value;
        return oneLine.length() > MAX_VALUE_LENGTH ? oneLine.substring(0, MAX_VALUE_LENGTH) : oneLine;
    }
}
