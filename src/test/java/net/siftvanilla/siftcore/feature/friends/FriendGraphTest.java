package net.siftvanilla.siftcore.feature.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import org.junit.jupiter.api.Test;

/** Memory: commit-ordered changes, loading with replay, tombstones and the lookups other features use. */
class FriendGraphTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    private final AtomicLong clock = new AtomicLong(1_000 * DAY);
    private final Map<UUID, AutoAccept> tpa = new HashMap<>();
    private final Map<UUID, Set<UUID>> ignores = new HashMap<>();
    private final FriendGraph graph = new FriendGraph(this.clock::get, () -> Duration.ofDays(7), new FriendGraph.TeleportPolicy() {
        @Override
        public AutoAccept mode(UUID target) {
            return FriendGraphTest.this.tpa.getOrDefault(target, AutoAccept.NOBODY);
        }

        @Override
        public boolean ignores(UUID player, UUID other) {
            return FriendGraphTest.this.ignores.getOrDefault(player, Set.of()).contains(other);
        }

        @Override
        public boolean sameTeam(UUID one, UUID two) {
            return FriendGraphTest.this.team.contains(one) && FriendGraphTest.this.team.contains(two);
        }

        @Override
        public boolean favouritesOn() {
            return FriendGraphTest.this.favouritesOn;
        }
    });
    private boolean favouritesOn = true;
    private final Set<UUID> team = new java.util.HashSet<>();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private void ready(UUID... players) {
        for (UUID player : players) {
            assertTrue(this.graph.prepare(player));
            assertTrue(this.graph.install(player, empty(0)));
        }
    }

    private static FriendGraph.Snapshot empty(long seq) {
        return new FriendGraph.Snapshot(seq, Map.of(), Map.of(), Map.of(), Map.of(), -1, null);
    }

    private static FriendGraph.PairChange friends(UUID x, UUID y, long seq, boolean favX, boolean favY) {
        return new FriendGraph.PairChange(x, y, seq, new FriendGraph.Link(seq, favX, favY), null, null, 0);
    }

    private static FriendGraph.PairChange apart(UUID x, UUID y, long seq, long removedAt) {
        return new FriendGraph.PairChange(x, y, seq, null, null, null, removedAt);
    }

    private static FriendGraph.PairChange request(UUID from, UUID to, long seq, PairState.State state, long created) {
        return new FriendGraph.PairChange(from, to, seq, null, new FriendGraph.Req(state, created), null, 0);
    }

    @Test
    void newerChangesWinWhateverTheArrivalOrder() {
        ready(this.a, this.b);
        this.graph.apply(List.of(apart(this.a, this.b, 2, this.clock.get())));
        this.graph.apply(List.of(friends(this.a, this.b, 1, false, false)));
        assertFalse(this.graph.friends(this.a, this.b), "the removal (seq 2) beats the older add");
        this.graph.apply(List.of(friends(this.b, this.a, 3, true, false)));
        assertTrue(this.graph.friends(this.a, this.b));
        assertTrue(this.graph.edge(this.b, this.a).favourite(), "favourites follow the pair's orientation");
        assertFalse(this.graph.edge(this.a, this.b).favourite());
    }

    @Test
    void requestsShowOnTheRightSides() {
        ready(this.a, this.b);
        long now = this.clock.get();
        this.graph.apply(List.of(request(this.a, this.b, 1, PairState.State.PENDING, now)));
        assertEquals(Set.of(this.b), this.graph.outgoing(this.a, 0).keySet());
        assertEquals(Set.of(this.a), this.graph.incoming(this.b, 0).keySet());
        this.graph.apply(List.of(request(this.a, this.b, 2, PairState.State.SHADOW, now)));
        assertEquals(Set.of(this.b), this.graph.outgoing(this.a, 0).keySet(), "a still sees it waiting");
        assertTrue(this.graph.incoming(this.b, 0).isEmpty(), "b no longer sees it");
        this.graph.apply(List.of(request(this.a, this.b, 3, PairState.State.CLOSED, now)));
        assertTrue(this.graph.outgoing(this.a, 0).isEmpty());
        assertTrue(this.graph.incoming(this.a, now + 1).isEmpty());
        assertTrue(this.graph.outgoing(this.a, now + 1).isEmpty(), "filtered by expiry when read");
    }

    @Test
    void loadingBuffersAndReplaysNewerChanges() {
        ready(this.b);
        assertTrue(this.graph.prepare(this.a));
        assertFalse(this.graph.isLoaded(this.a), "still loading");
        this.graph.apply(List.of(friends(this.a, this.b, 5, false, false)));
        this.graph.apply(List.of(friends(this.a, this.c, 2, false, false)));
        FriendGraph.Snapshot snapshot = new FriendGraph.Snapshot(3, Map.of(), Map.of(), Map.of(), Map.of(), 75, "Patron");
        assertTrue(this.graph.install(this.a, snapshot));
        assertEquals(Set.of(this.b), this.graph.friendsOf(this.a), "seq 5 replayed, seq 2 was already in the snapshot");
        assertEquals(75, this.graph.node(this.a).rankLimit(), "the stored rank limit until the live one is read");
        assertFalse(this.graph.install(this.a, snapshot), "a second install is refused");
    }

    @Test
    void shuffledCompletionsEndInCommitOrder() {
        UUID[] players = new UUID[6];
        for (int i = 0; i < players.length; i++) {
            players[i] = UUID.randomUUID();
        }
        Random random = new Random(11);
        for (int trial = 0; trial < 50; trial++) {
            FriendGraph fresh = new FriendGraph(this.clock::get, () -> Duration.ofDays(7), FriendGraph.TeleportPolicy.NEVER);
            List<FriendGraph.PairChange> changes = new ArrayList<>();
            Map<String, FriendGraph.PairChange> last = new HashMap<>();
            for (long seq = 1; seq <= 200; seq++) {
                UUID x = players[random.nextInt(players.length)];
                UUID y = players[random.nextInt(players.length)];
                if (x.equals(y)) {
                    continue;
                }
                FriendGraph.PairChange change = switch (random.nextInt(3)) {
                    case 0 -> friends(x, y, seq, random.nextBoolean(), random.nextBoolean());
                    case 1 -> apart(x, y, seq, 0);
                    default -> request(x, y, seq, random.nextBoolean() ? PairState.State.PENDING : PairState.State.SHADOW, seq);
                };
                changes.add(change);
                last.put(key(x, y), change);
            }
            Collections.shuffle(changes, random);
            for (UUID player : players) {
                fresh.prepare(player);
            }
            // Half the players finish loading in the middle of the stream, half at the end.
            int split = changes.size() / 2;
            fresh.apply(changes.subList(0, split));
            for (int i = 0; i < players.length / 2; i++) {
                fresh.install(players[i], empty(0));
            }
            fresh.apply(changes.subList(split, changes.size()));
            for (int i = players.length / 2; i < players.length; i++) {
                fresh.install(players[i], empty(0));
            }
            for (FriendGraph.PairChange expected : last.values()) {
                boolean linked = expected.link() != null;
                assertEquals(linked, fresh.edge(expected.a(), expected.b()) != null, "trial " + trial);
                assertEquals(linked, fresh.edge(expected.b(), expected.a()) != null, "trial " + trial);
                if (linked) {
                    assertEquals(expected.link().favouriteA(), fresh.edge(expected.a(), expected.b()).favourite());
                    assertEquals(expected.link().favouriteB(), fresh.edge(expected.b(), expected.a()).favourite());
                }
                boolean outgoing = expected.ab() != null;
                assertEquals(outgoing, fresh.outgoing(expected.a(), 0).containsKey(expected.b()), "trial " + trial);
                boolean visible = outgoing && expected.ab().state() == PairState.State.PENDING;
                assertEquals(visible, fresh.incoming(expected.b(), 0).containsKey(expected.a()), "trial " + trial);
            }
        }
    }

    private static String key(UUID x, UUID y) {
        return x.compareTo(y) < 0 ? x + ":" + y : y + ":" + x;
    }

    @Test
    void leavingKeepsTheNodeUntilEvicted() {
        ready(this.a, this.b);
        this.graph.apply(List.of(friends(this.a, this.b, 1, false, false)));
        long left = this.graph.leave(this.a);
        assertTrue(left > 0);
        assertEquals(FriendGraph.Phase.LEAVING, this.graph.node(this.a).phase());
        assertTrue(this.graph.friends(this.a, this.b), "still answered while leaving");
        assertFalse(this.graph.prepare(this.a), "a quick rejoin needs no load");
        assertEquals(FriendGraph.Phase.READY, this.graph.node(this.a).phase());
        assertFalse(this.graph.evict(this.a, left), "the old eviction does nothing after a rejoin");
        long again = this.graph.leave(this.a);
        assertTrue(this.graph.evict(this.a, again));
        assertNull(this.graph.node(this.a));
        assertTrue(this.graph.friends(this.a, this.b), "b's node still knows");
        this.graph.leave(this.b);
        this.graph.drop(this.b);
        assertFalse(this.graph.friends(this.a, this.b), "unknown once nobody of the two is loaded");
        assertTrue(this.graph.prepare(this.c));
        assertEquals(0, this.graph.leave(this.c), "a loading node is just dropped");
        assertNull(this.graph.node(this.c));
    }

    @Test
    void aLoadFromAnEarlierLoginNeverFillsANewerNode() {
        ready(this.b);
        long first = this.graph.prepareLoad(this.a);
        assertTrue(first > 0);
        assertEquals(0, this.graph.prepareLoad(this.a), "already loading: the queued load stays the one");
        assertEquals(0, this.graph.leave(this.a), "left before the load finished: the loading node goes");
        // While a was away, b and a became friends (no node of a buffered it).
        this.graph.apply(List.of(friends(this.a, this.b, 5, false, false)));
        long second = this.graph.prepareLoad(this.a);
        assertTrue(second > first);
        FriendGraph.Snapshot stale = empty(3);
        assertFalse(this.graph.install(this.a, stale, first), "the first login's snapshot is older than the friendship");
        assertFalse(this.graph.dropLoad(this.a, first), "a failure of the first load leaves the new node alone");
        assertEquals(FriendGraph.Phase.LOADING, this.graph.node(this.a).phase());
        FriendGraph.Snapshot current = new FriendGraph.Snapshot(6, Map.of(this.b, new FriendGraph.Edge(5, false)), Map.of(), Map.of(),
            Map.of(), -1, null);
        assertTrue(this.graph.install(this.a, current, second));
        assertTrue(this.graph.node(this.a).friends().containsKey(this.b), "the newer load holds the friendship");
        assertFalse(this.graph.install(this.a, current, second), "installed once");
        long third = this.graph.prepareLoad(this.c);
        assertTrue(this.graph.dropLoad(this.c, third), "a failed load drops its own node");
        assertNull(this.graph.node(this.c));
    }

    @Test
    void recentlyFriendsRemembersRemovals() {
        ready(this.a, this.b);
        this.graph.apply(List.of(friends(this.a, this.b, 1, false, false)));
        assertTrue(this.graph.recentlyFriends(this.a, this.b, Duration.ofHours(1)), "friends now");
        this.graph.apply(List.of(apart(this.a, this.b, 2, this.clock.get())));
        assertTrue(this.graph.recentlyFriends(this.b, this.a, Duration.ofDays(1)));
        this.clock.addAndGet(2 * DAY);
        assertFalse(this.graph.recentlyFriends(this.a, this.b, Duration.ofDays(1)));
        assertTrue(this.graph.recentlyFriends(this.a, this.b, Duration.ofDays(3)));
        this.clock.addAndGet(6 * DAY);
        assertFalse(this.graph.recentlyFriends(this.a, this.b, Duration.ofDays(30)), "never longer than the memory");
        this.graph.pruneTombs(this.clock.get() - 7 * DAY);
        assertTrue(this.graph.node(this.a).tombs().isEmpty());
        assertFalse(this.graph.recentlyFriends(this.a, this.c, Duration.ofDays(30)));
    }

    @Test
    void favouriteLookup() {
        ready(this.a, this.b);
        this.graph.apply(List.of(friends(this.a, this.b, 1, true, false)));
        assertTrue(this.graph.favourite(this.a, this.b), "a marked b as a favourite");
        assertFalse(this.graph.favourite(this.b, this.a), "b did not mark a");
        assertFalse(this.graph.favourite(this.a, this.c), "not a friend");
        assertFalse(this.graph.favourite(UUID.randomUUID(), this.a), "the owner must be loaded");
        this.favouritesOn = false;
        assertFalse(this.graph.favourite(this.a, this.b), "nobody is a favourite while favourites are off");
    }

    @Test
    void autoAcceptTeleport() {
        ready(this.a, this.b, this.c);
        this.graph.apply(List.of(friends(this.a, this.b, 1, true, false), friends(this.a, this.c, 2, false, false)));
        assertFalse(this.graph.autoAcceptTeleport(this.a, this.b), "nobody by default");
        this.tpa.put(this.a, AutoAccept.FAVOURITES);
        assertTrue(this.graph.autoAcceptTeleport(this.a, this.b), "b is a's favourite");
        assertFalse(this.graph.autoAcceptTeleport(this.a, this.c));
        assertFalse(this.graph.autoAcceptTeleport(this.b, this.a), "b chose nothing");
        this.tpa.put(this.a, AutoAccept.ALL);
        assertTrue(this.graph.autoAcceptTeleport(this.a, this.c));
        this.ignores.put(this.a, Set.of(this.c));
        assertFalse(this.graph.autoAcceptTeleport(this.a, this.c), "never from someone a ignores");
        UUID stranger = UUID.randomUUID();
        assertFalse(this.graph.autoAcceptTeleport(this.a, stranger), "only friends");
        assertFalse(this.graph.autoAcceptTeleport(stranger, this.a), "the target must be loaded");

        // "Friends and teammates" (the shared friends-tpa option): friends, plus teammates who aren't friends.
        UUID mate = UUID.randomUUID();
        this.team.addAll(Set.of(this.a, mate));
        this.ignores.clear();
        assertFalse(this.graph.autoAcceptTeleport(this.a, mate), "all friends: a teammate who isn't a friend still asks");
        this.tpa.put(this.a, AutoAccept.FRIENDS_TEAM);
        assertTrue(this.graph.autoAcceptTeleport(this.a, mate), "a teammate");
        assertTrue(this.graph.autoAcceptTeleport(this.a, this.c), "a friend");
        assertFalse(this.graph.autoAcceptTeleport(this.a, stranger), "neither");
        this.ignores.put(this.a, Set.of(mate));
        assertFalse(this.graph.autoAcceptTeleport(this.a, mate), "never from someone a ignores, teammate or not");
        assertFalse(this.graph.autoAcceptTeleport(this.a, this.a), "never yourself");
    }

    @Test
    void rankLimitAndLookups() {
        ready(this.a);
        assertTrue(this.graph.rankLimit(this.a, 100));
        assertFalse(this.graph.rankLimit(this.a, 100), "unchanged");
        assertEquals(100, this.graph.node(this.a).rankLimit());
        assertEquals(Set.of(), this.graph.friendsOf(UUID.randomUUID()), "unknown players have no friends here");
        assertEquals(1, this.graph.nodes().size());
        this.graph.clear();
        assertEquals(0, this.graph.size());
    }
}
