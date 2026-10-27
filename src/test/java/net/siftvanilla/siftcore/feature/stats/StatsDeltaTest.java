package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import org.junit.jupiter.api.Test;

class StatsDeltaTest {

    private static final StatsSnapshot SAMPLE = new StatsSnapshot(10, 4, 2, 6, 100, 1000, 5000, 3600);

    @Test
    void killAndDeathMoveCountersAndStreak() {
        StatsSnapshot after = StatsDelta.kill().then(StatsDelta.kill()).applyTo(SAMPLE);
        assertEquals(new StatsSnapshot(12, 4, 4, 6, 100, 1000, 5000, 3600), after);
        StatsSnapshot dead = StatsDelta.kill().then(StatsDelta.kill()).then(StatsDelta.kill()).then(StatsDelta.kill())
            .then(StatsDelta.kill()).then(StatsDelta.death()).applyTo(SAMPLE);
        assertEquals(new StatsSnapshot(15, 5, 0, 7, 100, 1000, 5000, 3600), dead);
    }

    @Test
    void addsAccumulate() {
        StatsDelta delta = StatsDelta.add(Counter.BLOCKS, 3).then(StatsDelta.add(Counter.BLOCKS, 4))
            .then(StatsDelta.add(Counter.EARNED, 250)).then(StatsDelta.add(Counter.PLAYTIME, 1));
        StatsSnapshot after = delta.applyTo(SAMPLE);
        assertEquals(1007, after.blocks());
        assertEquals(5250, after.earned());
        assertEquals(3601, after.playtime());
        assertEquals(SAMPLE.kills(), after.kills());
        assertEquals(SAMPLE.streak(), after.streak());
    }

    @Test
    void setOverridesEarlierChangesAndKeepsLaterOnes() {
        StatsDelta delta = StatsDelta.add(Counter.KILLS, 5).then(StatsDelta.set(Counter.KILLS, 100)).then(StatsDelta.kill());
        StatsSnapshot after = delta.applyTo(SAMPLE);
        assertEquals(101, after.kills());
        assertFalse(delta.keeps(Counter.KILLS));
        assertEquals(101, delta.added(Counter.KILLS));
        assertTrue(delta.keeps(Counter.DEATHS));
        // The set does not touch the streak; the kill does.
        assertEquals(3, after.streak());
    }

    @Test
    void resetZeroesEverythingButLaterEventsCount() {
        StatsSnapshot after = StatsDelta.kill().then(StatsDelta.reset()).then(StatsDelta.add(Counter.MOBS, 2)).then(StatsDelta.kill())
            .applyTo(SAMPLE);
        assertEquals(new StatsSnapshot(1, 0, 1, 1, 2, 0, 0, 0), after);
    }

    @Test
    void emptyDeltasAreRecognised() {
        assertTrue(StatsDelta.NONE.isEmpty());
        assertTrue(StatsDelta.NONE.then(StatsDelta.NONE).isEmpty());
        assertFalse(StatsDelta.kill().isEmpty());
        assertFalse(StatsDelta.set(Counter.KILLS, 0).isEmpty());
        StatsDelta kill = StatsDelta.kill();
        assertSame(kill, kill.then(StatsDelta.NONE));
        assertSame(kill, StatsDelta.NONE.then(kill));
        assertSame(SAMPLE, StatsDelta.NONE.applyTo(SAMPLE));
    }

    @Test
    void rejectsInvalidAmounts() {
        assertThrows(IllegalArgumentException.class, () -> StatsDelta.add(Counter.KILLS, 0));
        assertThrows(IllegalArgumentException.class, () -> StatsDelta.add(Counter.KILLS, -1));
        assertThrows(IllegalArgumentException.class, () -> StatsDelta.set(Counter.KILLS, -1));
    }

    @Test
    void recorderStatsMapToCounters() {
        assertEquals(Counter.KILLS, Counter.of(StatsRecorder.Stat.KILLS));
        assertEquals(Counter.DEATHS, Counter.of(StatsRecorder.Stat.DEATHS));
        assertEquals(Counter.MOBS, Counter.of(StatsRecorder.Stat.MOBS_KILLED));
        assertEquals(Counter.BLOCKS, Counter.of(StatsRecorder.Stat.BLOCKS_MINED));
        assertEquals(Counter.EARNED, Counter.of(StatsRecorder.Stat.MONEY_EARNED));
        assertEquals(Counter.PLAYTIME, Counter.of(StatsRecorder.Stat.PLAYTIME_SECONDS));
        assertEquals(Counter.MOBS, Counter.byId("MOBS").orElseThrow());
        assertTrue(Counter.byId("streak").isEmpty());
    }

    /** Random event sequences: composing in any grouping equals applying the events one by one. */
    @Test
    void compositionMatchesSequentialApplication() {
        Random random = new Random(11);
        for (int trial = 0; trial < 5_000; trial++) {
            List<StatsDelta> events = new ArrayList<>();
            int count = random.nextInt(20);
            for (int i = 0; i < count; i++) {
                events.add(randomEvent(random));
            }
            StatsSnapshot sequential = SAMPLE;
            StatsDelta composed = StatsDelta.NONE;
            for (StatsDelta event : events) {
                sequential = event.applyTo(sequential);
                composed = composed.then(event);
            }
            assertEquals(sequential, composed.applyTo(SAMPLE), () -> "events " + events);
            // Split in two halves, the way a failed save is merged back in front of newer changes.
            int split = events.isEmpty() ? 0 : random.nextInt(events.size() + 1);
            StatsDelta first = StatsDelta.NONE;
            StatsDelta second = StatsDelta.NONE;
            for (int i = 0; i < events.size(); i++) {
                if (i < split) {
                    first = first.then(events.get(i));
                } else {
                    second = second.then(events.get(i));
                }
            }
            assertEquals(sequential, first.then(second).applyTo(SAMPLE));
            assertEquals(sequential, second.applyTo(first.applyTo(SAMPLE)));
        }
    }

    /** Near the column limits, capping after a composed delta equals capping after every single event. */
    @Test
    void capsCommuteWithComposition() {
        Random random = new Random(23);
        StatsSnapshot top = new StatsSnapshot(Counter.INT_MAX - 3, Counter.INT_MAX - 1, Counter.INT_MAX - 2, Counter.INT_MAX - 2,
            Counter.BIG_MAX - 7, Counter.BIG_MAX, Counter.BIG_MAX - 100, Counter.BIG_MAX - 1);
        for (int trial = 0; trial < 5_000; trial++) {
            StatsSnapshot sequential = top;
            StatsDelta composed = StatsDelta.NONE;
            int count = 1 + random.nextInt(12);
            for (int i = 0; i < count; i++) {
                StatsDelta event = random.nextInt(4) == 0
                    ? StatsDelta.add(Counter.values()[random.nextInt(Counter.values().length)], 1 + (long) random.nextInt(Integer.MAX_VALUE))
                    : randomEvent(random);
                sequential = event.applyTo(sequential);
                composed = composed.then(event);
            }
            StatsSnapshot result = composed.applyTo(top);
            assertEquals(sequential, result);
            for (Counter counter : Counter.values()) {
                assertTrue(result.get(counter) <= counter.max(), counter + " stays within its column");
            }
            assertTrue(result.streak() <= Counter.INT_MAX && result.bestStreak() <= Counter.INT_MAX);
        }
    }

    static StatsDelta randomEvent(Random random) {
        int roll = random.nextInt(100);
        Counter counter = Counter.values()[random.nextInt(Counter.values().length)];
        if (roll < 30) {
            return StatsDelta.kill();
        }
        if (roll < 45) {
            return StatsDelta.death();
        }
        if (roll < 90) {
            return StatsDelta.add(counter, 1 + random.nextInt(50));
        }
        if (roll < 97) {
            return StatsDelta.set(counter, random.nextInt(100));
        }
        return StatsDelta.reset();
    }
}
