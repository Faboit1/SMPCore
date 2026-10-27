package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyallClockTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Duration HOURS_4 = Duration.ofHours(4);
    private static final Duration MISSED = Duration.ofMinutes(10);

    @Test
    void firstStartSchedulesOneIntervalAhead() {
        assertEquals(NOW + HOURS_4.toMillis(), KeyallClock.nextAfterStart(0, NOW, HOURS_4, MISSED));
    }

    @Test
    void aStoredTimeAheadIsKept() {
        long stored = NOW + Duration.ofMinutes(73).toMillis();
        assertEquals(stored, KeyallClock.nextAfterStart(stored, NOW, HOURS_4, MISSED));
    }

    @Test
    void aKeyallMissedWhileOfflineRunsShortlyAfterStartup() {
        long stored = NOW - Duration.ofMinutes(30).toMillis();
        assertEquals(NOW + MISSED.toMillis(), KeyallClock.nextAfterStart(stored, NOW, HOURS_4, MISSED));
        assertEquals(NOW, KeyallClock.nextAfterStart(stored, NOW, HOURS_4, Duration.ZERO));
    }

    @Test
    void aStoredTimeBeyondAShorterIntervalIsPulledIn() {
        long stored = NOW + Duration.ofHours(10).toMillis();
        assertEquals(NOW + HOURS_4.toMillis(), KeyallClock.nextAfterStart(stored, NOW, HOURS_4, MISSED));
    }

    @Test
    void reloadKeepsTheTimeUnlessTheIntervalGotShorterOrItPassed() {
        long next = NOW + Duration.ofHours(3).toMillis();
        assertEquals(next, KeyallClock.afterReload(next, NOW, HOURS_4));
        assertEquals(NOW + Duration.ofHours(1).toMillis(), KeyallClock.afterReload(next, NOW, Duration.ofHours(1)));
        assertEquals(NOW + HOURS_4.toMillis(), KeyallClock.afterReload(NOW - 5_000, NOW, HOURS_4),
            "switched back on after its time passed: one interval from now");
    }

    @Test
    void chatAnnouncementsFireOnceWhenTheirMomentPasses() {
        List<Duration> at = List.of(Duration.ofMinutes(5), Duration.ofMinutes(1));
        long fiveMinutes = Duration.ofMinutes(5).toMillis();
        assertNull(KeyallClock.crossed(fiveMinutes + 2_000, fiveMinutes + 1_000, at));
        assertEquals(Duration.ofMinutes(5), KeyallClock.crossed(fiveMinutes + 400, fiveMinutes - 600, at));
        assertNull(KeyallClock.crossed(fiveMinutes - 600, fiveMinutes - 1_600, at), "not again on the next tick");
        assertEquals(Duration.ofMinutes(1), KeyallClock.crossed(60_100, 59_100, at));
        assertEquals(Duration.ofMinutes(1), KeyallClock.crossed(fiveMinutes + 10_000, 30_000, at),
            "after a stalled tick only the latest announcement is made");
        assertNull(KeyallClock.crossed(1_000, 0, at), "never at zero, the keyall itself speaks then");
        assertNull(KeyallClock.crossed(Long.MAX_VALUE, Duration.ofHours(4).toMillis(), at));
    }

    @Test
    void actionBarCountsTheLastSeconds() {
        Duration from = Duration.ofSeconds(10);
        assertEquals(-1, KeyallClock.actionBarSeconds(10_001, from));
        assertEquals(10, KeyallClock.actionBarSeconds(10_000, from));
        assertEquals(10, KeyallClock.actionBarSeconds(9_001, from));
        assertEquals(1, KeyallClock.actionBarSeconds(1, from));
        assertEquals(-1, KeyallClock.actionBarSeconds(0, from));
        assertEquals(-1, KeyallClock.actionBarSeconds(5_000, Duration.ZERO));
    }

    @Test
    void remainingRoundsUpAndNeverGoesNegative() {
        assertEquals(Duration.ofSeconds(2), KeyallClock.remaining(NOW + 1_001, NOW));
        assertEquals(Duration.ZERO, KeyallClock.remaining(NOW - 5_000, NOW));
    }
}
