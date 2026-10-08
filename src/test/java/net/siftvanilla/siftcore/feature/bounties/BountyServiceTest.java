package net.siftvanilla.siftcore.feature.bounties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bounty money flows against a real ledger and SQLite: placing and stacking, claiming with tax, sponsors who
 * kill their own target, expiry and staff refunds, storage failures, restarts and concurrency. After every test the
 * escrow invariant (escrow balance = active contributions, in memory and in storage) and the ledger audit hold.
 */
class BountyServiceTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Duration FOURTEEN_DAYS = Duration.ofDays(14);

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Ledger ledger;
    private BountyService service;

    @BeforeEach
    void setUp() throws Exception {
        this.database = BountiesTestDatabase.open(this.dir);
        this.ledger = new Ledger(this.database, Logger.getLogger("bounty-test"), 1_000_000_000_000_000L);
        this.ledger.load();
        this.service = new BountyService(this.ledger, this.database, new BountyBook());
        this.service.load();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            assertInvariants();
        } finally {
            this.database.close();
        }
    }

    private void assertInvariants() throws Exception {
        assertNull(this.service.checkEscrow());
        assertNull(this.service.checkStoredEscrow().get(10, TimeUnit.SECONDS));
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), report.problems().toString());
    }

    private UUID player(long money) {
        UUID player = UUID.randomUUID();
        if (money > 0) {
            assertTrue(this.ledger.execute(LedgerTx.builder().source(player, Currency.MONEY, money, "test_mint", null).build()).success());
        }
        return player;
    }

    private long money(UUID player) {
        return this.ledger.balance(player, Currency.MONEY);
    }

    private long escrow() {
        return this.ledger.balance(BountyService.ESCROW, Currency.MONEY);
    }

    private BountyService.Placed place(UUID sponsor, UUID target, long amount, long at) throws Exception {
        BountyService.Placed placed = this.service.place(sponsor, target, amount, at);
        placed.result().committed().get(10, TimeUnit.SECONDS);
        return placed;
    }

    private String storedState(long id) throws Exception {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT state FROM bounties WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private long ledgerSum(String kind) throws Exception {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(ABS(delta)), 0) FROM ledger WHERE kind = ? AND delta < 0")) {
                ps.setString(1, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    @Test
    void placingMovesMoneyToEscrowAndStacks() throws Exception {
        UUID alice = player(50_000);
        UUID bob = player(50_000);
        UUID target = player(0);
        BountyService.Placed first = place(alice, target, 10_000, NOW);
        assertTrue(first.result().success());
        assertEquals(10_000, first.totalAfter());
        BountyService.Placed second = place(bob, target, 5_000, NOW + 1);
        BountyService.Placed third = place(alice, target, 2_500, NOW + 2);
        assertEquals(17_500, third.totalAfter());
        assertEquals(17_500, escrow());
        assertEquals(37_500, money(alice));
        assertEquals(45_000, money(bob));
        BountyBook.Bounty bounty = this.service.book().get(target);
        assertEquals(3, bounty.contributions().size());
        assertEquals(2, bounty.sponsors());
        assertEquals(BountyService.ACTIVE, storedState(second.contribution().id()));
        assertEquals(17_500, ledgerSum(BountyService.KIND_PLACE));
    }

    @Test
    void aPlacementWithoutTheMoneyChangesNothing() throws Exception {
        UUID poor = player(999);
        UUID target = player(0);
        BountyService.Placed placed = this.service.place(poor, target, 1_000, NOW);
        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, placed.result().status());
        assertNull(this.service.book().get(target));
        assertEquals(0, escrow());
        assertEquals(999, money(poor));
        assertNull(storedState(placed.contribution().id()));
    }

    @Test
    void placementInputsAreChecked() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(BountyService.PlaceProblem.YOURSELF, BountyService.check(a, a, 5_000, 1_000));
        assertEquals(BountyService.PlaceProblem.BELOW_MINIMUM, BountyService.check(a, b, 999, 1_000));
        assertEquals(BountyService.PlaceProblem.BELOW_MINIMUM, BountyService.check(a, b, 0, 0), "never zero");
        assertNull(BountyService.check(a, b, 1_000, 1_000));
        assertThrows(IllegalArgumentException.class, () -> this.service.place(a, a, 5_000, NOW));
        assertThrows(IllegalArgumentException.class, () -> this.service.place(a, b, 0, NOW));
    }

    @Test
    void claimingPaysTheKillerMinusTax() throws Exception {
        UUID alice = player(100_000);
        UUID bob = player(100_000);
        UUID target = player(0);
        UUID killer = player(0);
        place(alice, target, 30_000, NOW);
        place(bob, target, 20_000, NOW + 1);
        Optional<BountyService.Plan> plan = this.service.plan(killer, target, 10);
        assertTrue(plan.isPresent());
        assertEquals(50_000, plan.get().split().total());
        assertEquals(5_000, plan.get().split().tax());
        assertEquals(List.of(alice, bob), plan.get().sponsors());
        TransactionResult result = this.service.claim(plan.get(), NOW + 10);
        assertTrue(result.success());
        result.committed().get(10, TimeUnit.SECONDS);
        assertEquals(45_000, money(killer));
        assertEquals(0, escrow());
        assertNull(this.service.book().get(target));
        for (BountyBook.Contribution contribution : plan.get().contributions()) {
            assertEquals(BountyService.CLAIMED, storedState(contribution.id()));
        }
        assertEquals(5_000, ledgerSum(BountyService.KIND_TAX));
        assertEquals(45_000, ledgerSum(BountyService.KIND_CLAIM));
        assertTrue(this.service.plan(killer, target, 10).isEmpty(), "nothing left to claim");
    }

    @Test
    void aStalePlanIsRejectedAndChangesNothing() throws Exception {
        UUID alice = player(100_000);
        UUID target = player(0);
        UUID first = player(0);
        UUID second = player(0);
        place(alice, target, 10_000, NOW);
        BountyService.Plan planA = this.service.plan(first, target, 10).orElseThrow();
        BountyService.Plan planB = this.service.plan(second, target, 10).orElseThrow();
        assertTrue(this.service.claim(planA, NOW).success());
        TransactionResult late = this.service.claim(planB, NOW);
        assertEquals(TransactionStatus.REJECTED, late.status(), "one bounty is paid once");
        assertEquals("changed", late.reason());
        assertEquals(9_000, money(first));
        assertEquals(0, money(second));
    }

    @Test
    void sponsorsKeepTheirOwnPartWhenTheyKillTheTarget() throws Exception {
        UUID alice = player(100_000);
        UUID bob = player(100_000);
        UUID target = player(0);
        place(alice, target, 40_000, NOW);
        place(bob, target, 10_000, NOW + 1);
        BountyService.Plan plan = this.service.plan(alice, target, 10).orElseThrow();
        assertEquals(10_000, plan.split().total(), "Alice can't claim her own 40,000");
        assertTrue(this.service.claim(plan, NOW + 5).success());
        assertEquals(60_000 + 9_000, money(alice));
        assertEquals(40_000, this.service.book().total(target), "her part stays on the target for someone else");
        assertEquals(40_000, escrow());
        UUID loneSponsor = player(50_000);
        UUID other = player(0);
        place(loneSponsor, other, 5_000, NOW);
        assertTrue(this.service.plan(loneSponsor, other, 10).isEmpty(), "nothing to claim from your own bounty");
        assertTrue(this.service.plan(other, other, 10).isEmpty(), "nobody claims their own head");
    }

    @Test
    void expiredContributionsGoBackToTheirSponsors() throws Exception {
        UUID alice = player(100_000);
        UUID bob = player(100_000);
        UUID target = player(0);
        BountyService.Placed old = place(alice, target, 10_000, NOW);
        BountyService.Placed fresh = place(bob, target, 7_000, NOW + Duration.ofDays(5).toMillis());
        long later = NOW + FOURTEEN_DAYS.toMillis();
        List<BountyService.Refund> refunds = this.service.expire(later - 1, FOURTEEN_DAYS);
        assertTrue(refunds.isEmpty(), "one millisecond early");
        refunds = this.service.expire(later, FOURTEEN_DAYS);
        assertEquals(1, refunds.size());
        assertTrue(refunds.getFirst().result().success());
        refunds.getFirst().result().committed().get(10, TimeUnit.SECONDS);
        assertEquals(100_000, money(alice));
        assertEquals(93_000, money(bob));
        assertEquals(7_000, this.service.book().total(target));
        assertEquals(7_000, escrow());
        assertEquals(BountyService.EXPIRED, storedState(old.contribution().id()));
        assertEquals(BountyService.ACTIVE, storedState(fresh.contribution().id()));
        assertTrue(this.service.expire(later, FOURTEEN_DAYS).isEmpty(), "refunded once");
        assertEquals(10_000, ledgerSum(BountyService.KIND_REFUND));
    }

    @Test
    void staffRemovalRefundsEveryone() throws Exception {
        UUID alice = player(100_000);
        UUID bob = player(100_000);
        UUID target = player(0);
        place(alice, target, 10_000, NOW);
        place(bob, target, 20_000, NOW);
        place(alice, target, 5_000, NOW);
        List<BountyService.Refund> refunds = this.service.removeAll(target, "console", NOW + 1);
        assertEquals(3, refunds.size());
        for (BountyService.Refund refund : refunds) {
            assertTrue(refund.result().success());
            refund.result().committed().get(10, TimeUnit.SECONDS);
            assertEquals(BountyService.REMOVED, storedState(refund.contribution().id()));
        }
        assertEquals(100_000, money(alice));
        assertEquals(100_000, money(bob));
        assertEquals(0, escrow());
        assertTrue(this.service.removeAll(target, "console", NOW + 2).isEmpty());
    }

    @Test
    void aFailedWriteRevertsEverything() throws Exception {
        UUID alice = player(100_000);
        UUID target = player(0);
        UUID killer = player(0);
        BountyService.Placed placed = place(alice, target, 10_000, NOW);
        // Someone closed the row behind the service's back: the claim's write fails, so the claim must be undone.
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE bounties SET state = 'CLAIMED' WHERE id = ?")) {
                ps.setLong(1, placed.contribution().id());
                ps.executeUpdate();
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        BountyService.Plan plan = this.service.plan(killer, target, 10).orElseThrow();
        TransactionResult result = this.service.claim(plan, NOW + 1);
        assertTrue(result.success(), "applied in memory first");
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.committed().get(10, TimeUnit.SECONDS));
        assertTrue(String.valueOf(failure.getCause()).contains("not active"), String.valueOf(failure.getCause()));
        assertEquals(0, money(killer), "the killer is not paid for a claim that was not stored");
        assertEquals(10_000, escrow());
        assertEquals(10_000, this.service.book().total(target), "the bounty is back in the book");
        // Put storage back in line with memory so the invariants can be checked.
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE bounties SET state = 'ACTIVE' WHERE id = ?")) {
                ps.setLong(1, placed.contribution().id());
                ps.executeUpdate();
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
    }

    @Test
    void aRestartLoadsTheSameBounties() throws Exception {
        UUID alice = player(100_000);
        UUID bob = player(100_000);
        UUID target = player(0);
        UUID other = player(0);
        place(alice, target, 10_000, NOW);
        place(bob, other, 20_000, NOW);
        BountyService.Placed gone = place(bob, target, 3_000, NOW);
        this.service.removeAll(other, "console", NOW).forEach(refund -> refund.result().committed().join());
        this.service.claim(this.service.plan(UUID.randomUUID(), target, 10).orElseThrow(), NOW).committed().get(10, TimeUnit.SECONDS);
        place(alice, target, 4_000, NOW + 1);
        BountyService restarted = new BountyService(this.ledger, this.database, new BountyBook());
        restarted.load();
        assertEquals(4_000, restarted.book().total(target));
        assertNull(restarted.book().get(other));
        assertEquals(escrow(), restarted.book().totalActive());
        assertNull(restarted.checkEscrow());
        BountyService.Placed next = restarted.place(alice, other, 1_000, NOW + 2);
        assertTrue(next.contribution().id() > gone.contribution().id(), "ids continue after a restart");
        next.result().committed().get(10, TimeUnit.SECONDS);
        this.service = restarted;
    }

    @Test
    void concurrentPlacementsClaimsAndRefundsKeepTheEscrowExact() throws Exception {
        int sponsorsCount = 8;
        List<UUID> sponsors = new ArrayList<>();
        for (int i = 0; i < sponsorsCount; i++) {
            sponsors.add(player(10_000_000));
        }
        List<UUID> targets = List.of(player(0), player(0), player(0));
        List<UUID> killers = List.of(player(0), player(0));
        AtomicLong claimedPayouts = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            int n = i;
            work.add(CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ThreadLocalRandom random = ThreadLocalRandom.current();
                UUID target = targets.get(random.nextInt(targets.size()));
                switch (n % 6) {
                    case 0, 1, 2, 3 -> this.service.place(sponsors.get(random.nextInt(sponsorsCount)), target,
                        random.nextLong(1_000, 50_000), NOW + n);
                    case 4 -> {
                        UUID killer = killers.get(random.nextInt(killers.size()));
                        this.service.plan(killer, target, 10).ifPresent(plan -> {
                            if (this.service.claim(plan, NOW + n).success()) {
                                claimedPayouts.addAndGet(plan.split().payout());
                            }
                        });
                    }
                    default -> this.service.expire(NOW + n + FOURTEEN_DAYS.toMillis() / 2, Duration.ofMillis(FOURTEEN_DAYS.toMillis() / 2 + 300));
                }
            }, pool));
        }
        start.countDown();
        CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(claimedPayouts.get(), money(killers.get(0)) + money(killers.get(1)), "killers got exactly the claimed payouts");
        assertEquals(this.service.book().totalActive(), escrow());
        // assertInvariants() in tearDown checks memory, storage and the ledger after every write is committed.
    }
}
