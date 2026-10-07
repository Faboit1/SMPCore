package net.siftvanilla.siftcore.core.config;

import java.time.Duration;
import java.util.Locale;

/** Parses and formats compact durations such as {@code 30s}, {@code 5m}, {@code 1h30m}, {@code 2d}. */
public final class Durations {

    private Durations() {
    }

    /**
     * Parses a compact duration. Units: ms, s, m, h, d, w. A bare number means seconds.
     *
     * @throws IllegalArgumentException with a human readable reason
     */
    public static Duration parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("is empty");
        }
        String text = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("is empty");
        }
        if (text.chars().allMatch(Character::isDigit)) {
            return Duration.ofSeconds(Long.parseLong(text));
        }
        long totalMillis = 0;
        int i = 0;
        while (i < text.length()) {
            int start = i;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("is not a duration like 30s, 5m, 1h30m or 2d");
            }
            long value = Long.parseLong(text.substring(start, i));
            int unitStart = i;
            while (i < text.length() && Character.isLetter(text.charAt(i))) {
                i++;
            }
            String unit = text.substring(unitStart, i);
            long factor = switch (unit) {
                case "ms" -> 1L;
                case "s", "" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> throw new IllegalArgumentException("has an unknown unit '" + unit + "' (use ms, s, m, h, d or w)");
            };
            totalMillis = Math.addExact(totalMillis, Math.multiplyExact(value, factor));
        }
        return Duration.ofMillis(totalMillis);
    }

    /** Formats a duration as compact text such as {@code 1h 5m}, {@code 12s} or {@code 250ms}. Never empty. */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds == 0) {
            long millis = Math.max(0, duration.toMillis());
            return millis == 0 ? "0s" : millis + "ms";
        }
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        append(sb, days, "d");
        append(sb, hours, "h");
        if (days == 0) {
            append(sb, minutes, "m");
        }
        if (days == 0 && hours == 0) {
            append(sb, secs, "s");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, long value, String unit) {
        if (value > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(value).append(unit);
        }
    }
}
