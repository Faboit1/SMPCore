package net.siftvanilla.siftcore.feature.friends;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqlWork;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every write unit against a real, migrated SQLite database, with memory ({@link FriendGraph}) fed the committed
 * results the way the feature does, and compared with storage after each test.
 */
class FriendStoreTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private final AtomicLong clock = new AtomicLong(1_000 * DAY);
    private FriendStore store;
    private FriendGraph graph;
    private FriendRules rules;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final List<UUID> loaded = new ArrayList<>();
    /** How the request unit reads privacy: the code default unless a test sets the server's default or lock. */
    private final AtomicReference<StoredSetting<Privacy>> privacyRule =
        new AtomicReference<>(StoredSetting.codeDefault(FriendPrefs.REQUESTS));

    @BeforeEach
    void open() throws Exception {
        Logger logger = Logger.getLogger("friends-test");
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("friends-test.db"), 2), logger);
        ClassLoader loader = FriendStoreTest.class.getClassLoader();
        new Migrations(this.database, logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.store = new FriendStore(this.database, this.clock::get, this.privacyRule::get);
        this.graph = new FriendGraph(this.clock::get, () -> Duration.ofDays(7), FriendGraph.TeleportPolicy.NEVER);
        this.rules = new FriendRules(7 * DAY, 7 * DAY, 20, 50, 30, 50, 500, 10);
        load(this.a, this.b, this.c);
    }

    @AfterEach
    void close() throws Exception {
        assertMemoryMatchesStorage();
        int[] invariants = this.store.invariants().get();
        assertArrayEquals(new int[] {0, 0, 0}, invariants, "symmetric, nobody their own friend, no requests between friends");
        this.database.close();
    }

    // ------------------------------------------------------------------ helpers

    private void load(UUID... players) throws Exception {
        for (UUID player : players) {
            this.graph.prepare(player);
            FriendGraph.Snapshot snapshot = this.store.write(this.store.load(player, this.rules, Duration.ofDays(7).toMillis())).get();
            assertTrue(this.graph.install(player, snapshot));
            this.loaded.add(player);
        }
    }

    private FriendStore.Result run(SqlWork<FriendStore.Result> work) throws Exception {
        FriendStore.Result result = this.store.write(work).get();
        this.graph.apply(result.changes());
        return result;
    }

    private static FriendStore.RequestFlags flags() {
        return new FriendStore.RequestFlags(false, false, 50, false, true);
    }

    private static FriendStore.RequestFlags mutual() {
        return new FriendStore.RequestFlags(false, false, 50, true, true);
    }

    private Outcome request(UUID from, UUID to) throws Exception {
        return run(this.store.request(from, to, this.rules, flags())).outcome();
    }

    private Outcome accept(UUID accepter, UUID requester) throws Exception {
        return run(this.store.accept(accepter, requester, this.rules, 50, true, false)).outcome();
    }

    private void befriend(UUID x, UUID y) throws Exception {
        assertEquals(Outcome.BECAME_FRIENDS, run(this.store.staffAdd(x, y, "test", 500, true, true)).outcome());
    }

    private <T> T query(SqlWork<T> work) throws Exception {
        return this.database.read(work).get();
    }

    private int count(String sql, Object... params) throws Exception {
        return query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i] instanceof UUID id ? id.toString() : params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private String state(UUID sender, UUID target) throws Exception {
        return query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT state FROM friend_requests WHERE sender = ? AND target = ?")) {
                ps.setString(1, sender.toString());
                ps.setString(2, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private void setPrivacy(UUID player, Privacy privacy) throws Exception {
        setPrivacy(player, privacy.id());
    }

    private void setPrivacy(UUID player, String stored) throws Exception {
        this.store.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO settings (uuid, setting, value) VALUES (?, ?, ?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, FriendPrefs.REQUESTS.id());
                ps.setString(3, stored);
                ps.executeUpdate();
            }
            return null;
        }).get();
    }

    /** Every loaded node equals what storage says (friends with favourites, visible requests both ways). */
    private void assertMemoryMatchesStorage() throws Exception {
        long expired = this.rules.expiredBefore(this.clock.get());
        for (UUID player : this.loaded) {
            FriendGraph.Node node = this.graph.loaded(player);
            assertNotNull(node, "loaded node");
            FriendGraph.Snapshot stored = this.store.read(player, this.rules).get();
            assertEquals(stored.friends(), node.friends(), "friends of " + player);
            assertEquals(stored.incoming().keySet(), this.graph.incoming(player, expired).keySet(), "incoming of " + player);
            assertEquals(stored.outgoing().keySet(), this.graph.outgoing(player, expired).keySet(), "outgoing of " + player);
        }
    }

    // ------------------------------------------------------------------ requests and accepts

    @Test
    void requestThenAccept() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertTrue(this.graph.incoming(this.b, 0).containsKey(this.a), "b sees the request");
        assertTrue(this.graph.outgoing(this.a, 0).containsKey(this.b), "a sees it waiting");
        assertEquals(Outcome.ALREADY_SENT, request(this.a, this.b));
        assertEquals(Outcome.BECAME_FRIENDS, accept(this.b, this.a));
        assertTrue(this.graph.friends(this.a, this.b) && this.graph.friends(this.b, this.a));
        assertEquals(0, count("SELECT COUNT(*) FROM friend_requests"));
        assertEquals(1, count("SELECT COUNT(*) FROM friend_log WHERE action = 'request'"));
        assertEquals(1, count("SELECT COUNT(*) FROM friend_log WHERE action = 'accept' AND player = ?", this.b));
        assertEquals(Outcome.ALREADY_FRIENDS, request(this.a, this.b));
        assertEquals(Outcome.ALREADY_FRIENDS, accept(this.b, this.a));
    }

    @Test
    void requestBackIsMutual() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.MUTUAL_NEEDED, request(this.b, this.a), "the caller must run the add event first");
        assertEquals(Outcome.BECAME_FRIENDS, run(this.store.request(this.b, this.a, this.rules, mutual())).outcome());
        assertTrue(this.graph.friends(this.a, this.b));
        assertEquals(1, count("SELECT COUNT(*) FROM friend_log WHERE action = 'mutual'"));
        assertEquals(0, count("SELECT COUNT(*) FROM friend_requests"));
    }

    @Test
    void offlineRequesterGetsANotice() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.BECAME_FRIENDS, run(this.store.accept(this.b, this.a, this.rules, 50, false, false)).outcome());
        assertEquals(List.of(this.b), this.store.write(this.store.takeNotices(this.a)).get(), "a is told once");
        assertEquals(List.of(), this.store.write(this.store.takeNotices(this.a)).get(), "and only once");
        assertEquals(List.of(), this.store.write(this.store.takeNotices(this.b)).get(), "b accepted, nothing to tell");
    }

    @Test
    void denyIsSilentAndRemembered() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.DONE, run(this.store.deny(this.b, this.a, this.rules)).outcome());
        assertFalse(this.graph.incoming(this.b, 0).containsKey(this.a), "b no longer sees it");
        assertTrue(this.graph.outgoing(this.a, 0).containsKey(this.b), "a still sees it waiting");
        assertEquals("shadow", state(this.a, this.b));
        assertEquals(Outcome.ALREADY_SENT, request(this.a, this.b), "a sees no difference");
        assertEquals(Outcome.GONE, run(this.store.deny(this.b, this.a, this.rules)).outcome(), "denying twice changes nothing");
        assertEquals(Outcome.GONE, accept(this.b, this.a), "a denied request can't be accepted");

        assertEquals(Outcome.DONE, run(this.store.cancel(this.a, this.b, this.rules)).outcome());
        assertEquals("closed", state(this.a, this.b), "cancelling a denied request keeps the deny memory");
        assertFalse(this.graph.outgoing(this.a, 0).containsKey(this.b));

        this.clock.addAndGet(DAY);
        assertEquals(Outcome.SHADOWED, request(this.a, this.b), "within the deny memory a new request stays hidden");
        assertEquals("shadow", state(this.a, this.b));
        assertFalse(this.graph.incoming(this.b, 0).containsKey(this.a));

        this.clock.addAndGet(8 * DAY);
        assertEquals(Outcome.SENT, request(this.a, this.b), "after the deny memory a request is seen again");
        assertTrue(this.graph.incoming(this.b, 0).containsKey(this.a));
    }

    @Test
    void cancellingAPendingRequestDeletesIt() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.DONE, run(this.store.cancel(this.a, this.b, this.rules)).outcome());
        assertNull(state(this.a, this.b));
        assertEquals(Outcome.GONE, run(this.store.cancel(this.a, this.b, this.rules)).outcome());
        assertEquals(Outcome.SENT, request(this.a, this.b), "no deny memory without a deny");
    }

    @Test
    void ignoredSendersAreHidden() throws Exception {
        FriendStore.RequestFlags ignored = new FriendStore.RequestFlags(true, false, 50, false, true);
        assertEquals(Outcome.SHADOWED, run(this.store.request(this.a, this.b, this.rules, ignored)).outcome());
        assertFalse(this.graph.incoming(this.b, 0).containsKey(this.a));
        assertTrue(this.graph.outgoing(this.a, 0).containsKey(this.b), "the sender sees it as waiting");
        assertEquals(1, count("SELECT COUNT(*) FROM friend_log WHERE action = 'request'"), "it counts against the sender");
        assertEquals(Outcome.GONE, run(this.store.accept(this.b, this.a, this.rules, 50, true, true)).outcome());
    }

    @Test
    void tooManyIncomingAreHidden() throws Exception {
        this.rules = new FriendRules(7 * DAY, 7 * DAY, 20, 1, 30, 50, 500, 10);
        assertEquals(Outcome.SENT, request(this.a, this.c));
        assertEquals(Outcome.SHADOWED, request(this.b, this.c), "c already has the most visible requests");
        assertEquals(Set.of(this.a), this.graph.incoming(this.c, 0).keySet());
    }

    @Test
    void requestExpiresSilently() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        this.clock.addAndGet(8 * DAY);
        long expired = this.rules.expiredBefore(this.clock.get());
        assertTrue(this.graph.incoming(this.b, expired).isEmpty(), "run out for b");
        assertTrue(this.graph.outgoing(this.a, expired).isEmpty(), "run out for a");
        assertEquals(Outcome.GONE, accept(this.b, this.a));
        assertEquals(Outcome.SENT, request(this.a, this.b), "a fresh request replaces the old row");
        assertEquals(1, count("SELECT COUNT(*) FROM friend_requests"));
    }

    @Test
    void outgoingAndDailyCaps() throws Exception {
        this.rules = new FriendRules(7 * DAY, 7 * DAY, 2, 50, 3, 50, 500, 10);
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.SENT, request(this.a, this.c));
        assertEquals(Outcome.OUTGOING_FULL, request(this.a, d));
        assertEquals(Outcome.DONE, run(this.store.cancel(this.a, this.b, this.rules)).outcome());
        assertEquals(Outcome.SENT, request(this.a, d), "the third request of the day");
        assertEquals(Outcome.DONE, run(this.store.cancel(this.a, this.c, this.rules)).outcome());
        assertEquals(Outcome.DAILY_CAP, request(this.a, e), "three requests today already");
        this.clock.addAndGet(DAY + 1);
        assertEquals(Outcome.SENT, request(this.a, e), "the cap counts the last 24 hours");
    }

    @Test
    void privacyIsCheckedInTheUnit() throws Exception {
        setPrivacy(this.b, Privacy.NOBODY);
        assertEquals(Outcome.PRIVATE, request(this.a, this.b));
        assertEquals(0, count("SELECT COUNT(*) FROM friend_requests"), "nothing stored");
        UUID d = UUID.randomUUID();
        setPrivacy(d, Privacy.KNOWN);
        assertEquals(Outcome.PRIVATE, request(this.a, d), "a stranger");
        assertEquals(Outcome.SENT, run(this.store.request(this.a, d, this.rules,
            new FriendStore.RequestFlags(false, true, 50, false, true))).outcome(), "a teammate");
        UUID e = UUID.randomUUID();
        setPrivacy(e, Privacy.KNOWN);
        befriend(this.c, e);
        assertEquals(Outcome.PRIVATE, request(this.a, e));
        befriend(this.a, this.c);
        assertEquals(Outcome.SENT, request(this.a, e), "a friend of a friend");
    }

    @Test
    void storedPrivacyIsReadLeniently() throws Exception {
        setPrivacy(this.b, " NOBODY ");
        assertEquals(Outcome.PRIVATE, request(this.a, this.b), "case and spaces are ignored, like the settings registry");
        setPrivacy(this.c, "sideways");
        assertEquals(Outcome.SENT, request(this.a, this.c), "an unreadable row reads as the default");
    }

    @Test
    void theServersDefaultAppliesWithoutARow() throws Exception {
        // features/settings.yml defaults: friends-requests: known. A player without a row follows it (storing the default
        // deletes the row), one with a row keeps their own choice.
        this.privacyRule.set(new StoredSetting<>(null, Privacy.KNOWN, FriendPrefs.REQUESTS::decodeOrNull));
        assertEquals(Outcome.PRIVATE, request(this.a, this.b), "no row: the server's default, known");
        setPrivacy(this.c, Privacy.EVERYONE);
        assertEquals(Outcome.SENT, request(this.a, this.c), "a stored choice wins over the server's default");
    }

    @Test
    void theServersLockWinsOverStoredRows() throws Exception {
        setPrivacy(this.b, Privacy.EVERYONE);
        this.privacyRule.set(new StoredSetting<>(Privacy.NOBODY, Privacy.NOBODY, FriendPrefs.REQUESTS::decodeOrNull));
        assertEquals(Outcome.PRIVATE, request(this.a, this.b), "locked to nobody, whatever b stored");
        this.privacyRule.set(new StoredSetting<>(Privacy.EVERYONE, Privacy.EVERYONE, FriendPrefs.REQUESTS::decodeOrNull));
        setPrivacy(this.c, Privacy.NOBODY);
        assertEquals(Outcome.SENT, request(this.a, this.c), "locked to everyone, whatever c stored");
    }

    @Test
    void privacyRuleFollowsThePlayerSettingsOverrides() throws Exception {
        PlayerSettings settings = new PlayerSettings(this.database, null, Logger.getLogger("friends-test"));
        FriendPrefs.register(settings, () -> true);
        this.privacyRule.set(StoredSetting.of(settings, FriendPrefs.REQUESTS));
        assertEquals(Outcome.SENT, request(this.a, this.b), "no overrides: everyone");
        assertEquals(Outcome.DONE, run(this.store.cancel(this.a, this.b, this.rules)).outcome());

        settings.overrides(new Overrides(Map.of(FriendPrefs.REQUESTS.id(), "known"), Map.of(), Set.of()));
        this.privacyRule.set(StoredSetting.of(settings, FriendPrefs.REQUESTS));
        assertEquals(Outcome.PRIVATE, request(this.a, this.c), "defaults: known");

        settings.overrides(new Overrides(Map.of(), Map.of(FriendPrefs.REQUESTS.id(), "nobody"), Set.of()));
        StoredSetting<Privacy> locked = StoredSetting.of(settings, FriendPrefs.REQUESTS);
        assertEquals(Privacy.NOBODY, locked.forced());
        assertEquals(Privacy.NOBODY, locked.resolve("everyone"), "locked: nobody, whatever is stored");

        settings.overrides(new Overrides(Map.of(FriendPrefs.REQUESTS.id(), "known"), Map.of(), Set.of(FriendPrefs.REQUESTS.id())));
        StoredSetting<Privacy> hidden = StoredSetting.of(settings, FriendPrefs.REQUESTS);
        assertEquals(Privacy.KNOWN, hidden.resolve("everyone"), "hidden: everyone reads the server's default, stored rows are ignored");
    }

    @Test
    void settingRowsReadManyPlayersAtOnce() throws Exception {
        setPrivacy(this.a, Privacy.NOBODY);
        setPrivacy(this.c, Privacy.KNOWN);
        List<UUID> players = new ArrayList<>(List.of(this.a, this.b, this.c));
        for (int i = 0; i < FriendStore.SETTING_BATCH + 5; i++) {
            players.add(UUID.randomUUID());
        }
        Map<UUID, String> rows = this.store.write(this.store.settingRows(FriendPrefs.REQUESTS.id(), players)).get();
        assertEquals(Map.of(this.a, "nobody", this.c, "known"), rows, "only players with a row, across batches");
        assertEquals(Map.of(), this.store.write(this.store.settingRows(FriendPrefs.REQUESTS.id(), List.of())).get());
    }

    @Test
    void limitsAreCheckedWhenAccepting() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.SENDER_FULL, run(this.store.accept(this.b, this.a, this.rules, 0, true, false)).outcome(),
            "b's own limit, as read now");
        this.rules = new FriendRules(7 * DAY, 7 * DAY, 20, 50, 30, 1, 500, 10);
        befriend(this.a, this.c);
        assertEquals(Outcome.TARGET_FULL, run(this.store.accept(this.b, this.a, this.rules, 50, true, false)).outcome(),
            "a filled up meanwhile (default limit 1)");
        this.store.write(this.store.saveProfile(this.a, 5, "Patron")).get();
        assertEquals(Outcome.BECAME_FRIENDS, run(this.store.accept(this.b, this.a, this.rules, 50, true, false)).outcome(),
            "a's stored rank limit counts while a is offline");
    }

    @Test
    void senderLimitBlocksRequests() throws Exception {
        assertEquals(Outcome.SENDER_FULL, run(this.store.request(this.a, this.b, this.rules,
            new FriendStore.RequestFlags(false, false, 0, false, true))).outcome());
        this.rules = new FriendRules(7 * DAY, 7 * DAY, 20, 50, 30, 1, 500, 10);
        befriend(this.b, this.c);
        assertEquals(Outcome.TARGET_FULL, request(this.a, this.b));
    }

    // ------------------------------------------------------------------ friendships

    @Test
    void removeEndsBothDirectionsAndLeavesATomb() throws Exception {
        befriend(this.a, this.b);
        assertEquals(Outcome.DONE, run(this.store.favourite(this.a, this.b, true, 10)).outcome());
        assertEquals(Outcome.DONE, run(this.store.note(this.a, this.b, "met at spawn")).outcome());
        assertEquals(Outcome.DONE, run(this.store.remove(this.a, this.b, this.a.toString(), false)).outcome());
        assertFalse(this.graph.friends(this.a, this.b));
        assertEquals(0, count("SELECT COUNT(*) FROM friends"), "notes and favourites go with the rows");
        assertTrue(this.graph.recentlyFriends(this.a, this.b, Duration.ofDays(1)));
        assertEquals(Outcome.NOT_FRIENDS, run(this.store.remove(this.a, this.b, this.a.toString(), false)).outcome());

        FriendGraph fresh = new FriendGraph(this.clock::get, () -> Duration.ofDays(7), FriendGraph.TeleportPolicy.NEVER);
        fresh.prepare(this.b);
        fresh.install(this.b, this.store.write(this.store.load(this.b, this.rules, Duration.ofDays(7).toMillis())).get());
        assertTrue(fresh.recentlyFriends(this.a, this.b, Duration.ofDays(1)), "the removal is read back from the history");
        this.clock.addAndGet(2 * DAY);
        assertFalse(fresh.recentlyFriends(this.a, this.b, Duration.ofDays(1)), "outside the window");
        assertTrue(fresh.recentlyFriends(this.a, this.b, Duration.ofDays(3)));
        assertTrue(fresh.recentlyFriends(this.a, this.b, Duration.ofDays(30)), "capped at the 7 day memory, still inside it");
        this.clock.addAndGet(6 * DAY);
        assertFalse(fresh.recentlyFriends(this.a, this.b, Duration.ofDays(30)), "the memory is at most 7 days");
    }

    @Test
    void favouritesAreCapped() throws Exception {
        befriend(this.a, this.b);
        befriend(this.a, this.c);
        assertEquals(Outcome.DONE, run(this.store.favourite(this.a, this.b, true, 1)).outcome());
        assertEquals(Outcome.FAVOURITES_FULL, run(this.store.favourite(this.a, this.c, true, 1)).outcome());
        assertTrue(this.graph.edge(this.a, this.b).favourite());
        assertFalse(this.graph.edge(this.b, this.a).favourite(), "favourites are one-sided");
        assertEquals(Outcome.DONE, run(this.store.favourite(this.a, this.b, false, 1)).outcome());
        assertEquals(Outcome.DONE, run(this.store.favourite(this.a, this.c, true, 1)).outcome());
        assertEquals(Outcome.NOT_FRIENDS, run(this.store.favourite(this.b, this.c, true, 1)).outcome());
    }

    @Test
    void notesAreSetAndCleared() throws Exception {
        befriend(this.a, this.b);
        assertEquals(Outcome.DONE, run(this.store.note(this.a, this.b, NoteText.clean("  builds the farms  "))).outcome());
        assertEquals("builds the farms", this.store.notes(this.a).get().get(this.b));
        assertNull(this.store.notes(this.b).get().get(this.a), "notes are private");
        assertEquals("builds the farms", this.store.profile(this.a, this.b, false).get().note());
        assertEquals(Outcome.DONE, run(this.store.note(this.a, this.b, null)).outcome());
        assertTrue(this.store.notes(this.a).get().isEmpty());
        assertEquals(Outcome.NOT_FRIENDS, run(this.store.note(this.a, this.c, "x")).outcome());
    }

    @Test
    void staffToolsBypassLimitsButNotTheHardCap() throws Exception {
        this.rules = new FriendRules(7 * DAY, 7 * DAY, 20, 50, 30, 1, 2, 10);
        assertEquals(Outcome.SENT, request(this.c, this.a));
        assertEquals(Outcome.BECAME_FRIENDS, run(this.store.staffAdd(this.a, this.b, "console", 1, true, false)).outcome());
        assertEquals("pending", state(this.c, this.a), "requests of other pairs stay");
        assertTrue(this.graph.friends(this.a, this.b));
        assertEquals(List.of(this.a), this.store.write(this.store.takeNotices(this.b)).get(), "the offline side is told later");
        assertEquals(Outcome.SENDER_FULL, run(this.store.staffAdd(this.a, this.c, "console", 1, true, true)).outcome());
        assertEquals(Outcome.ALREADY_FRIENDS, run(this.store.staffAdd(this.b, this.a, "console", 1, true, true)).outcome());
        assertEquals(Outcome.DONE, run(this.store.remove(this.a, this.b, "console", true)).outcome());
        assertEquals(1, count("SELECT COUNT(*) FROM friend_log WHERE action = 'staff_remove' AND actor = 'console'"));

        assertEquals(Outcome.SENT, request(this.b, this.a));
        FriendStore.Result cleared = run(this.store.clearRequests(this.a, "console"));
        assertEquals(2, cleared.count(), "both directions, every state");
        assertEquals(0, count("SELECT COUNT(*) FROM friend_requests"));
        assertTrue(this.graph.incoming(this.a, 0).isEmpty() && this.graph.outgoing(this.c, 0).isEmpty());
    }

    @Test
    void denyAllHidesEveryVisibleRequest() throws Exception {
        assertEquals(Outcome.SENT, request(this.b, this.a));
        assertEquals(Outcome.SENT, request(this.c, this.a));
        FriendStore.Result result = run(this.store.denyAll(this.a, this.rules));
        assertEquals(Outcome.DONE, result.outcome());
        assertEquals(2, result.count());
        assertTrue(this.graph.incoming(this.a, 0).isEmpty());
        assertTrue(this.graph.outgoing(this.b, 0).containsKey(this.a), "senders still see them waiting");
        assertEquals(Outcome.GONE, run(this.store.denyAll(this.a, this.rules)).outcome());
    }

    // ------------------------------------------------------------------ races

    @Test
    void sixtyFourAcceptsMakeOneFriendship() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        List<CompletableFuture<FriendStore.Result>> futures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<FriendStore.Result> future = new CompletableFuture<>();
            futures.add(future);
            threads.add(Thread.ofPlatform().start(() ->
                this.store.write(this.store.accept(this.b, this.a, this.rules, 50, true, false)).whenComplete((r, e) -> {
                    if (e != null) {
                        future.completeExceptionally(e);
                    } else {
                        future.complete(r);
                    }
                })));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        List<FriendStore.Result> results = new ArrayList<>();
        for (CompletableFuture<FriendStore.Result> future : futures) {
            results.add(future.get());
        }
        Collections.shuffle(results, new Random(7));
        int became = 0;
        for (FriendStore.Result result : results) {
            this.graph.apply(result.changes());
            if (result.outcome() == Outcome.BECAME_FRIENDS) {
                became++;
            } else {
                assertTrue(result.outcome() == Outcome.ALREADY_FRIENDS || result.outcome() == Outcome.GONE, result.outcome().name());
            }
        }
        assertEquals(1, became);
        assertEquals(2, count("SELECT COUNT(*) FROM friends"));
        assertEquals(1, count("SELECT COUNT(*) FROM friend_log WHERE action = 'accept'"));
        assertTrue(this.graph.friends(this.a, this.b));
    }

    @Test
    void removeAndAcceptRacesEndConsistent() throws Exception {
        Random random = new Random(42);
        for (int round = 0; round < 20; round++) {
            assertEquals(Outcome.SENT, request(this.a, this.b), "round " + round);
            List<CompletableFuture<FriendStore.Result>> futures = new ArrayList<>();
            futures.add(this.store.write(this.store.accept(this.b, this.a, this.rules, 50, true, false)));
            futures.add(this.store.write(this.store.remove(this.a, this.b, this.a.toString(), false)));
            futures.add(this.store.write(this.store.favourite(this.a, this.b, true, 10)));
            futures.add(this.store.write(this.store.cancel(this.a, this.b, this.rules)));
            futures.add(this.store.write(this.store.remove(this.b, this.a, this.b.toString(), false)));
            List<FriendStore.Result> results = new ArrayList<>();
            for (CompletableFuture<FriendStore.Result> future : futures) {
                results.add(future.get());
            }
            Collections.shuffle(results, random);
            results.forEach(r -> this.graph.apply(r.changes()));
            assertMemoryMatchesStorage();
            if (this.graph.friends(this.a, this.b)) {
                assertEquals(Outcome.DONE, run(this.store.remove(this.a, this.b, "test", false)).outcome());
            }
            run(this.store.clearRequests(this.a, "test"));
        }
    }

    @Test
    void memoryTakesOnlyNewerChanges() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        FriendStore.Result accepted = this.store.write(this.store.accept(this.b, this.a, this.rules, 50, true, false)).get();
        FriendStore.Result removed = this.store.write(this.store.remove(this.a, this.b, "test", false)).get();
        this.graph.apply(removed.changes());
        this.graph.apply(accepted.changes());
        assertFalse(this.graph.friends(this.a, this.b), "the older accept arriving late does not undo the removal");
    }

    @Test
    void loadingReplaysOnlyChangesAfterItsSnapshot() throws Exception {
        UUID d = UUID.randomUUID();
        FriendStore.Result before = this.store.write(this.store.staffAdd(this.a, d, "test", 500, true, true)).get();
        this.graph.apply(before.changes());
        this.graph.prepare(d);
        CompletableFuture<FriendGraph.Snapshot> snapshot = this.store.write(this.store.load(d, this.rules, 0));
        FriendStore.Result after = this.store.write(this.store.staffAdd(this.b, d, "test", 500, true, true)).get();
        this.graph.apply(after.changes());
        this.graph.apply(before.changes());
        assertTrue(this.graph.install(d, snapshot.get()));
        this.loaded.add(d);
        assertEquals(Set.of(this.a, this.b), this.graph.friendsOf(d));
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    void sweeperKeepsDenyMemoryAndDropsTheRest() throws Exception {
        UUID d = UUID.randomUUID();
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.SENT, request(this.c, this.b));
        this.clock.addAndGet(6 * DAY);
        assertEquals(Outcome.DONE, run(this.store.deny(this.b, this.c, this.rules)).outcome());
        assertEquals(Outcome.SENT, request(d, this.b));
        this.clock.addAndGet(2 * DAY);
        assertEquals(1, (int) this.store.write(this.store.sweep(this.rules, 90 * DAY)).get(), "a's run-out request");
        assertNull(state(this.a, this.b));
        assertEquals("shadow", state(this.c, this.b), "denied two days ago: kept as deny memory though run out");
        assertEquals("pending", state(d, this.b));
        this.clock.addAndGet(6 * DAY);
        this.store.write(this.store.sweep(this.rules, 90 * DAY)).get();
        assertNull(state(this.c, this.b), "the deny memory ran out too");
        assertNull(state(d, this.b), "and d's request ran out");
        int logs = count("SELECT COUNT(*) FROM friend_log");
        assertTrue(logs > 0);
        this.clock.addAndGet(100 * DAY);
        this.store.write(this.store.sweep(this.rules, 90 * DAY)).get();
        assertEquals(0, count("SELECT COUNT(*) FROM friend_log"), "history older than log.keep");
    }

    @Test
    void closedRowsLeaveAfterTheDenyMemory() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.DONE, run(this.store.deny(this.b, this.a, this.rules)).outcome());
        assertEquals(Outcome.DONE, run(this.store.cancel(this.a, this.b, this.rules)).outcome());
        this.clock.addAndGet(3 * DAY);
        this.store.write(this.store.sweep(this.rules, 90 * DAY)).get();
        assertEquals("closed", state(this.a, this.b));
        this.clock.addAndGet(5 * DAY);
        this.store.write(this.store.sweep(this.rules, 90 * DAY)).get();
        assertNull(state(this.a, this.b));
    }

    @Test
    void staffViewsShowTheTruth() throws Exception {
        assertEquals(Outcome.SENT, request(this.a, this.b));
        assertEquals(Outcome.DONE, run(this.store.deny(this.b, this.a, this.rules)).outcome());
        List<FriendStore.RequestRow> rows = this.store.requestRows(this.b).get();
        assertEquals(1, rows.size());
        assertEquals(PairState.State.SHADOW, rows.getFirst().state());
        assertTrue(rows.getFirst().decided() > 0);
        befriend(this.b, this.c);
        FriendStore.StaffFriends staffView = this.store.staffFriends(this.c).get();
        assertEquals(1, staffView.rows().size());
        assertEquals(0, staffView.storedLimit(), "no rank limit stored yet");
        this.store.write(this.store.saveProfile(this.c, 200, "Legend")).get();
        assertEquals(200, this.store.staffFriends(this.c).get().storedLimit(), "the staff view reads the stored rank limit");
        List<FriendStore.LogRow> history = this.store.history(this.b, 10, 0).get();
        Set<String> actions = new HashSet<>();
        history.forEach(row -> actions.add(row.action()));
        assertEquals(Set.of("request", "deny", "staff_add"), actions);
        Map<UUID, List<UUID>> mutual = this.store.mutual(this.a, List.of(this.b)).get();
        assertTrue(mutual.isEmpty(), "a has no friends yet");
        befriend(this.a, this.c);
        assertEquals(List.of(this.c), this.store.mutual(this.a, List.of(this.b)).get().get(this.b));
    }
}
