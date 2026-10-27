package net.siftvanilla.siftcore.feature.displays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.junit.jupiter.api.Test;

/** A refresh only re-sends text when the rendered component changed. */
class ChangeDetectionTest {

    @Test
    void firstValueIsAChangeAndAnEqualValueIsNot() {
        ChangeTracker<String, Component> tracker = new ChangeTracker<>();
        assertTrue(tracker.update("a", Component.text("one")));
        assertFalse(tracker.update("a", Component.text("one")), "an equal component is no change");
        assertTrue(tracker.update("a", Component.text("two")));
        assertEquals(Component.text("two"), tracker.get("a"));
    }

    @Test
    void keysAreIndependentAndForgettingStartsOver() {
        ChangeTracker<String, Component> tracker = new ChangeTracker<>();
        tracker.update("a", Component.text("x"));
        assertTrue(tracker.update("b", Component.text("x")), "another display is tracked on its own");
        tracker.forget("a");
        assertNull(tracker.get("a"));
        assertTrue(tracker.update("a", Component.text("x")));
        tracker.clear();
        assertEquals(0, tracker.size());
    }

    @Test
    void renderingTheSameValuesTwiceIsNoChangeButANewValueIs() {
        TextStyle style = DisplaysTestSupport.style();
        DisplayTemplate template = DisplayTemplate.compile("richest",
            List.of("<icon:money> <primary>Richest players", "<secondary>1. <primary>{baltop_name_1} <money>{baltop_value_1}"),
            style, (line, message) -> {
                throw new AssertionError(message);
            });
        Map<String, String> values = new HashMap<>(Map.of("baltop_name_1", "Alex", "baltop_value_1", "$1,500"));
        ChangeTracker<String, Component> tracker = new ChangeTracker<>();
        assertTrue(tracker.update("richest", template.render(style, values::get)));
        assertFalse(tracker.update("richest", template.render(style, values::get)), "same leaderboard, nothing to send");
        values.put("baltop_value_1", "$1,600");
        assertTrue(tracker.update("richest", template.render(style, values::get)), "a new balance is sent");
        values.remove("baltop_name_1");
        assertTrue(tracker.update("richest", template.render(style, values::get)), "an emptied place (now -) is sent");
        assertFalse(tracker.update("richest", template.render(style, values::get)));
    }

    @Test
    void concurrentEqualUpdatesReportExactlyOneChange() throws Exception {
        ChangeTracker<String, Component> tracker = new ChangeTracker<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger changes = new AtomicInteger();
        for (int i = 0; i < 64; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (tracker.update("a", Component.text("same"))) {
                    changes.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, changes.get());
    }
}
