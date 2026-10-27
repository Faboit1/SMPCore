package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StreakChangeTest {

    /** The obvious event-by-event model the algebra must match. */
    private static long[] simulate(long streak, long best, List<StreakChange> events) {
        long s = streak;
        long b = best;
        for (StreakChange event : events) {
            if (event == StreakChange.KILL) {
                s++;
                b = Math.max(b, s);
            } else if (event == StreakChange.DEATH) {
                s = 0;
            } else if (event == StreakChange.RESET) {
                s = 0;
                b = 0;
            }
        }
        return new long[] {s, b};
    }

    private static StreakChange compose(List<StreakChange> events) {
        StreakChange change = StreakChange.NONE;
        for (StreakChange event : events) {
            change = change.then(event);
        }
        return change;
    }

    private static List<StreakChange> randomEvents(Random random, int count) {
        List<StreakChange> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int roll = random.nextInt(100);
            events.add(roll < 65 ? StreakChange.KILL : roll < 95 ? StreakChange.DEATH : StreakChange.RESET);
        }
        return events;
    }

    @Test
    void singleTransitions() {
        assertEquals(6, StreakChange.KILL.streak(5));
        assertEquals(9, StreakChange.KILL.best(5, 9));
        assertEquals(6, StreakChange.KILL.best(5, 5));
        assertEquals(0, StreakChange.DEATH.streak(5));
        assertEquals(9, StreakChange.DEATH.best(5, 9));
        assertEquals(0, StreakChange.RESET.streak(5));
        assertEquals(0, StreakChange.RESET.best(5, 9));
        assertEquals(5, StreakChange.NONE.streak(5));
        assertEquals(9, StreakChange.NONE.best(5, 9));
    }

    @Test
    void killsThenDeathKeepTheRunAsBest() {
        StreakChange change = compose(List.of(StreakChange.KILL, StreakChange.KILL, StreakChange.KILL, StreakChange.DEATH, StreakChange.KILL));
        assertEquals(1, change.streak(4));
        assertEquals(10, change.best(4, 10));
        // The old streak of 4 plus 3 kills beats a best of 5.
        assertEquals(7, change.best(4, 5));
    }

    @Test
    void resetForgetsEverythingBeforeIt() {
        StreakChange change = compose(List.of(StreakChange.KILL, StreakChange.KILL, StreakChange.RESET, StreakChange.KILL));
        assertEquals(1, change.streak(50));
        assertEquals(1, change.best(50, 80));
    }

    @Test
    void noneIsTheIdentity() {
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            StreakChange change = compose(randomEvents(random, random.nextInt(8)));
            assertEquals(change, change.then(StreakChange.NONE));
            assertEquals(change, StreakChange.NONE.then(change));
        }
        assertTrue(StreakChange.NONE.then(StreakChange.NONE).isNone());
        assertFalse(StreakChange.KILL.isNone());
    }

    @Test
    void compositionMatchesTheEventModel() {
        Random random = new Random(42);
        for (int trial = 0; trial < 20_000; trial++) {
            List<StreakChange> events = randomEvents(random, random.nextInt(25));
            long streak = random.nextInt(20);
            long best = streak + random.nextInt(20);
            long[] expected = simulate(streak, best, events);
            StreakChange change = compose(events);
            assertEquals(expected[0], change.streak(streak), () -> "streak after " + events);
            assertEquals(expected[1], change.best(streak, best), () -> "best after " + events);
        }
    }

    @Test
    void anySplitIntoSavesGivesTheSameResult() {
        Random random = new Random(99);
        for (int trial = 0; trial < 5_000; trial++) {
            List<StreakChange> events = randomEvents(random, 1 + random.nextInt(30));
            long streak = random.nextInt(10);
            long best = streak + random.nextInt(10);
            // Apply in random chunks, the way consecutive saves would.
            long s = streak;
            long b = best;
            int from = 0;
            while (from < events.size()) {
                int to = Math.min(events.size(), from + 1 + random.nextInt(6));
                StreakChange chunk = compose(events.subList(from, to));
                long nextBest = chunk.best(s, b);
                s = chunk.streak(s);
                b = nextBest;
                from = to;
            }
            long[] expected = simulate(streak, best, events);
            assertEquals(expected[0], s);
            assertEquals(expected[1], b);
        }
    }

    @Test
    void compositionIsAssociative() {
        Random random = new Random(5);
        for (int trial = 0; trial < 5_000; trial++) {
            StreakChange a = compose(randomEvents(random, random.nextInt(6)));
            StreakChange b = compose(randomEvents(random, random.nextInt(6)));
            StreakChange c = compose(randomEvents(random, random.nextInt(6)));
            StreakChange left = a.then(b).then(c);
            StreakChange right = a.then(b.then(c));
            for (long streak = 0; streak < 6; streak++) {
                for (long best = streak; best < streak + 6; best++) {
                    assertEquals(left.streak(streak), right.streak(streak));
                    assertEquals(left.best(streak, best), right.best(streak, best));
                }
            }
        }
    }

    @Test
    void rejectsNegativeParts() {
        assertThrows(IllegalArgumentException.class, () -> new StreakChange(true, -1, true, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new StreakChange(true, 0, true, true, 0, -3));
    }

    @Test
    void saturatesInsteadOfOverflowing() {
        StreakChange huge = new StreakChange(true, Long.MAX_VALUE - 1, true, true, Long.MAX_VALUE - 1, 0);
        StreakChange more = huge.then(StreakChange.KILL).then(StreakChange.KILL);
        assertEquals(Long.MAX_VALUE, more.streak(5));
        assertEquals(Long.MAX_VALUE, more.best(5, 0));
    }
}
