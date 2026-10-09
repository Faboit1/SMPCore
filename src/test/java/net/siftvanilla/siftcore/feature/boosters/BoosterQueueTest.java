package net.siftvanilla.siftcore.feature.boosters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import org.junit.jupiter.api.Test;

/** The booster line: one at a time, back to back, never stacked, time only through advance(). */
class BoosterQueueTest {

    private static final long NOW = 1_700_000_000_000L;

    private static Booster booster(long id, int percent, Duration length) {
        return Booster.queued(id, ServerBoosters.SELL, percent, length, UUID.randomUUID(), Booster.Source.STORE, "ref-" + id, null,
            "console", NOW);
    }

    @Test
    void theFirstBoosterStartsAndTheNextWaitsWithoutStacking() {
        BoosterQueue queue = new BoosterQueue();
        List<Booster> first = queue.add(booster(1, 10, Duration.ofMinutes(30)), NOW);
        assertEquals(Booster.State.ACTIVE, first.getFirst().state());
        assertEquals(NOW, first.getFirst().started());
        List<Booster> second = queue.add(booster(2, 25, Duration.ofMinutes(30)), NOW);
        assertEquals(Booster.State.QUEUED, second.getFirst().state());
        assertEquals(10, queue.percent(), "percentages never stack: only the running booster counts");
        assertEquals(1, queue.position(2));
        assertEquals(0, queue.position(1), "the running booster has no place in line");
        assertThrows(IllegalStateException.class, () -> queue.add(booster(2, 5, Duration.ofMinutes(1)), NOW), "an id is added once");
    }

    @Test
    void boostersRunBackToBackAndTheLeftoverCarriesOver() {
        BoosterQueue queue = new BoosterQueue();
        queue.add(booster(1, 10, Duration.ofMinutes(1)), NOW);
        queue.add(booster(2, 20, Duration.ofMinutes(1)), NOW);
        queue.add(booster(3, 30, Duration.ofMinutes(1)), NOW);
        assertTrue(queue.advance(59_999, NOW + 59_999).isEmpty(), "nothing ends before its time");
        assertEquals(1, queue.active().remaining());
        List<Booster> changed = queue.advance(1_500, NOW + 61_499);
        assertEquals(2, changed.size(), "the first ended and the second started: " + changed);
        assertEquals(Booster.State.ENDED, changed.get(0).state());
        assertEquals(0, changed.get(0).remaining());
        assertEquals(NOW + 61_499, changed.get(0).ended());
        assertEquals(2, changed.get(1).id());
        assertEquals(Booster.State.ACTIVE, changed.get(1).state());
        assertEquals(60_000 - 1_499, queue.active().remaining(), "the 1.499s past the first booster's end come off the second");
        assertEquals(20, queue.percent());
        // One huge step ends the second and the third in one go, and nothing is left.
        List<Booster> rest = queue.advance(500_000, NOW + 600_000);
        assertEquals(List.of(2L, 3L), rest.stream().map(Booster::id).toList());
        assertTrue(rest.stream().allMatch(b -> b.state() == Booster.State.ENDED));
        assertNull(queue.active());
        assertEquals(0, queue.percent());
        assertTrue(queue.finished(1).isPresent() && queue.finished(3).isPresent());
    }

    @Test
    void timeOnlyPassesForTheRunningBooster() {
        BoosterQueue queue = new BoosterQueue();
        queue.add(booster(1, 10, Duration.ofMinutes(10)), NOW);
        queue.add(booster(2, 10, Duration.ofMinutes(10)), NOW);
        queue.advance(120_000, NOW + 120_000);
        assertEquals(480_000, queue.active().remaining());
        assertEquals(600_000, queue.waiting().getFirst().remaining(), "a waiting booster keeps its whole length");
        assertTrue(queue.advance(0, NOW).isEmpty());
        assertTrue(queue.advance(-5, NOW).isEmpty(), "time never runs backwards");
    }

    @Test
    void endingEarlyStartsTheNextOrLeavesTheLine() {
        BoosterQueue queue = new BoosterQueue();
        queue.add(booster(1, 10, Duration.ofMinutes(10)), NOW);
        queue.add(booster(2, 15, Duration.ofMinutes(10)), NOW);
        queue.add(booster(3, 20, Duration.ofMinutes(10)), NOW);
        List<Booster> removed = queue.end(3, Booster.State.REVOKED, NOW + 1);
        assertEquals(1, removed.size());
        assertEquals(Booster.State.REVOKED, removed.getFirst().state());
        assertFalse(queue.contains(3));
        List<Booster> stopped = queue.end(1, Booster.State.STOPPED, NOW + 2);
        assertEquals(List.of(Booster.State.STOPPED, Booster.State.ACTIVE), stopped.stream().map(Booster::state).toList());
        assertEquals(2, queue.active().id());
        assertEquals(15, queue.percent());
        assertTrue(queue.end(99, Booster.State.STOPPED, NOW).isEmpty(), "an unknown booster changes nothing");
        assertThrows(IllegalArgumentException.class, () -> queue.end(2, Booster.State.ENDED, NOW));
    }

    @Test
    void undoingAnAddAndAnEndPutsTheLineBack() {
        BoosterQueue queue = new BoosterQueue();
        queue.add(booster(1, 10, Duration.ofMinutes(10)), NOW);
        queue.add(booster(2, 15, Duration.ofMinutes(10)), NOW);
        // The running booster could not be stored: it never existed and the next one runs.
        List<Booster> changed = queue.remove(1, NOW + 5);
        assertEquals(2, queue.active().id());
        assertEquals(List.of(2L), changed.stream().map(Booster::id).toList());

        queue.add(booster(3, 20, Duration.ofMinutes(10)), NOW);
        Booster running = queue.active();
        queue.end(running.id(), Booster.State.REVOKED, NOW + 10);
        assertEquals(3, queue.active().id());
        // The revoke could not be stored: the revoked booster runs again and the one that replaced it waits first.
        List<Booster> back = queue.reinstate(running, 0);
        assertEquals(2, queue.active().id());
        assertEquals(Booster.State.ACTIVE, queue.active().state());
        assertEquals(3, queue.waiting().getFirst().id());
        assertEquals(Booster.State.QUEUED, queue.waiting().getFirst().state());
        assertEquals(0, queue.waiting().getFirst().started(), "the displaced booster waits as if it never started");
        assertEquals(2, back.size());
        assertTrue(queue.finished(2).isEmpty(), "a reinstated booster is not finished");

        Booster waiting = queue.waiting().getFirst();
        queue.end(waiting.id(), Booster.State.REVOKED, NOW + 20);
        queue.reinstate(waiting, 0);
        assertEquals(1, queue.position(waiting.id()), "a waiting booster gets its old place back");
    }

    @Test
    void loadingResumesTheBoosterThatStartedFirst() {
        Booster a = booster(5, 10, Duration.ofMinutes(10)).started(NOW - 1_000).withRemaining(42_000);
        Booster b = booster(3, 20, Duration.ofMinutes(10)).started(NOW);
        Booster c = booster(4, 30, Duration.ofMinutes(10));
        Booster ended = booster(1, 50, Duration.ofMinutes(1)).started(NOW).over(Booster.State.ENDED, NOW);
        BoosterQueue queue = new BoosterQueue();
        List<Booster> changed = queue.load(List.of(c, b, a, ended), NOW);
        assertEquals(5, queue.active().id(), "the booster that started first runs again");
        assertEquals(42_000, queue.active().remaining(), "with the time it had left");
        assertEquals(List.of(3L, 4L), queue.waiting().stream().map(Booster::id).toList(), "the others wait in id order");
        assertEquals(1, changed.size(), "the second running booster (a crash between two writes) waits again");
        assertEquals(Booster.State.QUEUED, changed.getFirst().state());

        BoosterQueue onlyWaiting = new BoosterQueue();
        List<Booster> started = onlyWaiting.load(List.of(c), NOW);
        assertEquals(4, onlyWaiting.active().id(), "with nothing running, the next waiting booster starts");
        assertEquals(List.of(Booster.State.ACTIVE), started.stream().map(Booster::state).toList());
    }

    @Test
    void lookupsByReference() {
        BoosterQueue queue = new BoosterQueue();
        queue.add(booster(1, 10, Duration.ofMinutes(10)), NOW);
        queue.add(booster(2, 15, Duration.ofMinutes(10)), NOW);
        assertEquals(1, queue.byRef("ref-1").orElseThrow().id());
        assertEquals(2, queue.byRef("ref-2").orElseThrow().id());
        assertTrue(queue.byRef("ref-9").isEmpty());
        assertTrue(queue.byRef(null).isEmpty());
    }

    @Test
    void boosterRecordRules() {
        Booster b = booster(1, 10, Duration.ofSeconds(90));
        assertEquals(90_000, b.remaining());
        assertEquals(1.0f, b.progress());
        assertEquals(0.5f, b.withRemaining(45_000).progress());
        assertEquals(90_000, b.withRemaining(1_000_000).remaining(), "remaining time never exceeds the length");
        assertThrows(IllegalArgumentException.class, () -> booster(2, 0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> b.over(Booster.State.ACTIVE, NOW));
        assertEquals(Booster.State.REVOKED, Booster.State.byId("revoked"));
        assertTrue(Booster.State.STOPPED.over());
        assertFalse(Booster.State.QUEUED.over());
        assertEquals(Booster.Source.STORE, Booster.Source.byId("store"));
        assertEquals(Booster.Source.STAFF, Booster.Source.byId("staff"));
    }
}
