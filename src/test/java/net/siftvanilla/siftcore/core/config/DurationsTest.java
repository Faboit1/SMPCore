package net.siftvanilla.siftcore.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesCompactDurations() {
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"));
        assertEquals(Duration.ofMinutes(5), Durations.parse("5m"));
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h30m"));
        assertEquals(Duration.ofDays(2), Durations.parse("2d"));
        assertEquals(Duration.ofMillis(250), Durations.parse("250ms"));
        assertEquals(Duration.ofSeconds(45), Durations.parse("45"));
        assertEquals(Duration.ofDays(7), Durations.parse("1w"));
        assertEquals(Duration.ofMinutes(61), Durations.parse("1h 1m"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("five minutes"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("5x"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("m"));
    }

    @Test
    void formatsCompactly() {
        assertEquals("0s", Durations.format(Duration.ZERO));
        assertEquals("250ms", Durations.format(Duration.ofMillis(250)));
        assertEquals("12s", Durations.format(Duration.ofSeconds(12)));
        assertEquals("1m 5s", Durations.format(Duration.ofSeconds(65)));
        assertEquals("1h 5m", Durations.format(Duration.ofMinutes(65)));
        assertEquals("2d 3h", Durations.format(Duration.ofHours(51)));
    }
}
