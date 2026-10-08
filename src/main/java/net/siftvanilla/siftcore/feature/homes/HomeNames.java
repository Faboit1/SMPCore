package net.siftvanilla.siftcore.feature.homes;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Home name rules: 1-16 letters, digits, {@code _} or {@code -}; compared and stored in lowercase. */
final class HomeNames {

    static final String DEFAULT = "home";
    static final int MAX_LENGTH = 16;
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_LENGTH + "}");

    private HomeNames() {
    }

    /** The stored form of a typed name, or empty when it breaks the rules. */
    static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String trimmed = input.strip();
        if (!VALID.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        return Optional.of(trimmed.toLowerCase(Locale.ROOT));
    }

    /** A free name for the set-home form: {@code home}, then {@code home2}, {@code home3}... */
    static String suggest(Map<String, ?> existing) {
        if (!existing.containsKey(DEFAULT)) {
            return DEFAULT;
        }
        for (int i = 2; i < 10_000; i++) {
            String candidate = DEFAULT + i;
            if (!existing.containsKey(candidate)) {
                return candidate;
            }
        }
        return "";
    }
}
