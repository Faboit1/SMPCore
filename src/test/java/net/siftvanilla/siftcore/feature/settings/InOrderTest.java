package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InOrderTest {

    @Test
    void aReadStartsOnlyOnceTheStepsBeforeItRan() throws Exception {
        InOrder steps = new InOrder(SettingsDb.LOGGER);
        List<String> ran = new CopyOnWriteArrayList<>();
        CompletableFuture<String> first = new CompletableFuture<>();
        AtomicBoolean secondRead = new AtomicBoolean();
        steps.then(() -> first, (value, error) -> ran.add("first " + value));
        steps.then(() -> {
            secondRead.set(true);
            return CompletableFuture.completedFuture("b");
        }, (value, error) -> ran.add("second " + value));
        assertFalse(secondRead.get(), "the second read waits for the first step");
        assertTrue(ran.isEmpty());
        first.complete("a");
        steps.idle().get(5, TimeUnit.SECONDS);
        assertTrue(secondRead.get());
        assertEquals(List.of("first a", "second b"), ran);
    }

    @Test
    void stepsRunInTheOrderGivenWhateverOrderTheirReadsComplete() throws Exception {
        InOrder steps = new InOrder(SettingsDb.LOGGER);
        List<String> ran = new CopyOnWriteArrayList<>();
        CompletableFuture<String> slow = new CompletableFuture<>();
        CompletableFuture<String> fast = CompletableFuture.completedFuture("fast");
        steps.then(() -> slow, (value, error) -> ran.add(value));
        steps.then(() -> fast, (value, error) -> ran.add(value));
        assertTrue(ran.isEmpty(), "an already read value still waits for the step before");
        slow.complete("slow");
        steps.idle().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("slow", "fast"), ran);
    }

    @Test
    void failuresReachTheStepAndDoNotStopLaterOnes() throws Exception {
        InOrder steps = new InOrder(SettingsDb.LOGGER);
        List<String> ran = new CopyOnWriteArrayList<>();
        steps.then(() -> CompletableFuture.failedFuture(new IllegalStateException("read failed")),
            (value, error) -> ran.add("failed: " + error.getMessage()));
        steps.then(() -> {
            throw new IllegalStateException("could not start");
        }, (value, error) -> ran.add("not started: " + error.getMessage()));
        steps.then(() -> CompletableFuture.completedFuture("x"), (value, error) -> {
            throw new IllegalStateException("step failed");
        });
        steps.then(() -> CompletableFuture.completedFuture("y"), (value, error) -> ran.add("after " + value));
        steps.idle().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("failed: read failed", "not started: could not start", "after y"), ran);
    }
}
