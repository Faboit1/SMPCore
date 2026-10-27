package net.siftvanilla.siftcore.feature.boosters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A booster's length in words for announcements ("30 minutes", "1 hour 30 minutes", "2 days"): the two largest
 * units that are not zero, seconds only for lengths under a minute. Pure logic; the unit names come from the lang file.
 */
final class BoosterTime {

    /** A time unit. */
    enum Unit {
        DAY(86_400),
        HOUR(3_600),
        MINUTE(60),
        SECOND(1);

        private final long seconds;

        Unit(long seconds) {
            this.seconds = seconds;
        }
    }

    /** One part of a length: {@code count} of {@code unit}. */
    record Part(Unit unit, long count) {
    }

    /** Names a part, e.g. {@code (MINUTE, 30)} as "30 minutes". */
    @FunctionalInterface
    interface Namer {
        String name(Unit unit, long count);
    }

    private BoosterTime() {
    }

    /** The parts of a length, largest first, at most two; a length under a second is "0 seconds". */
    static List<Part> parts(Duration duration) {
        long total = Math.max(0, duration.toSeconds());
        boolean underMinute = total < 60;
        List<Part> parts = new ArrayList<>(2);
        for (Unit unit : Unit.values()) {
            if (unit == Unit.SECOND && !underMinute) {
                break;
            }
            long count = total / unit.seconds;
            if (count > 0) {
                parts.add(new Part(unit, count));
                total -= count * unit.seconds;
                if (parts.size() == 2) {
                    break;
                }
            } else if (!parts.isEmpty()) {
                // "1 day 5 minutes" reads oddly; after the largest unit only the very next one may follow.
                break;
            }
        }
        if (parts.isEmpty()) {
            parts.add(new Part(Unit.SECOND, 0));
        }
        return parts;
    }

    /** The length in words, e.g. "1 hour 30 minutes". */
    static String words(Duration duration, Namer namer) {
        StringBuilder text = new StringBuilder();
        for (Part part : parts(duration)) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(namer.name(part.unit(), part.count()));
        }
        return text.toString();
    }
}
