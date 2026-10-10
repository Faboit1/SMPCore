package net.siftvanilla.siftcore.feature.crates;

import java.time.Duration;
import java.util.List;

/** When the keyall runs and when its countdown speaks. Pure logic on epoch milliseconds. */
final class KeyallClock {

    private KeyallClock() {
    }

    /**
     * The next run after the server started.
     *
     * @param stored the stored next run, or 0 when none was stored
     * @return the stored time when it is still ahead (but never further away than one interval); when it passed while
     *         the server was offline, {@code now + missedDelay} so players who come back still get it; without a
     *         stored time one interval from now
     */
    static long nextAfterStart(long stored, long now, Duration interval, Duration missedDelay) {
        long intervalMillis = interval.toMillis();
        if (stored <= 0) {
            return now + intervalMillis;
        }
        if (stored <= now) {
            return now + missedDelay.toMillis();
        }
        return Math.min(stored, now + intervalMillis);
    }

    /**
     * The next run after a reload (the interval may have changed, the keyall may have been switched back on): never
     * further away than one new interval, and one interval from now when the old time already passed.
     */
    static long afterReload(long current, long now, Duration interval) {
        if (current <= now) {
            return now + interval.toMillis();
        }
        return Math.min(current, now + interval.toMillis());
    }

    /**
     * The chat announcement whose moment passed between two ticks: the remaining time went from
     * {@code beforeMillis} to {@code afterMillis}. When several passed at once (a stalled tick), the latest one, the
     * shortest. Null when none.
     */
    static Duration crossed(long beforeMillis, long afterMillis, List<Duration> thresholds) {
        Duration result = null;
        for (Duration threshold : thresholds) {
            long at = threshold.toMillis();
            if (beforeMillis > at && afterMillis <= at && afterMillis > 0) {
                if (result == null || threshold.compareTo(result) < 0) {
                    result = threshold;
                }
            }
        }
        return result;
    }

    /** The whole seconds to show in the action bar countdown, or -1 when it is not counting down. */
    static int actionBarSeconds(long remainingMillis, Duration from) {
        if (from.isZero() || remainingMillis <= 0 || remainingMillis > from.toMillis()) {
            return -1;
        }
        return (int) ((remainingMillis + 999) / 1000);
    }

    /** The time left as a duration rounded up to whole seconds (never negative). */
    static Duration remaining(long nextRun, long now) {
        long millis = Math.max(0, nextRun - now);
        return Duration.ofSeconds((millis + 999) / 1000);
    }
}
