package net.siftvanilla.siftcore.feature.boosters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import org.junit.jupiter.api.Test;

/** What the announcer tells everyone, from two looks at the line: each change once, and nothing while unsettled. */
class LineWatchTest {

    private static final long NOW = 1_700_000_000_000L;

    private static Booster booster(long id) {
        return Booster.queued(id, ServerBoosters.SELL, 10, Duration.ofMinutes(30), null, Booster.Source.STAFF, null, null, "test", NOW);
    }

    @Test
    void aStartAQueueAndAnEndAreEachToldOnce() {
        LineWatch watch = new LineWatch();
        watch.prime(null, List.of());
        Booster a = booster(1).started(NOW);
        Booster b = booster(2);
        assertEquals(List.of(new LineWatch.Started(a), new LineWatch.Queued(b, 1)), watch.look(a, List.of(b), true));
        assertEquals(List.of(), watch.look(a, List.of(b), true), "nothing new");
        Booster bRunning = b.started(NOW);
        assertEquals(List.of(new LineWatch.Ended(1), new LineWatch.Started(bRunning)), watch.look(bRunning, List.of(), true),
            "back to back: the end, then the next start");
        assertEquals(List.of(new LineWatch.Ended(2)), watch.look(null, List.of(), true));
    }

    @Test
    void whatWasLoadedAtStartupIsNotToldAgain() {
        LineWatch watch = new LineWatch();
        Booster a = booster(1).started(NOW);
        Booster b = booster(2);
        watch.prime(a, List.of(b));
        assertEquals(List.of(), watch.look(a, List.of(b), true));
    }

    @Test
    void nothingIsToldOrRememberedWhileAChangeMayStillBeTakenBack() {
        LineWatch watch = new LineWatch();
        watch.prime(null, List.of());
        Booster a = booster(1).started(NOW);
        assertEquals(List.of(), watch.look(a, List.of(), false));
        assertEquals(List.of(new LineWatch.Started(a)), watch.look(a, List.of(), true), "told once it is stored");

        Booster b = booster(2);
        assertEquals(List.of(), watch.look(a, List.of(b), false));
        assertEquals(List.of(), watch.look(a, List.of(), true), "taken back before it was stored: never told");
    }
}
