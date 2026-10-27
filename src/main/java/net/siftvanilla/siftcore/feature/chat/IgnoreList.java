package net.siftvanilla.siftcore.feature.chat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Every ignore list (table {@code ignores}), held in memory so chat, private messages, teleport requests and friend
 * requests can ask from any thread without touching the database. Loaded once at startup (the table is small:
 * a row per ignored player, capped per player) and written through on every change, in order, by the database's
 * writer thread.
 * <p>
 * Each player's list is an immutable set replaced atomically, so readers never see a half-made change and two
 * changes to one list never lose each other.
 */
final class IgnoreList implements IgnoreLookup {

    /** The outcome of a change. */
    enum Change {
        ADDED,
        REMOVED,
        /** Nothing to do: already ignored (add) or not ignored (remove). */
        UNCHANGED,
        /** The list is at its limit. */
        FULL
    }

    private final Database database;
    private final Logger logger;
    private final Map<UUID, Set<UUID>> lists = new ConcurrentHashMap<>();

    IgnoreList(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** Loads every list; blocks, so call it at startup only. */
    void load() throws Exception {
        Map<UUID, Set<UUID>> loaded = this.database.read(c -> {
            Map<UUID, Set<UUID>> map = new HashMap<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, ignored FROM ignores")) {
                while (rs.next()) {
                    UUID player = parse(rs.getString(1));
                    UUID ignored = parse(rs.getString(2));
                    if (player != null && ignored != null && !player.equals(ignored)) {
                        map.computeIfAbsent(player, k -> new HashSet<>()).add(ignored);
                    }
                }
            }
            return map;
        }).get();
        this.lists.clear();
        loaded.forEach((player, set) -> this.lists.put(player, Set.copyOf(set)));
    }

    private static UUID parse(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean ignores(UUID player, UUID other) {
        Set<UUID> set = this.lists.get(player);
        return set != null && set.contains(other);
    }

    /** The players {@code player} ignores (an immutable snapshot). */
    Set<UUID> ignored(UUID player) {
        return this.lists.getOrDefault(player, Set.of());
    }

    int count(UUID player) {
        return ignored(player).size();
    }

    /** How many ignore entries exist in total. */
    int total() {
        int total = 0;
        for (Set<UUID> set : this.lists.values()) {
            total += set.size();
        }
        return total;
    }

    /** Starts ignoring {@code other}, unless the list already holds {@code max} players. */
    Change add(UUID player, UUID other, int max) {
        Change[] change = {Change.UNCHANGED};
        this.lists.compute(player, (k, set) -> {
            Set<UUID> current = set == null ? Set.of() : set;
            if (current.contains(other)) {
                return set;
            }
            if (current.size() >= max) {
                change[0] = Change.FULL;
                return set;
            }
            Set<UUID> next = new HashSet<>(current);
            next.add(other);
            change[0] = Change.ADDED;
            return Set.copyOf(next);
        });
        if (change[0] == Change.ADDED) {
            watch(this.database.write(c -> {
                try (PreparedStatement ps = c.prepareStatement(this.database.dialect().insertIgnore("ignores",
                    new String[] {"uuid", "ignored"}))) {
                    ps.setString(1, player.toString());
                    ps.setString(2, other.toString());
                    ps.executeUpdate();
                }
                return null;
            }), "save that " + player + " ignores " + other);
        }
        return change[0];
    }

    /** Stops ignoring {@code other}. */
    Change remove(UUID player, UUID other) {
        Change[] change = {Change.UNCHANGED};
        this.lists.computeIfPresent(player, (k, set) -> {
            if (!set.contains(other)) {
                return set;
            }
            Set<UUID> next = new HashSet<>(set);
            next.remove(other);
            change[0] = Change.REMOVED;
            return next.isEmpty() ? null : Set.copyOf(next);
        });
        if (change[0] == Change.REMOVED) {
            watch(this.database.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ignores WHERE uuid = ? AND ignored = ?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, other.toString());
                    ps.executeUpdate();
                }
                return null;
            }), "save that " + player + " stopped ignoring " + other);
        }
        return change[0];
    }

    /** Counts the rows in the table (self-test: the table is readable). */
    CompletableFuture<Integer> countRows() {
        return this.database.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ignores")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private void watch(CompletableFuture<?> write, String what) {
        write.whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.SEVERE, "Could not " + what + "; the change is kept in memory until a restart", error);
            }
        });
    }
}
