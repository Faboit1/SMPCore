package net.siftvanilla.siftcore.feature.friends;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.SqlWork;

/**
 * The friends tables and every write unit that changes them. Each action is one unit on the database's single
 * ordered writer: all checks read first, then the changes, then the history row, inside one savepoint. Because the
 * writer runs units one after another, a check can never be overtaken by another action between reading and
 * writing, and offline players never need to be in memory. Business refusals come back as an {@link Outcome};
 * a unit only throws on a real storage error.
 * <p>
 * Every unit takes the next sequence number while it runs, so sequence order is commit order, and returns the
 * committed state of each pair it touched for {@link FriendGraph#apply}. No Bukkit here: the unit tests run every
 * unit against a real SQLite database.
 */
public final class FriendStore {

    /** History actions ({@code friend_log.action}). */
    public static final String LOG_REQUEST = "request";
    public static final String LOG_ACCEPT = "accept";
    public static final String LOG_MUTUAL = "mutual";
    public static final String LOG_DENY = "deny";
    public static final String LOG_CANCEL = "cancel";
    public static final String LOG_REMOVE = "remove";
    public static final String LOG_STAFF_ADD = "staff_add";
    public static final String LOG_STAFF_REMOVE = "staff_remove";
    public static final String LOG_STAFF_CLEAR = "staff_clear";

    /** The longest stored rank label ({@code friend_profiles.rank_label}). */
    public static final int RANK_LABEL_LENGTH = 32;

    /**
     * What a unit did.
     *
     * @param outcome the result
     * @param changes the committed state of every pair the unit touched
     * @param count   how many requests a bulk unit changed (deny all, clear requests), else 0
     */
    public record Result(Outcome outcome, List<FriendGraph.PairChange> changes, int count) {

        static Result of(Outcome outcome, FriendGraph.PairChange change) {
            return new Result(outcome, change == null ? List.of() : List.of(change), 0);
        }
    }

    /**
     * What the sender's side knows when a request starts, computed on the sender's thread.
     *
     * @param targetIgnoresSender the target ignores the sender (the request is hidden)
     * @param sameTeam            they are in the same team (counts as "known" for privacy)
     * @param senderLimit         the sender's friend limit right now
     * @param allowMutual         a request from the target may become a friendship (its event already ran)
     * @param targetOnline        the target is online (an offline target gets a notice for a mutual friendship)
     */
    public record RequestFlags(boolean targetIgnoresSender, boolean sameTeam, int senderLimit, boolean allowMutual,
                               boolean targetOnline) {
    }

    /** One request row for the staff view. */
    public record RequestRow(UUID sender, UUID target, long created, PairState.State state, long decided) {
    }

    /** One friendship row for the staff view. */
    public record FriendRow(UUID friend, long since, boolean favourite) {
    }

    /**
     * A player's friends for the staff view, with the rank limit stored for them ({@code 0} = none stored, the default
     * applies): the limit the write units check while the player is offline.
     */
    public record StaffFriends(List<FriendRow> rows, int storedLimit) {
    }

    /** One history row. */
    public record LogRow(long ts, UUID player, UUID other, String action, String actor) {
    }

    /** What a profile needs from storage. */
    public record ProfileData(String note, String rankLabel, Set<UUID> friends) {
    }

    private final Database database;
    private final LongSupplier clock;
    private final AtomicLong sequence = new AtomicLong();

    public FriendStore(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    public Database database() {
        return this.database;
    }

    /** The last sequence number handed out. */
    public long sequence() {
        return this.sequence.get();
    }

    public <T> CompletableFuture<T> write(SqlWork<T> work) {
        return this.database.write(work);
    }

    // ------------------------------------------------------------------ requests

    /** A friend request from {@code sender} to {@code target}, checked in the order of {@link Decisions#request}. */
    public SqlWork<Result> request(UUID sender, UUID target, FriendRules rules, RequestFlags flags) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            PairState s = readPair(c, sender, target, rules, now, flags.senderLimit(), true, flags.sameTeam());
            Decisions.RequestDecision decision = Decisions.request(s, rules, now, flags.targetIgnoresSender(), flags.allowMutual());
            switch (decision.outcome()) {
                case SENT, SHADOWED -> {
                    if (s.outgoing() != null) {
                        try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE friend_requests SET created = ?, state = ?, decided = ? WHERE sender = ? AND target = ?")) {
                            ps.setLong(1, now);
                            ps.setString(2, decision.state().id());
                            setDecided(ps, 3, decision.decided());
                            ps.setString(4, sender.toString());
                            ps.setString(5, target.toString());
                            ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO friend_requests (sender, target, created, state, decided) VALUES (?, ?, ?, ?, ?)")) {
                            ps.setString(1, sender.toString());
                            ps.setString(2, target.toString());
                            ps.setLong(3, now);
                            ps.setString(4, decision.state().id());
                            setDecided(ps, 5, decision.decided());
                            ps.executeUpdate();
                        }
                    }
                    log(c, now, sender, target, LOG_REQUEST, sender.toString());
                }
                case BECAME_FRIENDS -> {
                    befriend(c, sender, target, now, false, !flags.targetOnline());
                    log(c, now, sender, target, LOG_MUTUAL, sender.toString());
                }
                default -> {
                }
            }
            return Result.of(decision.outcome(), readChange(c, sender, target, seq, 0));
        };
    }

    /** {@code accepter} accepts the request {@code requester} sent them. */
    public SqlWork<Result> accept(UUID accepter, UUID requester, FriendRules rules, int accepterLimit,
                                  boolean requesterOnline, boolean ignored) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            PairState s = readPair(c, accepter, requester, rules, now, accepterLimit, false, false);
            Outcome outcome = Decisions.accept(s, rules, now, ignored);
            if (outcome == Outcome.ALREADY_FRIENDS) {
                deleteRequests(c, accepter, requester);
            } else if (outcome == Outcome.BECAME_FRIENDS) {
                int taken;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_requests WHERE sender = ? AND target = ? "
                    + "AND state = 'pending' AND created >= ?")) {
                    ps.setString(1, requester.toString());
                    ps.setString(2, accepter.toString());
                    ps.setLong(3, rules.expiredBefore(now));
                    taken = ps.executeUpdate();
                }
                if (taken == 0) {
                    outcome = Outcome.GONE;
                } else {
                    befriend(c, accepter, requester, now, false, !requesterOnline);
                    log(c, now, accepter, requester, LOG_ACCEPT, accepter.toString());
                }
            }
            return Result.of(outcome, readChange(c, accepter, requester, seq, 0));
        };
    }

    /** {@code target} denies {@code sender}: the request is hidden from now on and remembered; nobody is told. */
    public SqlWork<Result> deny(UUID target, UUID sender, FriendRules rules) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            Outcome outcome = Decisions.deny(readRequest(c, sender, target), rules, now);
            if (outcome == Outcome.DONE) {
                int changed;
                try (PreparedStatement ps = c.prepareStatement("UPDATE friend_requests SET state = 'shadow', decided = ? "
                    + "WHERE target = ? AND sender = ? AND state = 'pending' AND created >= ?")) {
                    ps.setLong(1, now);
                    ps.setString(2, target.toString());
                    ps.setString(3, sender.toString());
                    ps.setLong(4, rules.expiredBefore(now));
                    changed = ps.executeUpdate();
                }
                if (changed == 0) {
                    outcome = Outcome.GONE;
                } else {
                    log(c, now, target, sender, LOG_DENY, target.toString());
                }
            }
            return Result.of(outcome, readChange(c, target, sender, seq, 0));
        };
    }

    /** {@code target} denies every request they can see, in one unit. */
    public SqlWork<Result> denyAll(UUID target, FriendRules rules) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            List<UUID> senders = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT sender FROM friend_requests WHERE target = ? "
                + "AND state = 'pending' AND created >= ?")) {
                ps.setString(1, target.toString());
                ps.setLong(2, rules.expiredBefore(now));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        senders.add(UUID.fromString(rs.getString(1)));
                    }
                }
            }
            if (senders.isEmpty()) {
                return new Result(Outcome.GONE, List.of(), 0);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE friend_requests SET state = 'shadow', decided = ? "
                + "WHERE target = ? AND state = 'pending' AND created >= ?")) {
                ps.setLong(1, now);
                ps.setString(2, target.toString());
                ps.setLong(3, rules.expiredBefore(now));
                ps.executeUpdate();
            }
            List<FriendGraph.PairChange> changes = new ArrayList<>(senders.size());
            for (UUID sender : senders) {
                log(c, now, target, sender, LOG_DENY, target.toString());
                changes.add(readChange(c, target, sender, seq, 0));
            }
            return new Result(Outcome.DONE, changes, senders.size());
        };
    }

    /**
     * {@code sender} withdraws their request to {@code target}: removed, or closed and kept as deny memory when it
     * had been denied.
     */
    public SqlWork<Result> cancel(UUID sender, UUID target, FriendRules rules) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            Decisions.CancelAction action = Decisions.cancel(readRequest(c, sender, target), rules, now);
            Outcome outcome = Outcome.DONE;
            switch (action) {
                case GONE -> outcome = Outcome.GONE;
                case DELETE -> {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_requests WHERE sender = ? AND target = ?")) {
                        ps.setString(1, sender.toString());
                        ps.setString(2, target.toString());
                        ps.executeUpdate();
                    }
                }
                case CLOSE -> {
                    try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE friend_requests SET state = 'closed' WHERE sender = ? AND target = ?")) {
                        ps.setString(1, sender.toString());
                        ps.setString(2, target.toString());
                        ps.executeUpdate();
                    }
                }
            }
            if (outcome == Outcome.DONE) {
                log(c, now, sender, target, LOG_CANCEL, sender.toString());
            }
            return Result.of(outcome, readChange(c, sender, target, seq, 0));
        };
    }

    // ------------------------------------------------------------------ friendships

    /**
     * Ends the friendship of {@code a} and {@code b} (both directed rows, with notes and favourites).
     *
     * @param actor who did it: a player UUID, {@code console} or a staff UUID
     * @param staff whether staff did it ({@code staff_remove} in the history)
     */
    public SqlWork<Result> remove(UUID a, UUID b, String actor, boolean staff) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            int removed;
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM friends WHERE (owner = ? AND friend = ?) OR (owner = ? AND friend = ?)")) {
                ps.setString(1, a.toString());
                ps.setString(2, b.toString());
                ps.setString(3, b.toString());
                ps.setString(4, a.toString());
                removed = ps.executeUpdate();
            }
            if (removed == 0) {
                return Result.of(Outcome.NOT_FRIENDS, readChange(c, a, b, seq, 0));
            }
            log(c, now, a, b, staff ? LOG_STAFF_REMOVE : LOG_REMOVE, actor);
            return Result.of(Outcome.DONE, readChange(c, a, b, seq, now));
        };
    }

    /** Sets {@code owner}'s favourite flag on {@code friend}, within the favourites cap. */
    public SqlWork<Result> favourite(UUID owner, UUID friend, boolean desired, int cap) {
        return c -> {
            long seq = this.sequence.incrementAndGet();
            Boolean current = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT favourite FROM friends WHERE owner = ? AND friend = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, friend.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        current = rs.getInt(1) != 0;
                    }
                }
            }
            int count = countInt(c, "SELECT COUNT(*) FROM friends WHERE owner = ? AND favourite = 1", owner.toString());
            Outcome outcome = Decisions.favourite(current != null, current != null && current, desired, count, cap);
            if (outcome == Outcome.DONE && current != null && current != desired) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE friends SET favourite = ? WHERE owner = ? AND friend = ?")) {
                    ps.setInt(1, desired ? 1 : 0);
                    ps.setString(2, owner.toString());
                    ps.setString(3, friend.toString());
                    ps.executeUpdate();
                }
            }
            return Result.of(outcome, readChange(c, owner, friend, seq, 0));
        };
    }

    /** Sets (or with null, clears) {@code owner}'s private note on {@code friend}. Notes are not kept in memory. */
    public SqlWork<Result> note(UUID owner, UUID friend, String note) {
        return c -> {
            this.sequence.incrementAndGet();
            int changed;
            try (PreparedStatement ps = c.prepareStatement("UPDATE friends SET note = ? WHERE owner = ? AND friend = ?")) {
                if (note == null || note.isEmpty()) {
                    ps.setNull(1, Types.VARCHAR);
                } else {
                    ps.setString(1, note);
                }
                ps.setString(2, owner.toString());
                ps.setString(3, friend.toString());
                changed = ps.executeUpdate();
            }
            return Result.of(changed == 0 ? Outcome.NOT_FRIENDS : Outcome.DONE, null);
        };
    }

    // ------------------------------------------------------------------ staff

    /**
     * Staff make {@code a} and {@code b} friends: no limits and no privacy, only the hard cap. Their requests in both
     * directions are removed; an offline player finds the friendship in their login summary.
     */
    public SqlWork<Result> staffAdd(UUID a, UUID b, String actor, int hardCap, boolean aOnline, boolean bOnline) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            boolean friends = exists(c, "SELECT 1 FROM friends WHERE owner = ? AND friend = ?", a.toString(), b.toString());
            int aCount = countInt(c, "SELECT COUNT(*) FROM friends WHERE owner = ?", a.toString());
            int bCount = countInt(c, "SELECT COUNT(*) FROM friends WHERE owner = ?", b.toString());
            Outcome outcome = Decisions.staffAdd(friends, aCount, bCount, hardCap);
            if (outcome == Outcome.BECAME_FRIENDS) {
                befriend(c, a, b, now, !aOnline, !bOnline);
                log(c, now, a, b, LOG_STAFF_ADD, actor);
            }
            return Result.of(outcome, readChange(c, a, b, seq, 0));
        };
    }

    /** Staff delete every request {@code player} sent or received, in any state. */
    public SqlWork<Result> clearRequests(UUID player, String actor) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            Set<UUID> others = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT sender, target FROM friend_requests WHERE sender = ? "
                + "UNION ALL SELECT sender, target FROM friend_requests WHERE target = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID sender = UUID.fromString(rs.getString(1));
                        others.add(sender.equals(player) ? UUID.fromString(rs.getString(2)) : sender);
                    }
                }
            }
            int deleted;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_requests WHERE sender = ? OR target = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                deleted = ps.executeUpdate();
            }
            List<FriendGraph.PairChange> changes = new ArrayList<>(others.size());
            for (UUID other : others) {
                log(c, now, player, other, LOG_STAFF_CLEAR, actor);
                changes.add(readChange(c, player, other, seq, 0));
            }
            return new Result(deleted == 0 ? Outcome.GONE : Outcome.DONE, changes, deleted);
        };
    }

    // ------------------------------------------------------------------ profiles, notices, loading

    /** Remembers a player's rank limit (0 = none) and rank label so checks while they are offline are exact. */
    public SqlWork<Void> saveProfile(UUID player, int rankLimit, String rankLabel) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement(this.database.dialect().replaceUpsert("friend_profiles",
                new String[] {"uuid"}, new String[] {"friend_limit", "rank_label", "updated"}))) {
                ps.setString(1, player.toString());
                ps.setInt(2, rankLimit);
                String label = rankLabel == null || rankLabel.isBlank() ? null : rankLabel.strip();
                if (label == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, NoteText.cut(label, RANK_LABEL_LENGTH));
                }
                ps.setLong(4, this.clock.getAsLong());
                ps.executeUpdate();
            }
            return null;
        };
    }

    /**
     * Marks {@code friend} as a friend {@code owner} has not been told about yet (the login summary tells them). Used
     * when the owner went offline between the unit that made them friends and the message.
     */
    public SqlWork<Void> markNotice(UUID owner, UUID friend) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE friends SET notice = 1 WHERE owner = ? AND friend = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, friend.toString());
                ps.executeUpdate();
            }
            return null;
        };
    }

    /**
     * The friends {@code owner} made while away (accepted or added while they were offline), in one unit that also
     * clears them, so each one is told exactly once.
     */
    public SqlWork<List<UUID>> takeNotices(UUID owner) {
        return c -> {
            List<UUID> friends = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT friend FROM friends WHERE owner = ? AND notice = 1 ORDER BY since")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        friends.add(UUID.fromString(rs.getString(1)));
                    }
                }
            }
            if (!friends.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE friends SET notice = 0 WHERE owner = ? AND notice = 1")) {
                    ps.setString(1, owner.toString());
                    ps.executeUpdate();
                }
            }
            return friends;
        };
    }

    /**
     * Reads everything memory keeps about a player, as a write unit so it is ordered with every change and carries
     * a sequence number: friends, visible requests both ways, and friendships removed within {@code rememberMillis}.
     */
    public SqlWork<FriendGraph.Snapshot> load(UUID player, FriendRules rules, long rememberMillis) {
        return c -> {
            long now = this.clock.getAsLong();
            long seq = this.sequence.incrementAndGet();
            return snapshot(c, player, rules, now, rememberMillis, seq);
        };
    }

    /** The same snapshot as {@link #load}, read off the writer (for the self-test). */
    public CompletableFuture<FriendGraph.Snapshot> read(UUID player, FriendRules rules) {
        long now = this.clock.getAsLong();
        return this.database.read(c -> snapshot(c, player, rules, now, 0, 0));
    }

    private static FriendGraph.Snapshot snapshot(Connection c, UUID player, FriendRules rules, long now, long rememberMillis,
                                                 long seq) throws SQLException {
        String id = player.toString();
        Map<UUID, FriendGraph.Edge> friends = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT friend, since, favourite FROM friends WHERE owner = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    friends.put(UUID.fromString(rs.getString(1)), new FriendGraph.Edge(rs.getLong(2), rs.getInt(3) != 0));
                }
            }
        }
        long expired = rules.expiredBefore(now);
        Map<UUID, Long> incoming = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT sender, created FROM friend_requests WHERE target = ? "
            + "AND state = 'pending' AND created >= ?")) {
            ps.setString(1, id);
            ps.setLong(2, expired);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    incoming.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
            }
        }
        Map<UUID, Long> outgoing = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT target, created FROM friend_requests WHERE sender = ? "
            + "AND state IN ('pending', 'shadow') AND created >= ?")) {
            ps.setString(1, id);
            ps.setLong(2, expired);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    outgoing.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
            }
        }
        Map<UUID, Long> tombs = new HashMap<>();
        if (rememberMillis > 0) {
            long since = now - rememberMillis;
            String[] queries = {
                "SELECT other, MAX(ts) FROM friend_log WHERE player = ? AND ts >= ? AND action IN ('remove', 'staff_remove') GROUP BY other",
                "SELECT player, MAX(ts) FROM friend_log WHERE other = ? AND ts >= ? AND action IN ('remove', 'staff_remove') GROUP BY player"};
            for (String sql : queries) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, id);
                    ps.setLong(2, since);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            tombs.merge(UUID.fromString(rs.getString(1)), rs.getLong(2), Math::max);
                        }
                    }
                }
            }
        }
        int storedLimit = -1;
        String storedLabel = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT friend_limit, rank_label FROM friend_profiles WHERE uuid = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    storedLimit = rs.getInt(1);
                    storedLabel = rs.getString(2);
                }
            }
        }
        return new FriendGraph.Snapshot(seq, friends, incoming, outgoing, tombs, storedLimit, storedLabel);
    }

    /**
     * Deletes requests that ran out (keeping denied ones while they are remembered), closed rows past the deny
     * memory, and history older than {@code logKeepMillis}. Returns the number of rows deleted.
     */
    public SqlWork<Integer> sweep(FriendRules rules, long logKeepMillis) {
        return c -> {
            long now = this.clock.getAsLong();
            int deleted = 0;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_requests WHERE state IN ('pending', 'shadow') "
                + "AND created < ? AND (decided IS NULL OR decided < ?)")) {
                ps.setLong(1, rules.expiredBefore(now));
                ps.setLong(2, rules.denyRememberedSince(now));
                deleted += ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_requests WHERE state = 'closed' AND decided < ?")) {
                ps.setLong(1, rules.denyRememberedSince(now));
                deleted += ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friend_log WHERE ts < ?")) {
                ps.setLong(1, now - logKeepMillis);
                deleted += ps.executeUpdate();
            }
            return deleted;
        };
    }

    // ------------------------------------------------------------------ reads (read pool)

    /** The notes {@code owner} keeps on friends, friend to note. */
    public CompletableFuture<Map<UUID, String>> notes(UUID owner) {
        return this.database.read(c -> {
            Map<UUID, String> notes = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT friend, note FROM friends WHERE owner = ? AND note IS NOT NULL")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        notes.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                    }
                }
            }
            return notes;
        });
    }

    /**
     * What a profile shows from storage: the viewer's note on the target, the target's last stored rank label, and
     * (when {@code needFriends}) the target's friends for the mutual line.
     */
    public CompletableFuture<ProfileData> profile(UUID viewer, UUID target, boolean needFriends) {
        return this.database.read(c -> {
            String note = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT note FROM friends WHERE owner = ? AND friend = ?")) {
                ps.setString(1, viewer.toString());
                ps.setString(2, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        note = rs.getString(1);
                    }
                }
            }
            String rank = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT rank_label FROM friend_profiles WHERE uuid = ?")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        rank = rs.getString(1);
                    }
                }
            }
            Set<UUID> friends = needFriends ? friendIds(c, target) : Set.of();
            return new ProfileData(note, rank, friends);
        });
    }

    /** The friends of a player who is not in memory. */
    public CompletableFuture<Set<UUID>> friendsOf(UUID player) {
        return this.database.read(c -> friendIds(c, player));
    }

    private static Set<UUID> friendIds(Connection c, UUID player) throws SQLException {
        Set<UUID> friends = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT friend FROM friends WHERE owner = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    friends.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return friends;
    }

    /** For each of {@code others}, the friends they share with {@code viewer}, in one query. */
    public CompletableFuture<Map<UUID, List<UUID>>> mutual(UUID viewer, Collection<UUID> others) {
        if (others.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<UUID> list = List.copyOf(others);
        return this.database.read(c -> {
            Map<UUID, List<UUID>> mutual = new HashMap<>();
            String marks = String.join(", ", java.util.Collections.nCopies(list.size(), "?"));
            try (PreparedStatement ps = c.prepareStatement("SELECT a.owner, a.friend FROM friends a JOIN friends b "
                + "ON b.owner = ? AND b.friend = a.friend WHERE a.owner IN (" + marks + ")")) {
                ps.setString(1, viewer.toString());
                for (int i = 0; i < list.size(); i++) {
                    ps.setString(i + 2, list.get(i).toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        mutual.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new ArrayList<>())
                            .add(UUID.fromString(rs.getString(2)));
                    }
                }
            }
            return mutual;
        });
    }

    /** A player's friends for the staff view, oldest first, with their stored rank limit. */
    public CompletableFuture<StaffFriends> staffFriends(UUID player) {
        return this.database.read(c -> {
            List<FriendRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT friend, since, favourite FROM friends WHERE owner = ? ORDER BY since")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new FriendRow(UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getInt(3) != 0));
                    }
                }
            }
            return new StaffFriends(rows, storedRankLimit(c, player));
        });
    }

    /** Every request row a player sent or received, in any state (the staff view), newest first. */
    public CompletableFuture<List<RequestRow>> requestRows(UUID player) {
        return this.database.read(c -> {
            List<RequestRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT sender, target, created, state, decided FROM friend_requests "
                + "WHERE sender = ? UNION ALL SELECT sender, target, created, state, decided FROM friend_requests WHERE target = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new RequestRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            rs.getLong(3), PairState.State.parse(rs.getString(4)), rs.getLong(5)));
                    }
                }
            }
            rows.sort((x, y) -> Long.compare(y.created(), x.created()));
            return rows;
        });
    }

    /** A page of a player's history (rows where they are either side), newest first. */
    public CompletableFuture<List<LogRow>> history(UUID player, int limit, int offset) {
        return this.database.read(c -> {
            List<LogRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, player, other, action, actor FROM friend_log "
                + "WHERE player = ? OR other = ? ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                ps.setInt(3, limit);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new LogRow(rs.getLong(1), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                            rs.getString(4), rs.getString(5)));
                    }
                }
            }
            return rows;
        });
    }

    /**
     * Storage invariants for the self-test: friendships missing their other direction, rows of a player with
     * themselves, and request rows between friends. Returns three counts.
     */
    public CompletableFuture<int[]> invariants() {
        return this.database.read(c -> new int[] {
            countInt(c, "SELECT COUNT(*) FROM friends a WHERE NOT EXISTS "
                + "(SELECT 1 FROM friends b WHERE b.owner = a.friend AND b.friend = a.owner)"),
            countInt(c, "SELECT COUNT(*) FROM friends WHERE owner = friend")
                + countInt(c, "SELECT COUNT(*) FROM friend_requests WHERE sender = target"),
            countInt(c, "SELECT COUNT(*) FROM friend_requests r JOIN friends f ON f.owner = r.sender AND f.friend = r.target")});
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Everything the decisions need about a pair, from {@code self}'s side.
     *
     * @param privacy whether to read the other side's privacy and the "known" check (requests only)
     */
    private static PairState readPair(Connection c, UUID self, UUID other, FriendRules rules, long now, int selfLimit,
                                      boolean privacy, boolean sameTeam) throws SQLException {
        String s = self.toString();
        String o = other.toString();
        boolean friends = exists(c, "SELECT 1 FROM friends WHERE owner = ? AND friend = ?", s, o);
        PairState.Request outgoing = readRequest(c, self, other);
        PairState.Request incoming = readRequest(c, other, self);
        int selfFriends = countInt(c, "SELECT COUNT(*) FROM friends WHERE owner = ?", s);
        int otherFriends = countInt(c, "SELECT COUNT(*) FROM friends WHERE owner = ?", o);
        int otherLimit = rules.limit(storedRankLimit(c, other));
        long expired = rules.expiredBefore(now);
        int selfOutgoing = 0;
        int otherIncoming = 0;
        int sentToday = 0;
        Privacy otherPrivacy = Privacy.EVERYONE;
        boolean known = false;
        if (privacy) {
            selfOutgoing = countLong(c, "SELECT COUNT(*) FROM friend_requests WHERE sender = ? AND state IN ('pending', 'shadow') "
                + "AND created >= ?", s, expired);
            otherIncoming = countLong(c, "SELECT COUNT(*) FROM friend_requests WHERE target = ? AND state = 'pending' "
                + "AND created >= ?", o, expired);
            sentToday = countLong(c, "SELECT COUNT(*) FROM friend_log WHERE player = ? AND action = 'request' AND ts >= ?",
                s, now - FriendRules.DAY_MILLIS);
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE uuid = ? AND setting = ?")) {
                ps.setString(1, o);
                ps.setString(2, FriendPrefs.REQUESTS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        otherPrivacy = Privacy.parse(rs.getString(1));
                    }
                }
            }
            if (otherPrivacy == Privacy.KNOWN) {
                known = sameTeam || exists(c, "SELECT 1 FROM friends a JOIN friends b ON b.owner = ? AND b.friend = a.friend "
                    + "WHERE a.owner = ? LIMIT 1", o, s);
            }
        }
        return new PairState(friends, outgoing, incoming, selfFriends, selfLimit, otherFriends, otherLimit, selfOutgoing,
            otherIncoming, sentToday, otherPrivacy, known);
    }

    private static int storedRankLimit(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT friend_limit FROM friend_profiles WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static PairState.Request readRequest(Connection c, UUID sender, UUID target) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT state, created, decided FROM friend_requests WHERE sender = ? AND target = ?")) {
            ps.setString(1, sender.toString());
            ps.setString(2, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PairState.Request(PairState.State.parse(rs.getString(1)), rs.getLong(2), rs.getLong(3));
            }
        }
    }

    /** The committed state of the pair, for memory. */
    private static FriendGraph.PairChange readChange(Connection c, UUID a, UUID b, long seq, long removedAt) throws SQLException {
        Map<String, long[]> rows = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT owner, since, favourite FROM friends "
            + "WHERE (owner = ? AND friend = ?) OR (owner = ? AND friend = ?)")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            ps.setString(3, b.toString());
            ps.setString(4, a.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.put(rs.getString(1), new long[] {rs.getLong(2), rs.getInt(3)});
                }
            }
        }
        FriendGraph.Link link = null;
        if (!rows.isEmpty()) {
            long[] fromA = rows.get(a.toString());
            long[] fromB = rows.get(b.toString());
            long since = fromA != null ? fromA[0] : fromB[0];
            link = new FriendGraph.Link(since, fromA != null && fromA[1] != 0, fromB != null && fromB[1] != 0);
        }
        return new FriendGraph.PairChange(a, b, seq, link, req(readRequest(c, a, b)), req(readRequest(c, b, a)), removedAt);
    }

    private static FriendGraph.Req req(PairState.Request request) {
        return request == null ? null : new FriendGraph.Req(request.state(), request.created());
    }

    /** Inserts both directed rows and removes the pair's requests in every state. */
    private static void befriend(Connection c, UUID a, UUID b, long now, boolean noticeA, boolean noticeB) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO friends (owner, friend, since, favourite, note, notice) VALUES (?, ?, ?, 0, NULL, ?)")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            ps.setLong(3, now);
            ps.setInt(4, noticeA ? 1 : 0);
            ps.addBatch();
            ps.setString(1, b.toString());
            ps.setString(2, a.toString());
            ps.setLong(3, now);
            ps.setInt(4, noticeB ? 1 : 0);
            ps.addBatch();
            ps.executeBatch();
        }
        deleteRequests(c, a, b);
    }

    private static void deleteRequests(Connection c, UUID a, UUID b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM friend_requests WHERE (sender = ? AND target = ?) OR (sender = ? AND target = ?)")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            ps.setString(3, b.toString());
            ps.setString(4, a.toString());
            ps.executeUpdate();
        }
    }

    private static void log(Connection c, long now, UUID player, UUID other, String action, String actor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO friend_log (ts, player, other, action, actor) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, now);
            ps.setString(2, player.toString());
            ps.setString(3, other.toString());
            ps.setString(4, action);
            if (actor == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, actor.length() > 36 ? actor.substring(0, 36) : actor);
            }
            ps.executeUpdate();
        }
    }

    private static void setDecided(PreparedStatement ps, int index, long decided) throws SQLException {
        if (decided > 0) {
            ps.setLong(index, decided);
        } else {
            ps.setNull(index, Types.BIGINT);
        }
    }

    private static boolean exists(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int countInt(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int countLong(Connection c, String sql, String param, long value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.setLong(2, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
