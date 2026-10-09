package net.siftvanilla.siftcore.feature.boosters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A booster's length in words, for the announcements. */
class BoosterTimeTest {

    private static String words(Duration duration) {
        return BoosterTime.words(duration, (unit, count) -> count + " " + switch (unit) {
            case DAY -> count == 1 ? "day" : "days";
            case HOUR -> count == 1 ? "hour" : "hours";
            case MINUTE -> count == 1 ? "minute" : "minutes";
            case SECOND -> count == 1 ? "second" : "seconds";
        });
    }

    @Test
    void lengthsReadNaturally() {
        assertEquals("30 minutes", words(Duration.ofMinutes(30)));
        assertEquals("1 minute", words(Duration.ofMinutes(1)));
        assertEquals("1 hour 30 minutes", words(Duration.ofMinutes(90)));
        assertEquals("2 hours", words(Duration.ofHours(2)));
        assertEquals("2 days", words(Duration.ofHours(48)));
        assertEquals("1 day 6 hours", words(Duration.ofHours(30)));
        assertEquals("45 seconds", words(Duration.ofSeconds(45)));
        assertEquals("0 seconds", words(Duration.ZERO));
    }

    @Test
    void atMostTheTwoLargestNeighbouringUnits() {
        assertEquals("1 day", words(Duration.ofDays(1).plusMinutes(5)), "a day and five minutes reads as a day");
        assertEquals("2 hours 5 minutes", words(Duration.ofHours(2).plusMinutes(5).plusSeconds(30)), "seconds never follow minutes");
        assertEquals("1 minute", words(Duration.ofSeconds(90)), "seconds only for lengths under a minute");
    }
}
