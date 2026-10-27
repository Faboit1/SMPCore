package net.siftvanilla.siftcore.feature.homes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Homes of online players, held in memory (loaded before they enter the world) and written through to the
 * {@code homes} table. Every change of one player's homes happens inside one atomic map update that also queues
 * its SQL, so memory and the (ordered) database writer always agree on the order of changes.
 * <p>
 * Loads go through the database writer as well: the writer runs strictly in order, so a load always sees every
 * change queued before it (a player who relogs right after setting a home never loads a stale list).
 */
final class HomeStore {

    /** What setting a home did. */
    enum Outcome {
        /** A new home was added. */
        CREATED,
        /** An existing home with that name was moved. */
        MOVED,
        /** The player is at their limit and the name is new. */
        LIMIT,
        /** The player's homes are not loaded (storage was slow or failed); nothing changed. */
        NOT_LOADED
    }

    /** The result of {@link #set}: the outcome and how many homes the player has afterwards. */
    record SetResult(Outcome outcome, int count) {
    }

    private final Database database;
    private final Map<UUID, Map<String, Home>> loaded = new ConcurrentHashMap<>();

    HomeStore(Database database) {
        this.database = database;
    }

    /** Whether setting a home named like an existing one ({@code exists}) is allowed with {@code count} homes. */
    static Outcome decide(int count, boolean exists, int limit) {
        if (exists) {
            return Outcome.MOVED;
        }
        return count < limit ? Outcome.CREATED : Outcome.LIMIT;
    }

    // ------------------------------------------------------------------ loading

    /** Reads a player's homes from storage (ordered after every queued change). Does not cache them. */
    CompletableFuture<Map<String, Home>> fetch(UUID player) {
        return this.database.write(connection -> read(connection, player));
    }

    /** Keeps a fetched list in memory for an online (or joining) player. */
    void put(UUID player, Map<String, Home> homes) {
        this.loaded.put(player, Collections.unmodifiableMap(new TreeMap<>(homes)));
    }

    /** Drops the lists of players who are no longer online (logins that never completed). */
    void retain(java.util.function.Predicate<UUID> keep) {
        this.loaded.keySet().removeIf(player -> !keep.test(player));
    }

    private static Map<String, Home> read(Connection connection, UUID player) throws SQLException {
        Map<String, Home> homes = new TreeMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT name, world, x, y, z, yaw, pitch, created FROM homes WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Home home = new Home(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                        (float) rs.getDouble(6), (float) rs.getDouble(7), rs.getLong(8));
                    homes.put(home.name(), home);
                }
            }
        }
        return Collections.unmodifiableMap(homes);
    }

    void forget(UUID player) {
        this.loaded.remove(player);
    }

    boolean isLoaded(UUID player) {
        return this.loaded.containsKey(player);
    }

    /** The player's homes by name (sorted), or empty while not loaded. */
    Optional<Map<String, Home>> homes(UUID player) {
        return Optional.ofNullable(this.loaded.get(player));
    }

    Optional<Home> get(UUID player, String name) {
        Map<String, Home> homes = this.loaded.get(player);
        return homes == null ? Optional.empty() : Optional.ofNullable(homes.get(name));
    }

    int count(UUID player) {
        Map<String, Home> homes = this.loaded.get(player);
        return homes == null ? 0 : homes.size();
    }

    int loadedPlayers() {
        return this.loaded.size();
    }

    // ------------------------------------------------------------------ changes

    /** Adds or moves a home of a loaded player, respecting the limit. Thread-safe. */
    SetResult set(UUID player, Home home, int limit) {
        Outcome[] outcome = {Outcome.NOT_LOADED};
        Map<String, Home> after = this.loaded.computeIfPresent(player, (key, homes) -> {
            outcome[0] = decide(homes.size(), homes.containsKey(home.name()), limit);
            if (outcome[0] == Outcome.LIMIT) {
                return homes;
            }
            Map<String, Home> copy = new TreeMap<>(homes);
            copy.put(home.name(), home);
            queueUpsert(player, home);
            return Collections.unmodifiableMap(copy);
        });
        return new SetResult(outcome[0], after == null ? 0 : after.size());
    }

    /**
     * Deletes a home. For a loaded player the in-memory list changes too; for anyone else only storage changes.
     * The future tells whether a row was deleted.
     */
    CompletableFuture<Boolean> delete(UUID player, String name) {
        AtomicReference<CompletableFuture<Boolean>> write = new AtomicReference<>();
        Map<String, Home> after = this.loaded.computeIfPresent(player, (key, homes) -> {
            if (!homes.containsKey(name)) {
                write.set(CompletableFuture.completedFuture(false));
                return homes;
            }
            Map<String, Home> copy = new TreeMap<>(homes);
            copy.remove(name);
            write.set(queueDelete(player, name));
            return Collections.unmodifiableMap(copy);
        });
        if (after == null) {
            return queueDelete(player, name);
        }
        return write.get();
    }

    private void queueUpsert(UUID player, Home home) {
        this.database.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(this.database.dialect().replaceUpsert("homes",
                new String[] {"uuid", "name"}, new String[] {"world", "x", "y", "z", "yaw", "pitch", "created"}))) {
                ps.setString(1, player.toString());
                ps.setString(2, home.name());
                ps.setString(3, home.world());
                ps.setDouble(4, home.x());
                ps.setDouble(5, home.y());
                ps.setDouble(6, home.z());
                ps.setDouble(7, home.yaw());
                ps.setDouble(8, home.pitch());
                ps.setLong(9, home.created());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private CompletableFuture<Boolean> queueDelete(UUID player, String name) {
        return this.database.write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM homes WHERE uuid = ? AND name = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, name);
                return ps.executeUpdate() > 0;
            }
        });
    }
}
