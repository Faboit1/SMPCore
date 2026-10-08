package net.siftvanilla.siftcore.feature.displays;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.siftvanilla.siftcore.core.text.TextStyle;

/**
 * A template from displays.yml, compiled once per load: design-system MiniMessage lines with {@code {name}}
 * placeholders. Each placeholder becomes an internal tag, and rendering inserts the values with
 * {@link Placeholder#unparsed}, so a value (a player name on a leaderboard) is always shown literally and can never
 * add formatting. Rendering re-reads the current text style every time. Pure: no server calls.
 *
 * @param id           the template name
 * @param source       the lines as written
 * @param compiled     the lines with placeholders replaced by internal tags
 * @param placeholders the distinct placeholder names, in order of first use (index = internal tag number)
 */
public record DisplayTemplate(String id, List<String> source, List<String> compiled, List<String> placeholders) {

    /** Shown for a placeholder that has no value or no provider. */
    public static final String MISSING = "-";
    public static final int MAX_LINES = 40;
    public static final int MAX_LINE_LENGTH = 512;
    /** Longest value inserted for one placeholder; longer values are cut. */
    public static final int MAX_VALUE_LENGTH = 128;

    private static final Pattern TOKEN = Pattern.compile("\\{([^{}\\s]*)}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final String TAG = "sift_display_value_";

    public DisplayTemplate {
        source = List.copyOf(source);
        compiled = List.copyOf(compiled);
        placeholders = List.copyOf(placeholders);
    }

    /** Receives one problem: the 1-based line number (0 for the whole template) and what is wrong. */
    @FunctionalInterface
    public interface Problems {
        void report(int line, String message);
    }

    /**
     * Compiles a template. Every problem is reported (lines with problems are still compiled, with bad tags left as
     * text), so one pass lists every mistake.
     */
    public static DisplayTemplate compile(String id, List<String> lines, TextStyle style, Problems problems) {
        if (lines.isEmpty()) {
            problems.report(0, "has no lines");
        }
        if (lines.size() > MAX_LINES) {
            problems.report(0, "has " + lines.size() + " lines; the most a display can show is " + MAX_LINES);
        }
        List<String> names = new ArrayList<>();
        List<String> compiled = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int number = i + 1;
            if (line.length() > MAX_LINE_LENGTH) {
                problems.report(number, "is longer than " + MAX_LINE_LENGTH + " characters");
            }
            if (line.indexOf('§') >= 0) {
                problems.report(number, "uses § colour codes; use <primary> and <secondary> instead");
                line = line.replace('§', '&');
            }
            List<String> bad = style.findDisallowedTags(line, Set.of());
            if (!bad.isEmpty()) {
                problems.report(number, "uses tags that are not allowed: " + String.join(", ", bad)
                    + " (allowed: <primary> <secondary> <money> <icon:name> <!italic>)");
            }
            Matcher matcher = TOKEN.matcher(line);
            StringBuilder out = new StringBuilder(line.length() + 16);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!NAME.matcher(name).matches()) {
                    problems.report(number, "has {" + name + "}, but placeholder names use only lowercase letters, digits and _");
                    matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                    continue;
                }
                int index = names.indexOf(name);
                if (index < 0) {
                    names.add(name);
                    index = names.size() - 1;
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement("<" + TAG + index + ">"));
            }
            matcher.appendTail(out);
            compiled.add(out.toString());
        }
        return new DisplayTemplate(id, lines, compiled, names);
    }

    /**
     * Renders every line, joined with line breaks. {@code values} returns a placeholder's value, or null when
     * nothing provides it; null, a failing lookup and an over-long value never break the display.
     */
    public Component render(TextStyle style, Function<String, String> values) {
        TagResolver.Builder resolvers = TagResolver.builder();
        for (int i = 0; i < this.placeholders.size(); i++) {
            resolvers.resolver(Placeholder.unparsed(TAG + i, value(values, this.placeholders.get(i))));
        }
        TagResolver resolver = resolvers.build();
        List<Component> lines = new ArrayList<>(this.compiled.size());
        for (String line : this.compiled) {
            lines.add(style.parse(line, resolver).colorIfAbsent(style.palette().primary()));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    static String value(Function<String, String> values, String name) {
        String value;
        try {
            value = values.apply(name);
        } catch (RuntimeException e) {
            return MISSING;
        }
        if (value == null) {
            return MISSING;
        }
        return value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) : value;
    }
}
