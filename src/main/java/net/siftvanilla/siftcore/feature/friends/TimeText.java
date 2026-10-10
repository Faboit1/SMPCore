package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Short times for list rows and profiles: one unit only ("3d", "5h", "12m", "40s"), because "seen 3d 4h 12m ago"
 * is noise in a button label, and dates like {@code 8 Oct 2026} for "friends since". Pure.
 */
public final class TimeText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private TimeText() {
    }

    /** The largest whole unit of the duration: days, hours, minutes or seconds (at least 1s). */
    public static String ago(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());
        if (seconds >= 86_400) {
            return seconds / 86_400 + "d";
        }
        if (seconds >= 3_600) {
            return seconds / 3_600 + "h";
        }
        if (seconds >= 60) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }

    /** {@link #ago(Duration)} of the time between {@code then} and {@code now} (epoch milliseconds). */
    public static String ago(long then, long now) {
        return ago(Duration.ofMillis(Math.max(0, now - then)));
    }

    /** A calendar date in the server's time zone, e.g. {@code 8 Oct 2026}. */
    public static String date(long epochMillis, ZoneId zone) {
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }

    /** A date and time in the server's time zone, e.g. {@code 8 Oct 2026 14:05} (staff views). */
    public static String dateTime(long epochMillis, ZoneId zone) {
        return DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }
}
