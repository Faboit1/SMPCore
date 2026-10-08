package net.siftvanilla.siftcore.feature.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatsStoreTest {

    private static final UUID ALEX = new UUID(10, 1);
    private static final UUID BLAKE = new UUID(10, 2);
    private static final Duration KEEP = Duration.ofMinutes(5);

    private final AtomicLong now = new AtomicLong(1_000_000);
    private FakeStorage storage;
    private StatsStore store;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("stats-test");
        logger.setLevel(Level.OFF);
        this.storage = new FakeStorage();
        this.store = new StatsStore(this.storage, logger, this.now::get, KEEP);
    }

    private void joinLoaded(UUID player) throws Exception {
        this.store.load(player).get(5, TimeUnit.SECONDS);
        this.store.join(player);
    }

    @Test
    void savesNeverCountAChangeTwice() throws Exception {
        this.storage.put(ALEX, new StatsSnapshot(10, 2, 1, 4, 0, 50, 0, 600));
        joinLoaded(ALEX);
        for (int i = 0; i < 3; i++) {
            this.store.add(ALEX, StatsRecorder.Stat.BLOCKS_MINED, 1);
        }
        this.store.save().get();
        assertEquals(53, this.storage.row(ALEX).blocks());
        this.store.add(ALEX, StatsRecorder.Stat.BLOCKS_MINED, 2);
        this.store.save().get();
        this.store.save().get();
        this.store.save().get();
        assertEquals(55, this.storage.row(ALEX).blocks());
        assertEquals(55, this.store.current(ALEX).blocks());
        assertEquals(2, this.storage.writeCalls.get(), "saves with nothing pending write nothing");
        assertEquals(this.storage.row(ALEX), this.store.current(ALEX));
    }

    @Test
    void changesMadeWhileASaveRunsAreKeptForTheNextOne() throws Exception {
        joinLoaded(ALEX);
        this.storage.manual(true);
        this.store.add(ALEX, StatsRecorder.Stat.MOBS_KILLED, 3);
        this.store.save();
        this.store.add(ALEX, StatsRecorder.Stat.MOBS_KILLED, 2);
        assertEquals(5, this.store.current(ALEX).mobs());
        // A second save while the first is still running does not write this player again.
        this.store.save();
        assertEquals(1, this.storage.heldWrites());
        this.storage.completeWrite();
        assertEquals(3, this.storage.row(ALEX).mobs());
        assertEquals(5, this.store.current(ALEX).mobs());
        this.store.save();
        this.storage.completeWrite();
        assertEquals(5, this.storage.row(ALEX).mobs());
        assertEquals(5, this.store.current(ALEX).mobs());
    }

    @Test
    void aFailedSaveIsRetriedInEventOrder() throws Exception {
        this.storage.put(ALEX, new StatsSnapshot(0, 0, 4, 4, 0, 0, 0, 0));
        joinLoaded(ALEX);
        this.store.kill(ALEX, BLAKE);
        this.store.kill(ALEX, BLAKE);
        this.store.death(ALEX);
        this.storage.failNextWrites(1);
        CompletableFuture<Void> failed = this.store.save();
        assertTrue(failed.isCompletedExceptionally());
        assertEquals(2, this.store.failing(), "both players of the failed write");
        this.store.kill(ALEX, BLAKE);
        this.store.save().get();
        assertEquals(0, this.store.failing());
        StatsSnapshot stored = this.storage.row(ALEX);
        // 4 + 2 kills = 6 best, then death, then 1 kill.
        assertEquals(1, stored.streak());
        assertEquals(6, stored.bestStreak());
        assertEquals(3, stored.kills());
        assertEquals(1, stored.deaths());
        assertEquals(stored, this.store.current(ALEX));
        // Blake (never loaded) died three times; the failed save's deaths were retried with the third.
        assertEquals(3, this.storage.row(BLAKE).deaths());
    }

    @Test
    void aRowTheDatabaseRefusesHoldsBackNobodyElse() throws Exception {
        joinLoaded(ALEX);
        joinLoaded(BLAKE);
        this.storage.refuse(ALEX);
        this.store.add(ALEX, StatsRecorder.Stat.KILLS, 5);
        this.store.add(BLAKE, StatsRecorder.Stat.MOBS_KILLED, 3);
        CompletableFuture<Void> partly = this.store.save();
        assertTrue(partly.isCompletedExceptionally(), "the save reports that something was not stored");
        assertEquals(3, this.storage.row(BLAKE).mobs(), "Blake's change is stored in the same save");
        assertEquals(0, this.storage.row(ALEX).kills());
        assertEquals(1, this.store.failing());
        assertEquals(5, this.store.current(ALEX).kills(), "Alex's change stays in memory");
        // Further saves keep retrying Alex while Blake's new changes keep landing.
        this.store.add(BLAKE, StatsRecorder.Stat.MOBS_KILLED, 1);
        this.store.add(ALEX, StatsRecorder.Stat.KILLS, 1);
        assertTrue(this.store.save().isCompletedExceptionally());
        assertEquals(4, this.storage.row(BLAKE).mobs());
        assertEquals(6, this.store.current(ALEX).kills());
        // Once the database takes the row again, everything Alex earned is stored exactly once.
        this.storage.accept(ALEX);
        this.store.save().get();
        assertEquals(6, this.storage.row(ALEX).kills());
        assertEquals(0, this.store.failing());
        assertEquals(0, this.store.unsaved());
    }

    @Test
    void offlinePlayersAreUpdatedWithoutLoading() throws Exception {
        this.storage.put(BLAKE, new StatsSnapshot(0, 0, 0, 0, 0, 0, 1_000, 0));
        this.store.add(BLAKE, StatsRecorder.Stat.MONEY_EARNED, 500);
        this.store.add(BLAKE, StatsRecorder.Stat.MONEY_EARNED, 250);
        assertNull(this.store.current(BLAKE), "not loaded, so no value is invented");
        assertEquals(0, this.store.get(BLAKE, StatsRecorder.Stat.MONEY_EARNED));
        this.store.save().get();
        assertEquals(1_750, this.storage.row(BLAKE).earned());
        assertEquals(0, this.storage.loadCalls.get());
        assertEquals(0, this.store.size(), "nothing left to keep in memory");
    }

    @Test
    void aLoadWaitsForTheRunningWriteOfThatPlayer() throws Exception {
        this.storage.put(BLAKE, new StatsSnapshot(5, 0, 0, 0, 0, 0, 0, 0));
        this.storage.manual(true);
        this.store.add(BLAKE, StatsRecorder.Stat.KILLS, 2);
        this.store.save();
        CompletableFuture<StatsSnapshot> loading = this.store.load(BLAKE);
        assertEquals(0, this.storage.loadCalls.get(), "must not read while a write is running");
        this.storage.completeWrite();
        assertEquals(1, this.storage.loadCalls.get());
        this.store.add(BLAKE, StatsRecorder.Stat.KILLS, 1);
        this.storage.completeLoad();
        assertEquals(8, loading.get(5, TimeUnit.SECONDS).kills(), "5 stored + 2 written once + 1 pending");
        assertEquals(8, this.store.current(BLAKE).kills());
        // Concurrent requests share one load.
        assertTrue(this.store.load(BLAKE).isDone());
        assertEquals(1, this.storage.loadCalls.get());
    }

    @Test
    void changesDuringALoadAreAddedOnTop() throws Exception {
        this.storage.put(ALEX, new StatsSnapshot(10, 0, 0, 0, 0, 0, 0, 0));
        this.storage.manual(true);
        CompletableFuture<StatsSnapshot> first = this.store.load(ALEX);
        CompletableFuture<StatsSnapshot> second = this.store.load(ALEX);
        this.store.kill(ALEX, BLAKE);
        // A save cannot write Alex while the load runs.
        this.store.save();
        assertEquals(1, this.storage.heldWrites(), "only Blake's death is written");
        this.storage.completeLoad();
        assertEquals(11, first.get(5, TimeUnit.SECONDS).kills());
        assertEquals(11, second.get(5, TimeUnit.SECONDS).kills());
        assertEquals(1, this.storage.loadCalls.get());
        this.storage.completeWrite();
        this.store.save();
        this.storage.completeWrite();
        assertEquals(11, this.storage.row(ALEX).kills());
    }

    @Test
    void aFailedLoadCanBeRetried() throws Exception {
        StatsStorage broken = new StatsStorage() {
            private int calls;

            @Override
            public CompletableFuture<StatsSnapshot> load(UUID player) {
                return ++this.calls == 1 ? CompletableFuture.failedFuture(new java.sql.SQLException("down"))
                    : CompletableFuture.completedFuture(new StatsSnapshot(7, 0, 0, 0, 0, 0, 0, 0));
            }

            @Override
            public CompletableFuture<java.util.Map<UUID, String>> write(List<PendingWrite> writes) {
                return CompletableFuture.completedFuture(java.util.Map.of());
            }

            @Override
            public CompletableFuture<java.util.Map<Board, List<Leaderboard.Row>>> top(int limit, long kdrMinKills) {
                return CompletableFuture.completedFuture(java.util.Map.of());
            }
        };
        Logger logger = Logger.getLogger("stats-test-broken");
        logger.setLevel(Level.OFF);
        StatsStore store = new StatsStore(broken, logger, this.now::get, KEEP);
        store.join(ALEX);
        assertFalse(store.loaded(ALEX));
        assertEquals(List.of(ALEX), store.notLoaded(List.of(ALEX)));
        store.retryLoads();
        assertTrue(store.loaded(ALEX));
        assertEquals(7, store.current(ALEX).kills());
    }

    @Test
    void offlineRecordsStayCachedForAWhileThenGo() throws Exception {
        joinLoaded(ALEX);
        this.store.add(ALEX, StatsRecorder.Stat.KILLS, 1);
        assertEquals(0, this.store.sweep(), "online players are never dropped");
        this.store.quit(ALEX);
        assertEquals(1, this.storage.row(ALEX).kills(), "quitting saves right away");
        assertEquals(0, this.store.sweep());
        assertTrue(this.store.loaded(ALEX), "kept for a quick rejoin");
        this.now.addAndGet(KEEP.toMillis());
        assertEquals(1, this.store.sweep());
        assertEquals(0, this.store.size());
    }

    @Test
    void recorderViewOfOnlinePlayers() throws Exception {
        joinLoaded(ALEX);
        StatsRecorder recorder = this.store;
        recorder.kill(ALEX, BLAKE);
        recorder.kill(ALEX, BLAKE);
        assertEquals(2, recorder.streak(ALEX));
        assertEquals(2, recorder.bestStreak(ALEX));
        recorder.death(ALEX);
        assertEquals(0, recorder.streak(ALEX));
        assertEquals(2, recorder.bestStreak(ALEX));
        assertEquals(2, recorder.get(ALEX, StatsRecorder.Stat.KILLS));
        assertEquals(1, recorder.get(ALEX, StatsRecorder.Stat.DEATHS));
        // Killing yourself is a death, never a kill.
        recorder.kill(ALEX, ALEX);
        assertEquals(2, recorder.get(ALEX, StatsRecorder.Stat.KILLS));
        assertEquals(2, recorder.get(ALEX, StatsRecorder.Stat.DEATHS));
        recorder.add(ALEX, StatsRecorder.Stat.PLAYTIME_SECONDS, 0);
        recorder.add(ALEX, StatsRecorder.Stat.PLAYTIME_SECONDS, -5);
        assertEquals(0, recorder.get(ALEX, StatsRecorder.Stat.PLAYTIME_SECONDS));
    }

    @Test
    void staffCorrectionsAreSavedRightAway() throws Exception {
        this.storage.put(BLAKE, new StatsSnapshot(40, 10, 3, 9, 1, 1, 1, 1));
        this.store.set(BLAKE, Counter.KILLS, 100).get();
        assertEquals(100, this.storage.row(BLAKE).kills());
        assertEquals(10, this.storage.row(BLAKE).deaths());
        this.store.give(BLAKE, Counter.PLAYTIME, 3_600).get();
        assertEquals(3_601, this.storage.row(BLAKE).playtime());
        this.store.reset(BLAKE).get();
        assertEquals(StatsSnapshot.ZERO, this.storage.row(BLAKE));
    }

    @Test
    void closeStoresEverythingAndLateChangesStillLand() throws Exception {
        joinLoaded(ALEX);
        this.store.add(ALEX, StatsRecorder.Stat.BLOCKS_MINED, 9);
        this.store.add(BLAKE, StatsRecorder.Stat.MONEY_EARNED, 70);
        this.store.close(Duration.ofSeconds(5));
        assertEquals(9, this.storage.row(ALEX).blocks());
        assertEquals(70, this.storage.row(BLAKE).earned());
        this.store.add(BLAKE, StatsRecorder.Stat.MONEY_EARNED, 30);
        assertEquals(100, this.storage.row(BLAKE).earned());
    }

    @Test
    void savesAfterCloseWriteNothingAndEachLateChangeIsWrittenOnce() throws Exception {
        joinLoaded(ALEX);
        this.store.add(ALEX, StatsRecorder.Stat.KILLS, 2);
        this.store.close(Duration.ofSeconds(5));
        int writes = this.storage.writeCalls.get();
        assertTrue(this.store.save().isDone());
        assertTrue(this.store.save(ALEX).isDone());
        assertEquals(writes, this.storage.writeCalls.get(), "the final save already took everything");
        this.store.add(ALEX, StatsRecorder.Stat.KILLS, 1);
        assertEquals(writes + 1, this.storage.writeCalls.get(), "a late change is written straight away");
        this.store.save();
        this.store.close(Duration.ofSeconds(5));
        assertEquals(3, this.storage.row(ALEX).kills());
        assertEquals(3, this.store.current(ALEX).kills());
    }

    @Test
    void closeWaitsForARunningSaveAndRetriesItIfItFailed() throws Exception {
        joinLoaded(ALEX);
        this.storage.manual(true);
        this.store.add(ALEX, StatsRecorder.Stat.KILLS, 4);
        this.store.save();
        this.store.add(ALEX, StatsRecorder.Stat.KILLS, 1);
        this.storage.failNextWrites(1);
        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(100);
                this.storage.manual(false);
                this.storage.completeWrite();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        completer.start();
        this.store.close(Duration.ofSeconds(5));
        completer.join();
        assertEquals(5, this.storage.row(ALEX).kills());
    }

    /** Many threads recording while saves run concurrently: every change lands exactly once. */
    @Test
    void concurrentRecordingAndSavingLosesAndDuplicatesNothing() throws Exception {
        int players = 40;
        int threads = 8;
        int perThread = 20_000;
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            uuids.add(new UUID(42, i));
            if (i % 2 == 0) {
                joinLoaded(uuids.get(i));
            }
        }
        long[] expected = new long[players];
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean recording = new AtomicBoolean(true);
        List<Future<long[]>> work = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int seed = t;
            work.add(pool.submit(() -> {
                Random random = new Random(seed);
                long[] mine = new long[players];
                start.await();
                for (int i = 0; i < perThread; i++) {
                    int p = random.nextInt(players);
                    long amount = 1 + random.nextInt(3);
                    this.store.add(uuids.get(p), StatsRecorder.Stat.BLOCKS_MINED, amount);
                    mine[p] += amount;
                }
                return mine;
            }));
        }
        Future<?> saver = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Random random = new Random(1);
            while (recording.get()) {
                this.store.save();
                if (random.nextInt(4) == 0) {
                    this.store.sweep();
                }
            }
        });
        start.countDown();
        for (Future<long[]> future : work) {
            long[] mine = future.get(60, TimeUnit.SECONDS);
            for (int i = 0; i < players; i++) {
                expected[i] += mine[i];
            }
        }
        recording.set(false);
        saver.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        while (this.store.unsaved() > 0) {
            this.store.save().get(5, TimeUnit.SECONDS);
        }
        for (int i = 0; i < players; i++) {
            assertEquals(expected[i], this.storage.row(uuids.get(i)).blocks(), "player " + i);
            if (i % 2 == 0) {
                assertEquals(expected[i], this.store.current(uuids.get(i)).blocks(), "memory of loaded player " + i);
            }
        }
    }

    /** Random events, saves, failures and loads in any interleaving end with storage equal to the event model. */
    @Test
    void randomScheduleMatchesTheEventModel() throws Exception {
        Random random = new Random(2024);
        for (int round = 0; round < 300; round++) {
            setUp();
            StatsSnapshot initial = new StatsSnapshot(random.nextInt(20), random.nextInt(20), 2, 5, random.nextInt(9), 0, 0, 0);
            this.storage.put(ALEX, initial);
            StatsSnapshot model = initial;
            this.storage.manual(true);
            boolean loadRequested = false;
            for (int step = 0; step < 40; step++) {
                int action = random.nextInt(10);
                if (action < 5) {
                    StatsDelta event = StatsDeltaTest.randomEvent(random);
                    this.store.record(ALEX, event);
                    model = event.applyTo(model);
                } else if (action == 5) {
                    this.store.save();
                } else if (action == 6 && this.storage.heldWrites() > 0) {
                    if (random.nextInt(3) == 0) {
                        this.storage.failNextWrites(1);
                    }
                    this.storage.completeWrite();
                } else if (action == 7 && !loadRequested) {
                    this.store.load(ALEX);
                    loadRequested = true;
                } else if (action == 8 && this.storage.heldLoads() > 0) {
                    this.storage.completeLoad();
                }
            }
            this.storage.manual(false);
            this.storage.failNextWrites(0);
            while (this.storage.heldLoads() > 0 || this.storage.heldWrites() > 0) {
                if (this.storage.heldWrites() > 0) {
                    this.storage.completeWrite();
                } else {
                    this.storage.completeLoad();
                }
            }
            for (int i = 0; i < 5 && this.store.unsaved() > 0; i++) {
                this.store.save().get(5, TimeUnit.SECONDS);
            }
            assertEquals(model, this.storage.row(ALEX), "round " + round);
            if (this.store.loaded(ALEX)) {
                assertEquals(model, this.store.current(ALEX), "memory, round " + round);
            }
        }
    }
}
