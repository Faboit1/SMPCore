package net.siftvanilla.siftcore.feature.boosters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The booster service on a real migrated SQLite database and the real ledger: staff and store boosters, the clock
 * (only time the server ran counts), refunds, and a restart that resumes the remaining time.
 */
class BoosterServiceTest {

    @TempDir
    Path dir;

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final AtomicReference<BoostersSettings> settings = new AtomicReference<>(settings(25));
    private JdbcDatabase database;
    private Ledger ledger;
    private BoosterService service;

    private static BoostersSettings settings(int maxPercent) {
        return new BoostersSettings(maxPercent, Duration.ofMinutes(1), Duration.ofDays(3), 2, 5,
            new BoostersSettings.Announce(true, true, true), new BoostersSettings.Bar(true, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS));
    }

    @BeforeEach
    void setUp() throws Exception {
        open();
    }

    private void open() throws Exception {
        Logger logger = Logger.getLogger("booster-test");
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, logger, 1_000_000_000_000L);
        this.ledger.load();
        this.service = new BoosterService(this.ledger, this.database, this.settings::get, new AtomicReference<>(), logger,
            this.clock::get, this.nanos::get);
        this.service.load();
    }

    /**
     * Stores the remaining time and stops cleanly, stays down for {@code down}, then starts again from the same files (a
     * restart).
     */
    private void restart(Duration down) throws Exception {
        this.service.storeRemaining();
        this.database.close();
        this.clock.addAndGet(down.toMillis());
        this.nanos.addAndGet(down.toNanos());
        open();
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    /** Lets {@code millis} of server time pass and ticks. */
    private void run(long millis) {
        this.nanos.addAndGet(millis * 1_000_000L);
        this.clock.addAndGet(millis);
        this.service.tick();
    }

    private String state(long id) throws Exception {
        this.database.flush();
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT state FROM boosters WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    /** What the store does: one transaction that puts a booster in line. */
    private TransactionResult deliver(String ref, int percent, Duration length) {
        LedgerTx.Builder tx = LedgerTx.builder().actor("console").silent();
        this.service.deliver(tx, new ServerBoosters.Grant(ServerBoosters.SELL, percent, length, UUID.randomUUID(), ref, "console"));
        return this.ledger.execute(tx.build());
    }

    private ServerBoosters.Revoked revoke(String ref) throws Exception {
        LedgerTx.Builder tx = LedgerTx.builder().actor("console").silent();
        AtomicReference<ServerBoosters.Revoked> outcome = this.service.revoke(tx, ref);
        TransactionResult result = this.ledger.execute(tx.build());
        assertTrue(result.success(), String.valueOf(result.reason()));
        result.committed().get(10, TimeUnit.SECONDS);
        return outcome.get();
    }

    @Test
    void staffBoostersStartQueueAndRespectTheLimits() throws Exception {
        BoosterService.Started first = this.service.start(10, Duration.ofMinutes(30), null, "event night", "console");
        assertNull(first.problem());
        assertEquals(Booster.State.ACTIVE, first.booster().state());
        assertEquals(10, this.service.percent());
        assertEquals("active", state(first.booster().id()));

        assertEquals("bad_percent", this.service.start(26, Duration.ofMinutes(30), null, null, "console").problem());
        assertEquals("bad_duration", this.service.start(10, Duration.ofSeconds(10), null, null, "console").problem());

        assertEquals(Booster.State.QUEUED, this.service.start(20, Duration.ofMinutes(5), null, null, "console").booster().state());
        assertEquals(Booster.State.QUEUED, this.service.start(20, Duration.ofMinutes(5), null, null, "console").booster().state());
        assertEquals("queue_full", this.service.start(20, Duration.ofMinutes(5), null, null, "console").problem(),
            "staff can't add past queue.staff-limit");
        assertEquals(10, this.service.percent(), "waiting boosters never add up");
        assertEquals(2, this.service.view().waiting().size());
    }

    @Test
    void storeBoostersAlwaysQueueAndRunBackToBack() throws Exception {
        assertTrue(deliver("tbx-1", 10, Duration.ofMinutes(30)).success());
        assertTrue(deliver("tbx-2", 15, Duration.ofMinutes(30)).success());
        assertTrue(deliver("tbx-3", 15, Duration.ofMinutes(30)).success());
        assertTrue(deliver("tbx-4", 15, Duration.ofMinutes(30)).success(), "a purchase is never refused for a long line");
        assertEquals(3, this.service.view().waiting().size());
        assertEquals(10, this.service.percent());
        assertEquals(new ServerBoosters.Status(false, 15, Duration.ofMinutes(30), 1), this.service.status("tbx-2").orElseThrow());

        run(Duration.ofMinutes(30).toMillis() + 500);
        assertEquals(15, this.service.percent(), "the second booster took over right when the first ran out");
        assertEquals("tbx-2", this.service.view().active().ref());
        assertEquals(Duration.ofMinutes(30).toMillis() - 500, this.service.view().active().remaining());
        assertTrue(this.service.status("tbx-1").isEmpty(), "a booster that ran out has no status");
        assertEquals("ended", state(this.service.view().active().id() - 1));
        assertTrue(this.service.finished(this.service.view().active().id() - 1).isPresent());
    }

    @Test
    void theRemainingTimeSurvivesARestartAndOnlyCountsWhileRunning() throws Exception {
        assertTrue(deliver("tbx-9", 10, Duration.ofMinutes(30)).success());
        assertTrue(deliver("tbx-10", 20, Duration.ofMinutes(10)).success());
        run(Duration.ofMinutes(12).toMillis());
        // The server is down for hours: none of that counts.
        restart(Duration.ofHours(5));
        run(0);
        BoosterService.View view = this.service.view();
        assertNotNull(view.active());
        assertEquals("tbx-9", view.active().ref());
        assertEquals(Duration.ofMinutes(18).toMillis(), view.active().remaining(), "it resumes with the 18 minutes it had left");
        assertEquals(1, view.waiting().size());
        assertEquals(10, this.service.percent());
        run(Duration.ofMinutes(18).toMillis());
        assertEquals(20, this.service.percent());
        assertEquals("tbx-10", this.service.view().active().ref());
    }

    @Test
    void refundsEndTheRunningBoosterAndRemoveWaitingOnes() throws Exception {
        assertTrue(deliver("tbx-a", 10, Duration.ofMinutes(30)).success());
        assertTrue(deliver("tbx-b", 15, Duration.ofMinutes(30)).success());
        assertTrue(deliver("tbx-c", 20, Duration.ofMinutes(30)).success());
        long b = this.service.view().waiting().getFirst().id();
        long c = this.service.view().waiting().getLast().id();
        assertEquals(ServerBoosters.Revoked.REMOVED, revoke("tbx-c"));
        assertEquals("revoked", state(c));
        assertEquals(1, this.service.view().waiting().size());
        assertEquals(ServerBoosters.Revoked.ENDED, revoke("tbx-a"));
        assertEquals(15, this.service.percent(), "the next booster starts when a refund ends the running one");
        assertEquals("active", state(b));
        assertEquals(ServerBoosters.Revoked.OVER, revoke("tbx-a"), "a booster that is over changes nothing");
        assertEquals(ServerBoosters.Revoked.OVER, revoke("unknown-ref"));
        assertEquals(15, this.service.percent());
    }

    @Test
    void staffStopEndsTheRunningBoosterOrTakesOneOutOfLine() throws Exception {
        Booster first = this.service.start(10, Duration.ofMinutes(30), UUID.randomUUID(), null, "staff").booster();
        Booster second = this.service.start(20, Duration.ofMinutes(30), null, null, "console").booster();
        Booster third = this.service.start(30 - 5, Duration.ofMinutes(30), null, null, "console").booster();
        assertEquals(Booster.State.STOPPED, this.service.stop(third.id()).orElseThrow().state());
        assertEquals("stopped", state(third.id()));
        assertEquals(first.id(), this.service.stop(null).orElseThrow().id(), "no id stops the running booster");
        assertEquals(second.id(), this.service.view().active().id());
        assertTrue(this.service.stop(999L).isEmpty());
    }

    /** Waits on the database writer until the test lets the commit go on; false when it never does. */
    private static boolean held(CountDownLatch release) throws SQLException {
        try {
            return release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted", e);
        }
    }

    /** A store delivery whose database commit waits for {@code release}, then succeeds or fails. */
    private TransactionResult deliverHeld(String ref, int percent, CountDownLatch release, boolean fail) {
        LedgerTx.Builder tx = LedgerTx.builder().actor("console").silent();
        this.service.deliver(tx, new ServerBoosters.Grant(ServerBoosters.SELL, percent, Duration.ofMinutes(30), UUID.randomUUID(), ref,
            "console"));
        tx.write(c -> {
            if (!held(release)) {
                throw new SQLException("never released");
            }
            if (fail) {
                throw new SQLException("disk on fire");
            }
            return null;
        });
        return this.ledger.execute(tx.build());
    }

    /** What the announcer would say now. */
    private List<LineWatch.Change> look(LineWatch watch) {
        BoosterService.View view = this.service.view();
        return watch.look(view.active(), view.waiting(), this.service.settled());
    }

    @Test
    void aBoosterIsOnlyAnnouncedOnceItsTransactionIsStored() throws Exception {
        LineWatch watch = new LineWatch();
        watch.prime(null, List.of());
        CountDownLatch release = new CountDownLatch(1);
        TransactionResult result = deliverHeld("tbx-held", 10, release, false);
        assertTrue(result.success());
        assertEquals(10, this.service.percent(), "prices rise at once");
        assertFalse(this.service.settled(), "but the delivery is not stored yet");
        assertEquals(List.of(), look(watch), "so nothing is announced");
        release.countDown();
        result.committed().get(10, TimeUnit.SECONDS);
        assertTrue(this.service.settled());
        List<LineWatch.Change> changes = look(watch);
        assertEquals(1, changes.size(), changes.toString());
        assertEquals("tbx-held", assertInstanceOf(LineWatch.Started.class, changes.getFirst()).booster().ref());
        assertEquals(List.of(), look(watch), "announced once");
    }

    @Test
    void aBoosterWhoseTransactionWasTakenBackIsNeverAnnounced() throws Exception {
        LineWatch watch = new LineWatch();
        deliver("tbx-first", 10, Duration.ofMinutes(30)).committed().get(10, TimeUnit.SECONDS);
        BoosterService.View before = this.service.view();
        watch.prime(before.active(), before.waiting());

        CountDownLatch release = new CountDownLatch(1);
        TransactionResult running = deliverHeld("tbx-gone", 20, release, true);
        assertTrue(running.success());
        assertEquals(1, this.service.view().waiting().size(), "it waits in line for now");
        assertEquals(List.of(), look(watch));
        release.countDown();
        assertThrows(Exception.class, () -> running.committed().get(10, TimeUnit.SECONDS));
        assertTrue(this.service.settled(), "the undo settles it too");
        assertEquals(0, this.service.view().waiting().size(), "the booster that was never stored is gone");
        assertEquals(List.of(), look(watch), "and it was never announced, not even as ended");

        // The same with the line empty: it would have started at once.
        assertTrue(this.service.stop(null).isPresent());
        assertInstanceOf(LineWatch.Ended.class, look(watch).getFirst());
        CountDownLatch again = new CountDownLatch(1);
        TransactionResult started = deliverHeld("tbx-gone-2", 20, again, true);
        assertTrue(started.success());
        assertEquals(20, this.service.percent());
        assertEquals(List.of(), look(watch));
        again.countDown();
        assertThrows(Exception.class, () -> started.committed().get(10, TimeUnit.SECONDS));
        assertEquals(0, this.service.percent());
        assertEquals(List.of(), look(watch), "no 'started', no 'ended'");
    }

    @Test
    void aRefundIsOnlyAnnouncedOnceStoredAndARevertedOneNever() throws Exception {
        deliver("tbx-keep", 10, Duration.ofMinutes(30)).committed().get(10, TimeUnit.SECONDS);
        LineWatch watch = new LineWatch();
        BoosterService.View before = this.service.view();
        watch.prime(before.active(), before.waiting());

        CountDownLatch release = new CountDownLatch(1);
        LedgerTx.Builder tx = LedgerTx.builder().actor("console").silent();
        AtomicReference<ServerBoosters.Revoked> outcome = this.service.revoke(tx, "tbx-keep");
        tx.write(c -> {
            if (!held(release)) {
                throw new SQLException("never released");
            }
            throw new SQLException("disk on fire");
        });
        TransactionResult result = this.ledger.execute(tx.build());
        assertTrue(result.success());
        assertEquals(ServerBoosters.Revoked.ENDED, outcome.get());
        assertEquals(0, this.service.percent());
        assertEquals(List.of(), look(watch), "not announced while it could still be taken back");
        release.countDown();
        assertThrows(Exception.class, () -> result.committed().get(10, TimeUnit.SECONDS));
        assertEquals(10, this.service.percent(), "the refund was taken back: the booster runs again");
        assertEquals(List.of(), look(watch), "and nobody was told it ended");

        assertEquals(ServerBoosters.Revoked.ENDED, revoke("tbx-keep"));
        List<LineWatch.Change> changes = look(watch);
        assertEquals(1, changes.size(), changes.toString());
        assertInstanceOf(LineWatch.Ended.class, changes.getFirst());
    }

    @Test
    void everythingShownIsWhatSalesPay() throws Exception {
        this.settings.set(settings(10));
        assertNull(this.service.problem(ServerBoosters.SELL, 40, Duration.ofMinutes(30)),
            "a paid booster above max-percent is accepted (its package may predate the lower limit)");
        assertNull(this.service.problem(ServerBoosters.SELL, 10, Duration.ofDays(20)), "store lengths only need the hard limits");
        assertEquals("bad_percent", this.service.problem(ServerBoosters.SELL, ServerBoosters.PERCENT_CAP + 1, Duration.ofMinutes(30)));
        assertEquals("bad_percent", this.service.problem(ServerBoosters.SELL, 0, Duration.ofMinutes(30)));
        assertEquals("bad_duration", this.service.problem(ServerBoosters.SELL, 10, Duration.ofSeconds(59)));
        assertEquals("bad_duration", this.service.problem(ServerBoosters.SELL, 10, ServerBoosters.MAX_LENGTH.plusSeconds(1)));
        assertEquals("bad_percent", this.service.start(40, Duration.ofMinutes(30), null, null, "console").problem(),
            "staff stay within max-percent");

        assertTrue(deliver("tbx-big", 40, Duration.ofMinutes(30)).success());
        assertTrue(deliver("tbx-next", 25, Duration.ofMinutes(30)).success());
        Booster big = this.service.view().active();
        assertEquals(40, big.percent(), "stored as bought");
        assertEquals(10, this.service.paid(big));
        assertEquals(10, this.service.percent());
        assertEquals(10, this.service.status("tbx-big").orElseThrow().percent(), "/purchases and the buyer see what it pays");
        assertEquals(10, this.service.status("tbx-next").orElseThrow().percent());
        assertEquals(10, this.service.paid(40));
        assertEquals(7, this.service.paid(7));
        this.settings.set(settings(50));
        assertEquals(40, this.service.percent(), "raising the limit again lets it pay what was bought");
    }

    @Test
    void theRunningBoosterNeverGoesPastTheCurrentLimit() {
        assertNull(this.service.start(25, Duration.ofMinutes(30), null, null, "console").problem());
        assertEquals(25, this.service.percent());
        this.settings.set(settings(10));
        assertEquals(10, this.service.percent(), "a reload that lowers max-percent also caps the running booster");
        assertEquals(10, this.service.maxPercent());
        assertEquals(10, this.service.latestMaxPercent());
        assertEquals("unknown_kind", this.service.problem("xp", 10, Duration.ofMinutes(5)));
        assertNull(this.service.problem(ServerBoosters.SELL, 10, Duration.ofMinutes(5)));
    }
}
