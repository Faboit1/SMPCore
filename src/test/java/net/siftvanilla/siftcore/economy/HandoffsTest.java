package net.siftvanilla.siftcore.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.siftvanilla.siftcore.core.scheduler.Task;
import org.junit.jupiter.api.Test;

/**
 * Hand-offs after a commit: whatever the player's scheduler does (runs the task, retires the player first, returns no
 * task because the player is already retired, throws because the plugin stops, or never runs at a stop), the payload
 * reaches exactly one of the receiver and the fallback, exactly once.
 */
class HandoffsTest {

    /** A scheduling that keeps the task and the retired callback for the test to fire. */
    private static final class Held implements Handoffs.Scheduling {
        Runnable run;
        Runnable retired;

        @Override
        public Task schedule(Runnable run, Runnable retired) {
            this.run = run;
            this.retired = retired;
            return () -> { };
        }
    }

    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final List<String> fellBack = new CopyOnWriteArrayList<>();

    private long hand(Handoffs<String> handoffs, String payload, Handoffs.Scheduling scheduling) {
        return handoffs.hand(payload, scheduling, this.delivered::add, this.fellBack::add);
    }

    @Test
    void deliversOnTheReceiversThread() {
        Handoffs<String> handoffs = new Handoffs<>();
        Held held = new Held();
        hand(handoffs, "diamonds", held);
        assertEquals(1, handoffs.pending());
        held.run.run();
        assertEquals(List.of("diamonds"), this.delivered);
        assertTrue(this.fellBack.isEmpty());
        assertEquals(0, handoffs.pending());
        held.retired.run();
        handoffs.drain();
        assertEquals(List.of("diamonds"), this.delivered, "nothing runs twice");
        assertTrue(this.fellBack.isEmpty());
    }

    @Test
    void noTaskBecauseThePlayerIsGoneMeansFallback() {
        // Canvas: a retired entity's scheduler returns no task and runs neither callback; the items must not vanish.
        Handoffs<String> handoffs = new Handoffs<>();
        hand(handoffs, "paid items", (run, retired) -> Task.NONE);
        assertEquals(List.of("paid items"), this.fellBack);
        assertTrue(this.delivered.isEmpty());
        assertEquals(0, handoffs.pending());
    }

    @Test
    void aNullTaskMeansFallback() {
        Handoffs<String> handoffs = new Handoffs<>();
        hand(handoffs, "paid items", (run, retired) -> null);
        assertEquals(List.of("paid items"), this.fellBack);
    }

    @Test
    void aSchedulerThatRefusesWorkMeansFallback() {
        // While the plugin is being disabled the scheduler throws instead of returning a task.
        Handoffs<String> handoffs = new Handoffs<>();
        hand(handoffs, "paid items", (run, retired) -> {
            throw new IllegalStateException("Plugin attempted to register task while disabled");
        });
        assertEquals(List.of("paid items"), this.fellBack);
        assertEquals(0, handoffs.pending());
    }

    @Test
    void aPlayerWhoLeavesFirstGetsTheFallbackOnly() {
        Handoffs<String> handoffs = new Handoffs<>();
        Held held = new Held();
        hand(handoffs, "spawners", held);
        held.retired.run();
        held.run.run();
        assertEquals(List.of("spawners"), this.fellBack);
        assertTrue(this.delivered.isEmpty());
    }

    @Test
    void shutdownDrainsWhatNeverRan() {
        // Between the region scheduler halting and the plugin stopping, accepted tasks never run and their retired
        // callbacks only run after the plugin is disabled (so never): the drain in disable() is what saves them.
        Handoffs<String> handoffs = new Handoffs<>();
        Held first = new Held();
        Held second = new Held();
        hand(handoffs, "a", first);
        hand(handoffs, "b", second);
        first.run.run();
        handoffs.drain();
        assertEquals(List.of("a"), this.delivered);
        assertEquals(List.of("b"), this.fellBack);
        second.run.run();
        second.retired.run();
        assertEquals(List.of("a"), this.delivered, "a drained hand-off never runs late");
        assertEquals(List.of("b"), this.fellBack);
    }

    @Test
    void exactlyOnceWhenEverythingRaces() throws Exception {
        Handoffs<Integer> handoffs = new Handoffs<>();
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger fellBack = new AtomicInteger();
        List<Held> helds = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            Held held = new Held();
            helds.add(held);
            handoffs.hand(i, held, x -> delivered.incrementAndGet(), x -> fellBack.incrementAndGet());
        }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Void>> runs = new ArrayList<>();
            runs.add(CompletableFuture.runAsync(() -> {
                await(start);
                helds.forEach(h -> h.run.run());
            }, pool));
            runs.add(CompletableFuture.runAsync(() -> {
                await(start);
                helds.forEach(h -> h.retired.run());
            }, pool));
            runs.add(CompletableFuture.runAsync(() -> {
                await(start);
                handoffs.drain();
            }, pool));
            start.countDown();
            CompletableFuture.allOf(runs.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        handoffs.drain();
        assertEquals(2_000, delivered.get() + fellBack.get());
        assertEquals(0, handoffs.pending());
    }

    @Test
    void onceRunsExactlyOneSide() {
        AtomicReference<String> ran = new AtomicReference<>("");
        Handoffs.once((run, retired) -> Task.NONE, () -> ran.set(ran.get() + "task"), () -> ran.set(ran.get() + "instead"));
        assertEquals("instead", ran.get());

        ran.set("");
        Handoffs.once((run, retired) -> {
            throw new IllegalStateException("disabled");
        }, () -> ran.set(ran.get() + "task"), () -> ran.set(ran.get() + "instead"));
        assertEquals("instead", ran.get());

        ran.set("");
        Held held = new Held();
        Handoffs.once(held, () -> ran.set(ran.get() + "task"), () -> ran.set(ran.get() + "instead"));
        held.run.run();
        held.retired.run();
        held.run.run();
        assertEquals("task", ran.get());

        ran.set("");
        Held left = new Held();
        Handoffs.once(left, () -> ran.set(ran.get() + "task"), () -> ran.set(ran.get() + "instead"));
        left.retired.run();
        left.run.run();
        assertEquals("instead", ran.get());
    }

    @Test
    void shutdownWaitsForAnswersStillOnTheirWay() throws Exception {
        Handoffs<String> handoffs = new Handoffs<>();
        assertTrue(handoffs.awaitIdle(Duration.ZERO));
        handoffs.begin();
        assertFalse(handoffs.awaitIdle(Duration.ofMillis(30)));
        CompletableFuture<Boolean> idle = CompletableFuture.supplyAsync(() -> handoffs.awaitIdle(Duration.ofSeconds(10)));
        Thread.sleep(20);
        handoffs.end();
        assertTrue(idle.get(5, TimeUnit.SECONDS));
        assertEquals(0, handoffs.waiting());
        assertThrows(IllegalStateException.class, handoffs::end);
    }

    /**
     * Storage completes commits on two threads, so its flush can return before the callback of an earlier commit even
     * started. A commit counted with begin() before its callback is registered is waited for, so the hand-off that
     * callback starts is drained before storage closes; one that is not counted slips past the drain.
     */
    @Test
    void aCountedCommitCallbackThatRunsAfterTheFlushIsStillDrained() throws Exception {
        for (boolean counted : new boolean[] {true, false}) {
            Handoffs<String> handoffs = new Handoffs<>();
            List<String> fellBack = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> commit = new CompletableFuture<>();
            if (counted) {
                handoffs.begin();
            }
            commit.whenComplete((ignored, error) -> {
                try {
                    // The plugin is stopping: the player's scheduler takes the task but never runs it.
                    handoffs.hand("spawner loot", new Held(), payload -> { }, fellBack::add);
                } finally {
                    if (counted) {
                        handoffs.end();
                    }
                }
            });
            ExecutorService callbacks = Executors.newSingleThreadExecutor();
            CountDownLatch drained = new CountDownLatch(1);
            try {
                // The callback thread gets to the commit only after the flush returned: a moment later when it is
                // counted (shutdown must wait for it), only after the drain when it is not (it would have slipped past).
                callbacks.execute(() -> {
                    try {
                        if (counted) {
                            Thread.sleep(100);
                        } else {
                            drained.await(5, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    commit.complete(null);
                });
                boolean idle = handoffs.awaitIdle(Duration.ofSeconds(5));
                handoffs.drain();
                drained.countDown();
                assertTrue(idle);
                if (counted) {
                    assertEquals(List.of("spawner loot"), fellBack, "drained before storage closes");
                } else {
                    assertTrue(fellBack.isEmpty(), "not counted: the drain ran before the callback");
                }
            } finally {
                callbacks.shutdown();
                assertTrue(callbacks.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void closedRefusesNewWorkAndWaitsForWorkUnderWay() throws Exception {
        Handoffs<String> handoffs = new Handoffs<>();
        assertTrue(handoffs.beginUnlessClosed());
        handoffs.close();
        assertFalse(handoffs.beginUnlessClosed(), "nothing new starts once closed");
        assertEquals(1, handoffs.waiting(), "a refusal counts nothing");
        assertFalse(handoffs.awaitIdle(Duration.ofMillis(30)), "the work under way is waited for");
        handoffs.end();
        assertTrue(handoffs.awaitIdle(Duration.ZERO));
    }

    /** Whatever races with close(): once shutdown stopped waiting, no work can have started that it did not wait for. */
    @Test
    void nothingStartsAfterShutdownStoppedWaiting() throws Exception {
        for (int round = 0; round < 50; round++) {
            Handoffs<String> handoffs = new Handoffs<>();
            AtomicInteger lateStarts = new AtomicInteger();
            AtomicReference<Boolean> waited = new AtomicReference<>(false);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Void>> workers = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    workers.add(CompletableFuture.runAsync(() -> {
                        await(start);
                        for (int n = 0; n < 200; n++) {
                            if (handoffs.beginUnlessClosed()) {
                                if (waited.get()) {
                                    lateStarts.incrementAndGet();
                                }
                                Thread.onSpinWait();
                                handoffs.end();
                            }
                        }
                    }, pool));
                }
                start.countDown();
                handoffs.close();
                assertTrue(handoffs.awaitIdle(Duration.ofSeconds(10)));
                waited.set(true);
                CompletableFuture.allOf(workers.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(0, lateStarts.get(), "work started after shutdown stopped waiting");
            assertEquals(0, handoffs.waiting());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
