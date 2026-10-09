package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.event.TeamJoinEvent;
import net.siftvanilla.siftcore.api.event.TeamLeaveEvent;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The team service against a real ledger and a migrated SQLite database: creation as one transaction, every role
 * rule enforced at execution time, invite expiry, member limits, persistence of every change (reloaded from storage
 * and compared with memory), and concurrent attempts that must not double-apply.
 */
class TeamServiceTest {

    private static final long COST = 50_000;
    private static final long TTL = Duration.ofMinutes(2).toMillis();

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Ledger ledger;
    private TeamRegistry registry;
    private TeamStore store;
    private Invites invites;
    private TeamService service;
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final AtomicLong ids = new AtomicLong(1);
    private volatile TeamsSettings settings = settings(COST, 5);
    private volatile TeamEventGate gate = TeamEventGate.ALLOW;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carl = UUID.randomUUID();
    private final UUID dana = UUID.randomUUID();

    private static TeamsSettings settings(long cost, int limit) {
        return new TeamsSettings(cost, 3, 16, List.of("bad"), limit, Duration.ofMinutes(2), 10, Duration.ofSeconds(3),
            Duration.ofSeconds(5), false, true, true, Duration.ofSeconds(60), 10, 10, java.util.Set.of());
    }

    @BeforeEach
    void setUp() throws Exception {
        this.database = TeamsTestDatabase.open(this.dir);
        this.ledger = new Ledger(this.database, Logger.getLogger("teams-ledger-test"), 1_000_000_000_000L);
        this.ledger.load();
        this.registry = new TeamRegistry();
        this.store = new TeamStore(this.database);
        this.invites = new Invites();
        TeamEventGate delegating = new TeamEventGate() {
            @Override
            public boolean create(UUID owner, String name, long cost) {
                return TeamServiceTest.this.gate.create(owner, name, cost);
            }

            @Override
            public boolean join(Team team, UUID player, TeamJoinEvent.Cause cause) {
                return TeamServiceTest.this.gate.join(team, player, cause);
            }

            @Override
            public boolean leave(Team team, UUID player, TeamLeaveEvent.Reason reason, UUID actor) {
                return TeamServiceTest.this.gate.leave(team, player, reason, actor);
            }

            @Override
            public boolean disband(Team team, UUID actor) {
                return TeamServiceTest.this.gate.disband(team, actor);
            }
        };
        this.service = new TeamService(this.ledger, this.registry, this.store, this.invites, delegating, () -> this.settings,
            this.ids::getAndIncrement, this.clock::get, Logger.getLogger("teams-service-test"));
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    /** Creates a team for the configured cost, like a player who just confirmed that cost. */
    private TeamService.Outcome create(UUID owner, String name) {
        return this.service.create(owner, name, this.settings.createCost());
    }

    private void fund(UUID player, long amount) {
        assertTrue(this.ledger.execute(LedgerTx.builder().source(player, Currency.MONEY, amount, "test_mint", null).build()).success());
    }

    private Team createTeam(UUID owner, String name) throws Exception {
        fund(owner, COST);
        TeamService.Outcome outcome = create(owner, name);
        assertTrue(outcome.ok(), () -> "create failed: " + outcome.problem());
        outcome.stored().get(10, TimeUnit.SECONDS);
        return outcome.team();
    }

    private void addMember(Team team, UUID inviter, UUID player) {
        assertTrue(this.service.invite(inviter, player).ok());
        TeamService.Outcome joined = this.service.join(player, team.id());
        assertTrue(joined.ok(), () -> "join failed: " + joined.problem());
    }

    /** Reloads storage the way enable() does and checks it matches memory exactly. */
    private void assertStorageMatchesMemory() throws Exception {
        this.database.flush();
        TeamStore.Rows rows = this.store.load().get(10, TimeUnit.SECONDS);
        assertTrue(rows.unreadable().isEmpty());
        TeamLoader.Result result = TeamLoader.assemble(rows.teams(), rows.members());
        assertTrue(result.repairs().isEmpty(), () -> "storage needed repairs: " + result.notes());
        List<Team> stored = new ArrayList<>(result.teams());
        List<Team> memory = this.registry.all();
        stored.sort(Comparator.comparingLong(Team::id));
        memory.sort(Comparator.comparingLong(Team::id));
        assertEquals(memory, stored);
        assertNull(this.registry.verify());
    }

    private long balance(UUID player) {
        return this.ledger.balance(player, Currency.MONEY);
    }

    // ------------------------------------------------------------------ create

    @Test
    void creatingChargesOnceAndStoresTheTeamWithTheLedgerRow() throws Exception {
        fund(this.alice, 80_000);
        TeamService.Outcome outcome = create(this.alice, "Testers");
        assertTrue(outcome.ok());
        outcome.stored().get(10, TimeUnit.SECONDS);
        assertEquals(30_000, balance(this.alice));
        Team team = this.registry.of(this.alice).orElseThrow();
        assertEquals("Testers", team.name());
        assertEquals(TeamRole.OWNER, team.role(this.alice));
        assertFalse(team.friendlyFire());
        long rows = this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ledger WHERE kind = ? AND ref = ? AND delta = ?")) {
                ps.setString(1, TeamService.CREATE_KIND);
                ps.setString(2, Long.toString(team.id()));
                ps.setLong(3, -COST);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).get();
        assertEquals(1, rows, "one sink row of the creation cost, referencing the team id");
        assertTrue(this.ledger.audit().get().healthy());
        assertStorageMatchesMemory();
    }

    @Test
    void creatingWithoutEnoughMoneyChangesNothing() throws Exception {
        fund(this.alice, COST - 1);
        assertEquals(TeamProblem.NOT_ENOUGH_MONEY, create(this.alice, "Testers").problem());
        assertEquals(COST - 1, balance(this.alice));
        assertTrue(this.registry.of(this.alice).isEmpty());
        assertStorageMatchesMemory();
    }

    @Test
    void namesAreValidatedAndUniqueIgnoringCase() throws Exception {
        createTeam(this.alice, "Testers");
        fund(this.bob, COST);
        assertEquals(TeamProblem.NAME_TAKEN, create(this.bob, "tESTERS").problem());
        assertEquals(TeamProblem.NAME_TOO_SHORT, create(this.bob, "ab").problem());
        assertEquals(TeamProblem.NAME_CHARACTERS, create(this.bob, "a team").problem());
        assertEquals(TeamProblem.NAME_BLOCKED, create(this.bob, "B_A_D_guys").problem());
        assertEquals(COST, balance(this.bob), "refused creations charge nothing");
    }

    @Test
    void aPlayerOwnsOrJoinsOnlyOneTeam() throws Exception {
        createTeam(this.alice, "Testers");
        fund(this.alice, COST);
        assertEquals(TeamProblem.ALREADY_IN_TEAM, create(this.alice, "Second").problem());
        Team other = createTeam(this.bob, "Others");
        assertEquals(TeamProblem.TARGET_IN_TEAM, this.service.invite(this.bob, this.alice).problem());
        assertEquals(TeamProblem.NO_INVITE, this.service.join(this.alice, other.id()).problem());
    }

    @Test
    void cancelledCreationChargesNothing() throws Exception {
        fund(this.alice, COST);
        this.gate = new TeamEventGate() {
            @Override
            public boolean create(UUID owner, String name, long cost) {
                return false;
            }

            @Override
            public boolean join(Team team, UUID player, TeamJoinEvent.Cause cause) {
                return true;
            }

            @Override
            public boolean leave(Team team, UUID player, TeamLeaveEvent.Reason reason, UUID actor) {
                return true;
            }

            @Override
            public boolean disband(Team team, UUID actor) {
                return true;
            }
        };
        assertEquals(TeamProblem.CANCELLED, create(this.alice, "Testers").problem());
        assertEquals(COST, balance(this.alice));
        assertTrue(this.registry.of(this.alice).isEmpty());
    }

    @Test
    void aCostChangedAfterConfirmingChargesNothing() throws Exception {
        fund(this.alice, 200_000);
        this.settings = settings(COST * 2, 5);
        assertEquals(TeamProblem.COST_CHANGED, this.service.create(this.alice, "Testers", COST).problem(),
            "the player agreed to the old cost");
        assertEquals(200_000, balance(this.alice));
        assertTrue(this.registry.of(this.alice).isEmpty());
        TeamService.Outcome outcome = this.service.create(this.alice, "Testers", COST * 2);
        assertTrue(outcome.ok());
        outcome.stored().get(10, TimeUnit.SECONDS);
        assertEquals(200_000 - COST * 2, balance(this.alice));
        assertStorageMatchesMemory();
    }

    @Test
    void freeCreationNeedsNoMoney() throws Exception {
        this.settings = settings(0, 5);
        TeamService.Outcome outcome = create(this.alice, "Freebies");
        assertTrue(outcome.ok());
        outcome.stored().get(10, TimeUnit.SECONDS);
        assertEquals(0, balance(this.alice));
        assertStorageMatchesMemory();
    }

    @Test
    void concurrentCreationsOfOneNameSucceedOnceAndChargeOnce() throws Exception {
        int players = 16;
        List<UUID> founders = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            UUID player = UUID.randomUUID();
            fund(player, COST);
            founders.add(player);
        }
        ExecutorService pool = Executors.newFixedThreadPool(players);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        List<TeamService.Outcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        for (UUID founder : founders) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                TeamService.Outcome outcome = create(founder, "Highlander");
                outcomes.add(outcome);
                if (outcome.ok()) {
                    created.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, created.get());
        long charged = founders.stream().filter(p -> balance(p) == 0).count();
        assertEquals(1, charged, "exactly one founder paid");
        for (TeamService.Outcome outcome : outcomes) {
            assertTrue(outcome.ok() || outcome.problem() == TeamProblem.NAME_TAKEN, () -> "unexpected " + outcome.problem());
        }
        assertEquals(1, this.registry.count());
        assertTrue(this.ledger.audit().get().healthy());
        assertStorageMatchesMemory();
    }

    // ------------------------------------------------------------------ invites and joining

    @Test
    void invitesExpireAfterTwoMinutes() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        assertTrue(this.service.invite(this.alice, this.bob).ok());
        assertEquals(TeamProblem.ALREADY_INVITED, this.service.invite(this.alice, this.bob).problem());
        this.clock.addAndGet(TTL);
        assertEquals(TeamProblem.INVITE_EXPIRED, this.service.join(this.bob, team.id()).problem());
        assertEquals(TeamProblem.NO_INVITE, this.service.join(this.bob, team.id()).problem(), "an expired invite is gone");
        assertTrue(this.service.invite(this.alice, this.bob).ok(), "a fresh invite works again");
        this.clock.addAndGet(TTL - 1);
        assertTrue(this.service.join(this.bob, team.id()).ok(), "still valid one millisecond before expiry");
        assertStorageMatchesMemory();
    }

    @Test
    void onlyOwnersAndAdminsInvite() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        assertEquals(TeamProblem.ADMINS_ONLY, this.service.invite(this.bob, this.carl).problem());
        assertTrue(this.service.promote(this.alice, this.bob).ok());
        assertTrue(this.service.invite(this.bob, this.carl).ok());
        assertEquals(TeamProblem.NOT_YOURSELF, this.service.invite(this.alice, this.alice).problem());
        assertEquals(TeamProblem.TARGET_ALREADY_MEMBER, this.service.invite(this.alice, this.bob).problem());
        assertEquals(TeamProblem.NOT_IN_TEAM, this.service.invite(this.dana, this.carl).problem());
    }

    @Test
    void declineUsesTheInviteAndNamesTheInviter() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        this.service.invite(this.alice, this.bob);
        TeamService.Outcome declined = this.service.decline(this.bob, team.id());
        assertTrue(declined.ok());
        assertEquals(this.alice, declined.subject());
        assertEquals(TeamProblem.NO_INVITE, this.service.join(this.bob, team.id()).problem());
        assertEquals(TeamProblem.NO_INVITE, this.service.decline(this.bob, team.id()).problem());
    }

    @Test
    void theMemberLimitIsEnforcedOnInviteAndOnJoin() throws Exception {
        this.settings = settings(COST, 3);
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        assertTrue(this.service.invite(this.alice, this.carl).ok());
        assertTrue(this.service.invite(this.alice, this.dana).ok());
        assertTrue(this.service.join(this.carl, team.id()).ok());
        assertEquals(TeamProblem.JOIN_TEAM_FULL, this.service.join(this.dana, team.id()).problem());
        assertEquals(Invites.State.VALID, this.invites.state(this.dana, team.id(), this.clock.get()),
            "a refused join keeps the invite for when there is room");
        assertEquals(TeamProblem.TEAM_FULL, this.service.invite(this.alice, UUID.randomUUID()).problem());
        assertEquals(3, this.registry.of(this.alice).orElseThrow().size());
    }

    @Test
    void theOwnersRankRaisesTheLimitAndIsStored() throws Exception {
        this.settings = settings(COST, 2);
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        assertEquals(TeamProblem.TEAM_FULL, this.service.invite(this.alice, this.carl).problem());
        this.service.updateRankLimit(this.alice, 4);
        assertEquals(4, this.service.memberLimit(this.registry.of(this.alice).orElseThrow()));
        addMember(team, this.alice, this.carl);
        this.service.updateRankLimit(this.bob, 50);
        assertEquals(4, this.registry.of(this.alice).orElseThrow().ownerRankLimit(), "only the owner's rank counts");
        this.service.updateRankLimit(this.alice, TeamRules.UNLIMITED);
        assertEquals(TeamRules.UNLIMITED, this.service.memberLimit(this.registry.of(this.alice).orElseThrow()));
        assertStorageMatchesMemory();
    }

    @Test
    void concurrentJoinsNeverExceedTheLimit() throws Exception {
        this.settings = settings(COST, 4);
        Team team = createTeam(this.alice, "Testers");
        List<UUID> joiners = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID player = UUID.randomUUID();
            this.invites.add(team.id(), player, this.alice, this.clock.get(), TTL);
            joiners.add(player);
        }
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger joined = new AtomicInteger();
        for (UUID joiner : joiners) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                TeamService.Outcome outcome = this.service.join(joiner, team.id());
                if (outcome.ok()) {
                    joined.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(3, joined.get());
        assertEquals(4, this.registry.get(team.id()).orElseThrow().size());
        assertStorageMatchesMemory();
    }

    // ------------------------------------------------------------------ leaving, kicking, roles

    @Test
    void theOwnerCannotLeaveButMembersCan() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        assertEquals(TeamProblem.OWNER_CANT_LEAVE, this.service.leave(this.alice).problem());
        TeamService.Outcome left = this.service.leave(this.bob);
        assertTrue(left.ok());
        assertFalse(left.team().isMember(this.bob));
        assertTrue(this.registry.of(this.bob).isEmpty());
        assertEquals(TeamProblem.NOT_IN_TEAM, this.service.leave(this.bob).problem());
        assertStorageMatchesMemory();
    }

    @Test
    void kickingFollowsRanks() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        addMember(team, this.alice, this.carl);
        addMember(team, this.alice, this.dana);
        assertTrue(this.service.promote(this.alice, this.bob).ok());
        assertTrue(this.service.promote(this.alice, this.carl).ok());
        assertEquals(TeamProblem.RANK_TOO_HIGH, this.service.kick(this.bob, this.carl).problem(), "admins can't kick admins");
        assertEquals(TeamProblem.RANK_TOO_HIGH, this.service.kick(this.bob, this.alice).problem());
        assertEquals(TeamProblem.ADMINS_ONLY, this.service.kick(this.dana, this.bob).problem());
        assertEquals(TeamProblem.NOT_YOURSELF, this.service.kick(this.bob, this.bob).problem());
        assertTrue(this.service.kick(this.bob, this.dana).ok(), "admins kick members");
        assertTrue(this.service.kick(this.alice, this.carl).ok(), "the owner kicks admins");
        assertEquals(TeamProblem.TARGET_NOT_MEMBER, this.service.kick(this.alice, this.dana).problem());
        assertEquals(2, this.registry.get(team.id()).orElseThrow().size());
        assertStorageMatchesMemory();
    }

    @Test
    void onlyTheOwnerPromotesAndDemotes() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        addMember(team, this.alice, this.carl);
        assertTrue(this.service.promote(this.alice, this.bob).ok());
        assertEquals(TeamProblem.OWNER_ONLY, this.service.promote(this.bob, this.carl).problem());
        assertEquals(TeamProblem.ALREADY_ADMIN, this.service.promote(this.alice, this.bob).problem());
        assertEquals(TeamProblem.NOT_ADMIN, this.service.demote(this.alice, this.carl).problem());
        assertTrue(this.service.demote(this.alice, this.bob).ok());
        assertEquals(TeamRole.MEMBER, this.registry.get(team.id()).orElseThrow().role(this.bob));
        assertStorageMatchesMemory();
    }

    @Test
    void transferHandsOverAndKeepsTheOldOwnerAsAdmin() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        addMember(team, this.alice, this.carl);
        assertEquals(TeamProblem.OWNER_ONLY, this.service.transfer(this.bob, this.carl).problem());
        assertEquals(TeamProblem.NOT_YOURSELF, this.service.transfer(this.alice, this.alice).problem());
        assertEquals(TeamProblem.TARGET_NOT_MEMBER, this.service.transfer(this.alice, this.dana).problem());
        TeamService.Outcome outcome = this.service.transfer(this.alice, this.bob);
        assertTrue(outcome.ok());
        Team after = this.registry.get(team.id()).orElseThrow();
        assertEquals(this.bob, after.owner());
        assertEquals(TeamRole.ADMIN, after.role(this.alice));
        assertEquals(TeamProblem.OWNER_ONLY, this.service.disband(this.alice).problem(), "the old owner lost owner powers");
        assertTrue(this.service.leave(this.alice).ok(), "and can now leave");
        assertEquals(TeamProblem.OWNER_CANT_LEAVE, this.service.leave(this.bob).problem());
        assertStorageMatchesMemory();
    }

    @Test
    void disbandingRemovesEverythingAndRefundsNothing() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        this.service.invite(this.alice, this.carl);
        assertEquals(TeamProblem.OWNER_ONLY, this.service.disband(this.bob).problem());
        TeamService.Outcome outcome = this.service.disband(this.alice);
        assertTrue(outcome.ok());
        assertEquals(2, outcome.team().size(), "the outcome carries the team as it was");
        assertTrue(this.registry.of(this.alice).isEmpty());
        assertTrue(this.registry.of(this.bob).isEmpty());
        assertTrue(this.registry.byName("testers").isEmpty());
        assertEquals(Invites.State.NONE, this.invites.state(this.carl, team.id(), this.clock.get()));
        assertEquals(0, balance(this.alice), "nothing is refunded");
        assertStorageMatchesMemory();
        assertTrue(createTeam(this.bob, "Testers").id() > team.id(), "the name is free again and ids are not reused");
    }

    // ------------------------------------------------------------------ home and friendly fire

    @Test
    void homeAndFriendlyFireAreForAdmins() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        TeamHome home = new TeamHome("world", 1.5, 64, -2.5, 90f, 10f);
        assertEquals(TeamProblem.ADMINS_ONLY, this.service.setHome(this.bob, home).problem());
        assertEquals(TeamProblem.ADMINS_ONLY, this.service.friendlyFire(this.bob, null).problem());
        assertTrue(this.service.setHome(this.alice, home).ok());
        assertTrue(this.service.friendlyFire(this.alice, null).ok());
        assertTrue(this.registry.friendlyFire(team.id()));
        assertEquals(TeamProblem.UNCHANGED, this.service.friendlyFire(this.alice, true).problem());
        assertTrue(this.service.friendlyFire(this.alice, false).ok());
        assertFalse(this.registry.friendlyFire(team.id()));
        assertEquals(home, this.registry.get(team.id()).orElseThrow().home());
        assertStorageMatchesMemory();
    }

    @Test
    void theTeamHomeFollowsTheHomeRules() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        TeamHome atSpawn = new TeamHome("world", 0.5, 70, 0.5, 0f, 0f);
        assertEquals(TeamProblem.HOME_IN_SPAWN, this.service.setHome(this.alice, atSpawn, false, true).problem());
        assertEquals(TeamProblem.HOME_WORLD_DISABLED, this.service.setHome(this.alice, atSpawn, true, false).problem());
        assertEquals(TeamProblem.ADMINS_ONLY, this.service.setHome(this.bob, atSpawn, false, true).problem());
        assertNull(this.registry.get(team.id()).orElseThrow().home(), "nothing was set");
        assertTrue(this.service.setHome(this.alice, atSpawn, false, false).ok());
        assertEquals(atSpawn, this.registry.get(team.id()).orElseThrow().home());
    }

    @Test
    void lookupAnswersForOtherFeatures() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        assertEquals(team.id(), this.registry.team(this.bob).orElseThrow());
        assertEquals("Testers", this.registry.teamName(this.alice).orElseThrow());
        assertTrue(this.registry.sameTeam(this.alice, this.bob));
        assertFalse(this.registry.sameTeam(this.alice, this.carl));
        assertEquals(java.util.Set.of(this.alice, this.bob), this.registry.members(team.id()));
        assertTrue(this.registry.members(999).isEmpty());
        assertTrue(this.registry.friendlyFire(999), "unknown teams have no protection");
    }

    // ------------------------------------------------------------------ staff

    @Test
    void staffToolsWorkOnAnyTeam() throws Exception {
        this.settings = settings(COST, 2);
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        assertTrue(this.service.adminAdd(team.id(), this.carl).ok(), "staff can add beyond the member limit");
        assertEquals(TeamProblem.TARGET_ALREADY_MEMBER, this.service.adminAdd(team.id(), this.carl).problem());
        assertEquals(TeamProblem.OWNER_CANT_LEAVE, this.service.adminKick(this.alice).problem());
        assertTrue(this.service.adminKick(this.carl).ok());
        assertEquals(TeamProblem.TARGET_NOT_MEMBER, this.service.adminKick(this.carl).problem());
        assertEquals(TeamProblem.UNCHANGED, this.service.adminTransfer(team.id(), this.alice).problem());
        assertEquals(TeamProblem.TARGET_NOT_MEMBER, this.service.adminTransfer(team.id(), this.dana).problem());
        assertTrue(this.service.adminTransfer(team.id(), this.bob).ok());
        assertEquals(TeamProblem.NAME_TAKEN, this.service.adminRename(team.id(), createTeam(this.dana, "Other").name()).problem());
        assertTrue(this.service.adminRename(team.id(), "testers").ok(), "changing only the case of its own name is fine");
        assertEquals(TeamProblem.NAME_UNCHANGED, this.service.adminRename(team.id(), "testers").problem());
        assertTrue(this.registry.byName("TESTERS").isPresent());
        assertEquals(TeamProblem.NO_HOME, this.service.adminDeleteHome(team.id()).problem());
        this.service.setHome(this.bob, new TeamHome("world", 0, 70, 0, 0, 0));
        assertTrue(this.service.adminDeleteHome(team.id()).ok());
        assertNull(this.registry.get(team.id()).orElseThrow().home());
        assertStorageMatchesMemory();
        assertTrue(this.service.adminDisband(team.id()).ok());
        assertEquals(TeamProblem.TEAM_GONE, this.service.adminDisband(team.id()).problem());
        assertStorageMatchesMemory();
    }

    @Test
    void cancelledLeaveKeepsThePlayer() throws Exception {
        Team team = createTeam(this.alice, "Testers");
        addMember(team, this.alice, this.bob);
        this.gate = new TeamEventGate() {
            @Override
            public boolean create(UUID owner, String name, long cost) {
                return true;
            }

            @Override
            public boolean join(Team t, UUID player, TeamJoinEvent.Cause cause) {
                return true;
            }

            @Override
            public boolean leave(Team t, UUID player, TeamLeaveEvent.Reason reason, UUID actor) {
                return false;
            }

            @Override
            public boolean disband(Team t, UUID actor) {
                return false;
            }
        };
        assertEquals(TeamProblem.CANCELLED, this.service.leave(this.bob).problem());
        assertEquals(TeamProblem.CANCELLED, this.service.kick(this.alice, this.bob).problem());
        assertEquals(TeamProblem.CANCELLED, this.service.disband(this.alice).problem());
        assertTrue(this.registry.of(this.bob).isPresent());
        assertStorageMatchesMemory();
    }

    @Test
    void theIdSequenceSkipsIdsOfDisbandedPaidTeams() throws Exception {
        Team first = createTeam(this.alice, "First");
        Team second = createTeam(this.bob, "Second");
        assertTrue(this.service.disband(this.bob).ok());
        this.database.flush();
        assertEquals(second.id(), this.store.highestId(TeamService.CREATE_KIND).get(10, TimeUnit.SECONDS),
            "the ledger remembers the id of the disbanded team");
        assertNotNull(first);
    }
}
