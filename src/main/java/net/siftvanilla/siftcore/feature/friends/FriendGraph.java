package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.link.FriendLookup;

/**
 * The friends of every loaded player, in memory. A player is loaded from just before they join until a grace period
 * after they leave; offline players are only in storage. Implements {@link FriendLookup} for other features.
 * <p>
 * Nodes are immutable snapshots in a concurrent map: reads take no lock and always see a consistent node. Changes
 * are rare and swap nodes under one lock. Every write unit takes a sequence number inside the database writer, so
 * sequence order is commit order; but the database completes futures on a small pool, so two results can arrive
 * here in the other order. Each change therefore carries the full state of its pair after the unit, and a node only
 * takes it when it is newer than what the node already has for that pair. A node that is still loading buffers
 * changes and replays the ones newer than its snapshot. Removals leave a tombstone so recent friendships can be
 * recognised for anti-farm checks. Pure: no Bukkit.
 */
public final class FriendGraph implements FriendLookup {

    /** Where a node is in its life. */
    public enum Phase {
        /** Installed at pre-login; its snapshot is being read. */
        LOADING,
        /** Loaded; the player is joining or online. */
        READY,
        /** The player left; the node is dropped after the grace period unless they come back. */
        LEAVING
    }

    /** A friendship from the owner's side. */
    public record Edge(long since, boolean favourite) {
    }

    /** A removed friendship, remembered for anti-farm checks. */
    public record Tomb(long ts, long seq) {
    }

    /**
     * The friends state of one player.
     *
     * @param id        the player
     * @param phase     loading, ready or leaving
     * @param baseSeq   the sequence number of the snapshot it was loaded from; while LOADING, the id of the load that
     *                  will fill it (a load started for an earlier login must not fill a newer node)
     * @param friends   friend to edge
     * @param incoming  requests the player can see (pending), sender to created; filter by expiry when reading
     * @param outgoing  requests the player sent and still sees (pending or hidden), target to created
     * @param tombs     removed friendships, other to tombstone
     * @param pairSeq   for every other player this node took a change about, that change's sequence number
     * @param rankLimit the friend limit granted by the player's rank (0 = none known)
     * @param created   when the node was installed
     * @param leftAt    when the player left (LEAVING), 0 otherwise
     * @param buffered  changes that arrived while loading
     */
    public record Node(UUID id, Phase phase, long baseSeq, Map<UUID, Edge> friends, Map<UUID, Long> incoming,
                       Map<UUID, Long> outgoing, Map<UUID, Tomb> tombs, Map<UUID, Long> pairSeq, int rankLimit,
                       long created, long leftAt, List<PairChange> buffered) {

        public Node {
            friends = Map.copyOf(friends);
            incoming = Map.copyOf(incoming);
            outgoing = Map.copyOf(outgoing);
            tombs = Map.copyOf(tombs);
            pairSeq = Map.copyOf(pairSeq);
            buffered = List.copyOf(buffered);
        }

        /** Whether the node holds loaded data (ready or leaving). */
        public boolean loaded() {
            return this.phase != Phase.LOADING;
        }

        Node with(Phase newPhase, long newLeftAt) {
            return new Node(this.id, newPhase, this.baseSeq, this.friends, this.incoming, this.outgoing, this.tombs,
                this.pairSeq, this.rankLimit, this.created, newLeftAt, this.buffered);
        }

        Node withLimit(int limit) {
            return new Node(this.id, this.phase, this.baseSeq, this.friends, this.incoming, this.outgoing, this.tombs,
                this.pairSeq, limit, this.created, this.leftAt, this.buffered);
        }
    }

    /**
     * The state of one pair after a write unit, as committed. {@code ab} is the request a sent to b, {@code ba} the
     * one b sent to a (null when there is none).
     *
     * @param seq       the unit's sequence number
     * @param link      the friendship, or null when they are not friends
     * @param ab        a's request to b, or null
     * @param ba        b's request to a, or null
     * @param removedAt when this unit ended their friendship, 0 if it did not
     */
    public record PairChange(UUID a, UUID b, long seq, Link link, Req ab, Req ba, long removedAt) {
    }

    /** A friendship as both sides see it. */
    public record Link(long since, boolean favouriteA, boolean favouriteB) {
    }

    /** A request row as memory needs it. */
    public record Req(PairState.State state, long created) {
    }

    /**
     * What a load read from storage.
     *
     * @param seq         the sequence number taken inside the load unit (ordered with every other unit)
     * @param friends     friend to edge
     * @param incoming    pending requests to the player that have not expired, sender to created
     * @param outgoing    pending or hidden requests from the player that have not expired, target to created
     * @param tombs       friendships removed within the anti-farm memory, other to time
     * @param storedLimit the rank limit stored for the player ({@code friend_profiles}), -1 when there is no row
     * @param storedLabel the rank label stored for the player, null when none
     */
    public record Snapshot(long seq, Map<UUID, Edge> friends, Map<UUID, Long> incoming, Map<UUID, Long> outgoing,
                           Map<UUID, Long> tombs, int storedLimit, String storedLabel) {
    }

    /** How a player handles teleport requests from friends ({@code friends-tpa}). */
    public interface TeleportPolicy {

        TeleportPolicy NEVER = new TeleportPolicy() {
            @Override
            public FriendPrefs.AutoTpa mode(UUID target) {
                return FriendPrefs.AutoTpa.NOBODY;
            }

            @Override
            public boolean ignores(UUID player, UUID other) {
                return false;
            }
        };

        /** The target's auto-accept choice. */
        FriendPrefs.AutoTpa mode(UUID target);

        /** Whether {@code player} ignores {@code other}. */
        boolean ignores(UUID player, UUID other);

        /** Whether favourites are on (the friends config gives favourite slots). */
        default boolean favouritesOn() {
            return false;
        }
    }

    private final Map<UUID, Node> nodes = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final AtomicLong loads = new AtomicLong();
    private final LongSupplier clock;
    private final Supplier<Duration> remember;
    private final TeleportPolicy policy;

    public FriendGraph(LongSupplier clock, Supplier<Duration> remember, TeleportPolicy policy) {
        this.clock = clock;
        this.remember = remember;
        this.policy = policy;
    }

    // ------------------------------------------------------------------ reads (any thread, no lock)

    public Node node(UUID player) {
        return this.nodes.get(player);
    }

    /** The node when it holds loaded data, else null. */
    public Node loaded(UUID player) {
        Node node = this.nodes.get(player);
        return node != null && node.loaded() ? node : null;
    }

    public boolean isLoaded(UUID player) {
        return loaded(player) != null;
    }

    public Collection<Node> nodes() {
        return List.copyOf(this.nodes.values());
    }

    public int size() {
        return this.nodes.size();
    }

    /** The edge from {@code owner} to {@code friend}, or null. */
    public Edge edge(UUID owner, UUID friend) {
        Node node = loaded(owner);
        return node == null ? null : node.friends().get(friend);
    }

    /** Requests the player can see, sender to created, without expired ones. */
    public Map<UUID, Long> incoming(UUID player, long expiredBefore) {
        Node node = loaded(player);
        return node == null ? Map.of() : fresh(node.incoming(), expiredBefore);
    }

    /** Requests the player sent and still sees, target to created, without expired ones. */
    public Map<UUID, Long> outgoing(UUID player, long expiredBefore) {
        Node node = loaded(player);
        return node == null ? Map.of() : fresh(node.outgoing(), expiredBefore);
    }

    private static Map<UUID, Long> fresh(Map<UUID, Long> requests, long expiredBefore) {
        Map<UUID, Long> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : requests.entrySet()) {
            if (entry.getValue() >= expiredBefore) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @Override
    public boolean friends(UUID a, UUID b) {
        Node node = loaded(a);
        if (node != null) {
            return node.friends().containsKey(b);
        }
        node = loaded(b);
        return node != null && node.friends().containsKey(a);
    }

    @Override
    public Set<UUID> friendsOf(UUID player) {
        Node node = loaded(player);
        return node == null ? Set.of() : node.friends().keySet();
    }

    @Override
    public boolean recentlyFriends(UUID a, UUID b, Duration window) {
        if (friends(a, b)) {
            return true;
        }
        Duration limit = window.compareTo(this.remember.get()) < 0 ? window : this.remember.get();
        long since = this.clock.getAsLong() - limit.toMillis();
        return tombSince(a, b, since) || tombSince(b, a, since);
    }

    private boolean tombSince(UUID owner, UUID other, long since) {
        Node node = loaded(owner);
        if (node == null) {
            return false;
        }
        Tomb tomb = node.tombs().get(other);
        return tomb != null && tomb.ts() >= since;
    }

    @Override
    public boolean favouritesEnabled() {
        return this.policy.favouritesOn();
    }

    @Override
    public boolean autoAcceptTeleport(UUID target, UUID requester) {
        Node node = loaded(target);
        if (node == null) {
            return false;
        }
        Edge edge = node.friends().get(requester);
        if (edge == null || this.policy.ignores(target, requester)) {
            return false;
        }
        return switch (this.policy.mode(target)) {
            case ALL -> true;
            case FAVOURITES -> edge.favourite();
            case NOBODY -> false;
        };
    }

    // ------------------------------------------------------------------ lifecycle (serialized by the lock)

    /**
     * Makes sure the player has a node before they join. Returns the id of the load that must start for a new loading
     * node, or 0 when none is needed: a node that is still loaded (the player left within the grace period) is simply
     * taken back, and a node that is already loading keeps its load.
     */
    public long prepareLoad(UUID player) {
        synchronized (this.lock) {
            Node node = this.nodes.get(player);
            long now = this.clock.getAsLong();
            if (node == null) {
                long load = this.loads.incrementAndGet();
                this.nodes.put(player, new Node(player, Phase.LOADING, load, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    0, now, 0, List.of()));
                return load;
            }
            if (node.phase() == Phase.LEAVING) {
                this.nodes.put(player, node.with(Phase.READY, 0));
            }
            return 0;
        }
    }

    /** {@link #prepareLoad}: true when a load must start. */
    public boolean prepare(UUID player) {
        return prepareLoad(player) != 0;
    }

    /** Installs a snapshot into the player's loading node, whichever load it waits for (startup, tests). */
    public boolean install(UUID player, Snapshot snapshot) {
        return install(player, snapshot, 0);
    }

    /**
     * Installs a loaded snapshot into the player's loading node and replays the changes that arrived meanwhile.
     * Returns false when there is no loading node any more (it was dropped, or already loaded), or when the node waits
     * for another load: the player left while this load was queued and logged in again, and changes made between the
     * two logins were buffered by neither node, so only the newer load may fill it.
     *
     * @param load the id {@link #prepareLoad} returned, or 0 for any load
     */
    public boolean install(UUID player, Snapshot snapshot, long load) {
        synchronized (this.lock) {
            Node node = this.nodes.get(player);
            if (node == null || node.phase() != Phase.LOADING || (load != 0 && node.baseSeq() != load)) {
                return false;
            }
            Map<UUID, Tomb> tombs = new HashMap<>();
            snapshot.tombs().forEach((other, ts) -> tombs.put(other, new Tomb(ts, snapshot.seq())));
            int rankLimit = node.rankLimit() != 0 ? node.rankLimit() : Math.max(0, snapshot.storedLimit());
            Node fresh = new Node(player, Phase.READY, snapshot.seq(), snapshot.friends(), snapshot.incoming(),
                snapshot.outgoing(), tombs, Map.of(), rankLimit, node.created(), 0, List.of());
            for (PairChange change : node.buffered()) {
                fresh = applyTo(fresh, change);
            }
            this.nodes.put(player, fresh);
            return true;
        }
    }

    /** Stores the friend limit of the player's rank. Returns true when it changed. */
    public boolean rankLimit(UUID player, int limit) {
        synchronized (this.lock) {
            Node node = this.nodes.get(player);
            if (node == null || node.rankLimit() == limit) {
                return false;
            }
            this.nodes.put(player, node.withLimit(limit));
            return true;
        }
    }

    /** Marks the player as gone and returns the leave time, which {@link #evict} needs; 0 when not loaded. */
    public long leave(UUID player) {
        synchronized (this.lock) {
            Node node = this.nodes.get(player);
            if (node == null) {
                return 0;
            }
            long now = Math.max(1, this.clock.getAsLong());
            if (node.phase() == Phase.LOADING) {
                this.nodes.remove(player);
                return 0;
            }
            this.nodes.put(player, node.with(Phase.LEAVING, now));
            return now;
        }
    }

    /** Drops the node if the player is still gone since {@code leftAt}. */
    public boolean evict(UUID player, long leftAt) {
        synchronized (this.lock) {
            Node node = this.nodes.get(player);
            if (node != null && node.phase() == Phase.LEAVING && node.leftAt() == leftAt) {
                this.nodes.remove(player);
                return true;
            }
            return false;
        }
    }

    /** Drops the player's node if it is still waiting for this load (the load failed). */
    public boolean dropLoad(UUID player, long load) {
        synchronized (this.lock) {
            Node node = this.nodes.get(player);
            if (node != null && node.phase() == Phase.LOADING && node.baseSeq() == load) {
                this.nodes.remove(player);
                return true;
            }
            return false;
        }
    }

    /** Drops a node no matter its phase (players who never joined, shutdown). */
    public void drop(UUID player) {
        synchronized (this.lock) {
            this.nodes.remove(player);
        }
    }

    public void clear() {
        synchronized (this.lock) {
            this.nodes.clear();
        }
    }

    /** Forgets tombstones older than {@code before}. */
    public void pruneTombs(long before) {
        synchronized (this.lock) {
            for (Node node : List.copyOf(this.nodes.values())) {
                if (node.tombs().values().stream().anyMatch(t -> t.ts() < before)) {
                    Map<UUID, Tomb> kept = new HashMap<>(node.tombs());
                    kept.values().removeIf(t -> t.ts() < before);
                    this.nodes.put(node.id(), new Node(node.id(), node.phase(), node.baseSeq(), node.friends(),
                        node.incoming(), node.outgoing(), kept, node.pairSeq(), node.rankLimit(), node.created(),
                        node.leftAt(), node.buffered()));
                }
            }
        }
    }

    // ------------------------------------------------------------------ changes

    /** Applies committed pair changes to every loaded or loading node they concern. */
    public void apply(Collection<PairChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        synchronized (this.lock) {
            for (PairChange change : changes) {
                applyToNode(change.a(), change);
                applyToNode(change.b(), change);
            }
        }
    }

    private void applyToNode(UUID player, PairChange change) {
        Node node = this.nodes.get(player);
        if (node == null) {
            return;
        }
        if (node.phase() == Phase.LOADING) {
            List<PairChange> buffered = new ArrayList<>(node.buffered());
            buffered.add(change);
            this.nodes.put(player, new Node(node.id(), node.phase(), node.baseSeq(), node.friends(), node.incoming(),
                node.outgoing(), node.tombs(), node.pairSeq(), node.rankLimit(), node.created(), node.leftAt(), buffered));
            return;
        }
        this.nodes.put(player, applyTo(node, change));
    }

    /** The node with the change applied, if the change is newer than what it has for that pair. */
    static Node applyTo(Node node, PairChange change) {
        boolean isA = node.id().equals(change.a());
        if (!isA && !node.id().equals(change.b())) {
            return node;
        }
        UUID other = isA ? change.b() : change.a();
        Map<UUID, Tomb> tombs = node.tombs();
        if (change.removedAt() > 0) {
            Tomb old = tombs.get(other);
            if (old == null || old.ts() < change.removedAt()) {
                tombs = new HashMap<>(tombs);
                tombs.put(other, new Tomb(change.removedAt(), change.seq()));
            }
        }
        long known = Math.max(node.baseSeq(), node.pairSeq().getOrDefault(other, 0L));
        if (change.seq() <= known) {
            return tombs == node.tombs() ? node : new Node(node.id(), node.phase(), node.baseSeq(), node.friends(),
                node.incoming(), node.outgoing(), tombs, node.pairSeq(), node.rankLimit(), node.created(), node.leftAt(),
                node.buffered());
        }
        Map<UUID, Edge> friends = new HashMap<>(node.friends());
        if (change.link() == null) {
            friends.remove(other);
        } else {
            friends.put(other, new Edge(change.link().since(), isA ? change.link().favouriteA() : change.link().favouriteB()));
        }
        Req mine = isA ? change.ab() : change.ba();
        Req theirs = isA ? change.ba() : change.ab();
        Map<UUID, Long> outgoing = new HashMap<>(node.outgoing());
        if (mine != null && (mine.state() == PairState.State.PENDING || mine.state() == PairState.State.SHADOW)) {
            outgoing.put(other, mine.created());
        } else {
            outgoing.remove(other);
        }
        Map<UUID, Long> incoming = new HashMap<>(node.incoming());
        if (theirs != null && theirs.state() == PairState.State.PENDING) {
            incoming.put(other, theirs.created());
        } else {
            incoming.remove(other);
        }
        Map<UUID, Long> pairSeq = new HashMap<>(node.pairSeq());
        pairSeq.put(other, change.seq());
        return new Node(node.id(), node.phase(), node.baseSeq(), friends, incoming, outgoing, tombs, pairSeq,
            node.rankLimit(), node.created(), node.leftAt(), node.buffered());
    }
}
