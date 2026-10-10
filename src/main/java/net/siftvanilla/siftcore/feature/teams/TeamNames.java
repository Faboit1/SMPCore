package net.siftvanilla.siftcore.feature.teams;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Team name rules: a length range (at most 16, the column size), only ASCII letters, digits and underscores, unique
 * ignoring case, and not containing a blocked word. Blocked words are matched loosely so the obvious workarounds
 * ({@code B_A_D}, {@code b4d}, {@code baaad}) are caught too, without matching anything a plain substring search
 * would not already match.
 */
public final class TeamNames {

    /** The longest name the database can hold. */
    public static final int MAX_LENGTH = 16;

    private TeamNames() {
    }

    /** The case-insensitive key a name is unique by. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Checks the shape of a name and the block list (not uniqueness).
     *
     * @return the problem, or null when the name is fine
     */
    public static TeamProblem validate(String name, int minLength, int maxLength, Collection<String> blockedWords) {
        if (name == null || name.length() < minLength) {
            return TeamProblem.NAME_TOO_SHORT;
        }
        if (name.length() > Math.min(maxLength, MAX_LENGTH)) {
            return TeamProblem.NAME_TOO_LONG;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
            if (!allowed) {
                return TeamProblem.NAME_CHARACTERS;
            }
        }
        return blocked(name, blockedWords) ? TeamProblem.NAME_BLOCKED : null;
    }

    /** True if the name contains a blocked word, reading digits as letters and allowing stretched letters. */
    public static boolean blocked(String name, Collection<String> blockedWords) {
        if (blockedWords.isEmpty()) {
            return false;
        }
        Set<String> forms = forms(name);
        for (String word : blockedWords) {
            for (String needle : forms(word)) {
                if (needle.isEmpty()) {
                    continue;
                }
                Pattern stretched = stretched(needle);
                for (String form : forms) {
                    if (stretched.matcher(form).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Lowercase without separators, and the same with digits read as the letters they imitate. */
    static Set<String> forms(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder plain = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                plain.append(c);
            }
        }
        String base = plain.toString();
        Set<String> forms = new LinkedHashSet<>();
        forms.add(base);
        forms.add(unleet(base));
        return forms;
    }

    /** {@code bad} matches {@code bad}, {@code baad} and {@code bbaaddd}: every letter may repeat. */
    private static Pattern stretched(String word) {
        StringBuilder regex = new StringBuilder(word.length() * 6);
        for (int i = 0; i < word.length(); i++) {
            regex.append(Pattern.quote(String.valueOf(word.charAt(i)))).append('+');
        }
        return Pattern.compile(regex.toString());
    }

    private static String unleet(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(switch (c) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4' -> 'a';
                case '5' -> 's';
                case '7' -> 't';
                case '8' -> 'b';
                case '9' -> 'g';
                default -> c;
            });
        }
        return sb.toString();
    }

    /** Cleans a configured block list: trimmed, lowercase, no blanks, no duplicates. */
    public static List<String> cleanBlockList(Collection<String> words) {
        Set<String> clean = new LinkedHashSet<>();
        for (String word : words) {
            if (word != null && !word.isBlank()) {
                clean.add(word.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(clean);
    }
}
