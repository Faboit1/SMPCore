package net.siftvanilla.siftcore.feature.staff;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.siftvanilla.siftcore.core.config.Durations;

/**
 * Reads the time part of punishment commands. A time is a number with a unit ({@code 30m}, {@code 12h},
 * {@code 7d}, {@code 1h30m}); a bare number is never a time here, so a reason such as "5 alts" is not mistaken for
 * five seconds. {@code perm}, {@code permanent} and {@code forever} explicitly mean no end.
 */
final class DurationInput {

    /** The shortest punishment staff can give. */
    static final Duration MIN = Duration.ofSeconds(1);
    /** The longest reason staff can give. */
    static final int MAX_REASON = 200;

    private static final Pattern LOOKS_LIKE_TIME = Pattern.compile("(\\d+[a-zA-Z]+)+");
    private static final Set<String> PERMANENT_WORDS = Set.of("perm", "permanent", "forever");

    /** What was wrong with the input. */
    enum Problem {
        NONE,
        /** A time was required but none was given. */
        MISSING,
        /** It looks like a time but can't be read (unknown unit). */
        INVALID,
        TOO_SHORT,
        TOO_LONG,
        REASON_TOO_LONG
    }

    /**
     * The result of reading {@code [time] [reason]}.
     *
     * @param length  how long, or null for permanent
     * @param reason  the cleaned reason, empty when none
     * @param problem what was wrong, {@link Problem#NONE} when the input is usable
     * @param token   the time token that was read (for error messages), empty when none
     */
    record Parsed(Duration length, String reason, Problem problem, String token) {

        boolean ok() {
            return this.problem == Problem.NONE;
        }

        boolean permanent() {
            return this.length == null;
        }
    }

    private DurationInput() {
    }

    /** {@code [time] [reason]}: the time is optional and a missing time means permanent (/mute). */
    static Parsed optionalLength(String rest, Duration max) {
        return read(rest, max, false);
    }

    /** {@code <time> [reason]}: the time must be there (/tempban). Permanent words are refused. */
    static Parsed requiredLength(String rest, Duration max) {
        return read(rest, max, true);
    }

    /** Only a reason (/ban, /kick, /warn). */
    static Parsed reasonOnly(String rest) {
        String reason = CleanText.clean(rest);
        if (reason.length() > MAX_REASON) {
            return new Parsed(null, reason, Problem.REASON_TOO_LONG, "");
        }
        return new Parsed(null, reason, Problem.NONE, "");
    }

    private static Parsed read(String rest, Duration max, boolean required) {
        String text = rest == null ? "" : rest.strip();
        String first = text;
        String remainder = "";
        int space = indexOfWhitespace(text);
        if (space >= 0) {
            first = text.substring(0, space);
            remainder = text.substring(space + 1);
        }
        String lower = first.toLowerCase(Locale.ROOT);
        if (!required && PERMANENT_WORDS.contains(lower)) {
            return withReason(null, remainder, first);
        }
        if (first.isEmpty() || !LOOKS_LIKE_TIME.matcher(first).matches()) {
            if (required) {
                return new Parsed(null, "", Problem.MISSING, first);
            }
            return withReason(null, text, "");
        }
        Problem problem = check(first, max);
        if (problem != Problem.NONE) {
            return new Parsed(null, "", problem, first);
        }
        return withReason(Durations.parse(first), remainder, first);
    }

    private static Parsed withReason(Duration length, String reasonText, String token) {
        String reason = CleanText.clean(reasonText);
        if (reason.length() > MAX_REASON) {
            return new Parsed(length, reason, Problem.REASON_TOO_LONG, token);
        }
        return new Parsed(length, reason, Problem.NONE, token);
    }

    /** Checks one time token against the limits. */
    static Problem check(String token, Duration max) {
        if (token == null || !LOOKS_LIKE_TIME.matcher(token.strip()).matches()) {
            return Problem.INVALID;
        }
        Duration duration;
        try {
            duration = Durations.parse(token);
        } catch (ArithmeticException e) {
            return Problem.TOO_LONG;
        } catch (IllegalArgumentException e) {
            return Problem.INVALID;
        }
        if (duration.compareTo(MIN) < 0) {
            return Problem.TOO_SHORT;
        }
        if (duration.compareTo(max) > 0) {
            return Problem.TOO_LONG;
        }
        return Problem.NONE;
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
