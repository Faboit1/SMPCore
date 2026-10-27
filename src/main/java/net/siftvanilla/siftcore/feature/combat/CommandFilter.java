package net.siftvanilla.siftcore.feature.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The commands refused in combat. An entry names a command ({@code home}) or a subcommand ({@code team home}). A
 * typed command matches when its label, with any namespace removed ({@code /siftcore:home}), is the entry's first
 * word or shares a command with it (the resolver returns every name and alias of the typed label's command), and
 * the following words start with the rest of the entry. Pure; the server-side resolver is passed in.
 */
final class CommandFilter {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern WORD = Pattern.compile("[a-z0-9_.\\-]{1,64}");

    /** One parsed entry: lowercase words, the first without a namespace. */
    record Rule(List<String> words) {

        Rule {
            words = List.copyOf(words);
            if (words.isEmpty()) {
                throw new IllegalArgumentException("is empty");
            }
        }

        /**
         * Parses an entry such as {@code home}, {@code /home} or {@code team home}.
         *
         * @throws IllegalArgumentException with a short reason
         */
        static Rule parse(String entry) {
            String[] tokens = tokens(entry);
            if (tokens.length == 0) {
                throw new IllegalArgumentException("is empty");
            }
            List<String> words = new ArrayList<>(tokens.length);
            for (int i = 0; i < tokens.length; i++) {
                String word = i == 0 ? label(tokens[0]) : tokens[i];
                if (!WORD.matcher(word).matches()) {
                    throw new IllegalArgumentException("has an invalid word '" + word + "'");
                }
                words.add(word);
            }
            return new Rule(words);
        }

        @Override
        public String toString() {
            return String.join(" ", this.words);
        }
    }

    private final List<Rule> rules;

    CommandFilter(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    List<Rule> rules() {
        return this.rules;
    }

    /** Splits a command line into lowercase words, without the leading slash. */
    static String[] tokens(String line) {
        if (line == null) {
            return new String[0];
        }
        String trimmed = line.strip();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return WHITESPACE.split(trimmed.toLowerCase(Locale.ROOT));
    }

    /** A label without its namespace: {@code siftcore:home} becomes {@code home}. */
    static String label(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        int colon = lower.lastIndexOf(':');
        return colon >= 0 ? lower.substring(colon + 1) : lower;
    }

    /**
     * Whether the typed command line is refused.
     *
     * @param line  what the player typed, e.g. {@code /t home} (with or without the slash)
     * @param names every name of the command behind a label (the label itself, the command's name and aliases,
     *              all lowercase and without namespaces)
     */
    boolean blocks(String line, Function<String, Set<String>> names) {
        if (this.rules.isEmpty()) {
            return false;
        }
        String[] tokens = tokens(line);
        if (tokens.length == 0) {
            return false;
        }
        String label = label(tokens[0]);
        Set<String> known = null;
        for (Rule rule : this.rules) {
            List<String> words = rule.words();
            if (words.size() > tokens.length) {
                continue;
            }
            String first = words.getFirst();
            if (!first.equals(label)) {
                if (known == null) {
                    known = names.apply(label);
                }
                if (!known.contains(first)) {
                    continue;
                }
            }
            boolean match = true;
            for (int i = 1; i < words.size(); i++) {
                if (!words.get(i).equals(tokens[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
