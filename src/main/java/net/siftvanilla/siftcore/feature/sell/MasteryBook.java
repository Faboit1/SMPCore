package net.siftvanilla.siftcore.feature.sell;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.Dialect;

/**
 * What every online player sold per sell category (the base value behind their mastery levels), kept in memory
 * and changed only inside sale transactions, so memory always matches storage.
 * <p>
 * A player's rows are loaded when they join. The load is queued on the database writer, which runs work strictly
 * in order: every sale write queued before it (an earlier session that was still being stored) is in what it reads,
 * and every sale of this session is queued after it and added on top in memory. So sales made while the load is
 * still running are neither lost nor counted twice.
 */
final class MasteryBook {

    /** One player's totals: what storage had plus what this session added. */
    private static final class State {
        private final Map<String, Long> sold = new HashMap<>();
        private boolean loaded;

        synchronized long get(String category) {
            return this.sold.getOrDefault(category, 0L);
        }

        synchronized Map<String, Long> snapshot() {
            return Map.copyOf(this.sold);
        }

        synchronized boolean loaded() {
            return this.loaded;
        }

        synchronized void add(Map<String, Long> credits, int sign) {
            credits.forEach((category, amount) -> {
                long next = this.sold.getOrDefault(category, 0L) + sign * amount;
                if (next <= 0) {
                    this.sold.remove(category);
                } else {
                    this.sold.put(category, next);
                }
            });
        }

        synchronized void merge(Map<String, Long> stored) {
            stored.forEach((category, amount) -> this.sold.merge(category, amount, Long::sum));
            this.loaded = true;
        }

        synchronized void clear() {
            this.sold.clear();
        }

        synchronized void set(String category, long value) {
            if (value <= 0) {
                this.sold.remove(category);
            } else {
                this.sold.put(category, value);
            }
        }
    }

    private final Database database;
    private final Logger logger;
    private final Map<UUID, State> players = new ConcurrentHashMap<>();

    MasteryBook(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /**
     * Starts loading a player's totals (their sales count from now on). Completes once they are in memory (also when
     * reading failed: the player then starts from what this session adds, and the failure is logged).
     */
    CompletableFuture<Void> load(UUID player) {
        State state = new State();
        this.players.put(player, state);
        return read(player).handle((stored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not load the sell mastery of " + player, error);
            }
            if (this.players.get(player) == state) {
                state.merge(error != null ? Map.of() : stored);
            }
            return null;
        });
    }

    /** True while a player's totals are in memory or being loaded. */
    boolean present(UUID player) {
        return this.players.containsKey(player);
    }

    /** Forgets a player who left. */
    void forget(UUID player) {
        this.players.remove(player);
    }

    /** True once a player's stored totals are in memory. */
    boolean loaded(UUID player) {
        State state = this.players.get(player);
        return state != null && state.loaded();
    }

    /** Base value a player sold in a category (0 when unknown or offline). Any thread. */
    long sold(UUID player, String category) {
        State state = this.players.get(player);
        return state == null ? 0 : state.get(category);
    }

    /** Every category's total of an online player. Any thread. */
    Map<String, Long> totals(UUID player) {
        State state = this.players.get(player);
        return state == null ? Map.of() : state.snapshot();
    }

    /** The sum over every category of an online player. */
    long total(UUID player) {
        long sum = 0;
        for (long value : totals(player).values()) {
            sum = Math.addExact(sum, value);
        }
        return sum;
    }

    /**
     * Adds a sale's credits to its transaction: the in-memory change (and its undo) and the additive upsert of each
     * category, so they commit or fail with the money.
     */
    void contribute(LedgerTx.Builder tx, UUID player, Map<String, Long> credits) {
        Map<String, Long> positive = new LinkedHashMap<>();
        credits.forEach((category, amount) -> {
            if (amount > 0) {
                positive.put(category, amount);
            }
        });
        if (positive.isEmpty()) {
            return;
        }
        Map<String, Long> copy = Map.copyOf(positive);
        // The undo takes back exactly what the apply added, from the same session's totals: if the player left and
        // came back before a failed write was undone, the new session loaded storage without it and stays as it is.
        State[] applied = new State[1];
        tx.apply(() -> {
            State state = this.players.get(player);
            if (state != null) {
                state.add(copy, 1);
                applied[0] = state;
            }
        }, () -> {
            State state = applied[0];
            if (state != null) {
                state.add(copy, -1);
                applied[0] = null;
            }
        });
        long now = System.currentTimeMillis();
        Dialect dialect = this.database.dialect();
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(addSql(dialect))) {
                for (Map.Entry<String, Long> entry : copy.entrySet()) {
                    ps.setString(1, player.toString());
                    ps.setString(2, entry.getKey());
                    ps.setLong(3, entry.getValue());
                    ps.setLong(4, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /**
     * Sets a category's total (staff tools): the in-memory change and the row as one domain transaction.
     *
     * @param value the new total, 0 removes the row
     */
    LedgerTx setTotal(UUID player, String category, long value) {
        long now = System.currentTimeMillis();
        long[] before = new long[1];
        State[] applied = new State[1];
        return LedgerTx.builder()
            .actor("system")
            .silent()
            .apply(() -> {
                State state = this.players.get(player);
                if (state != null) {
                    before[0] = state.get(category);
                    state.set(category, value);
                    applied[0] = state;
                }
            }, () -> {
                State state = applied[0];
                if (state != null) {
                    state.set(category, before[0]);
                    applied[0] = null;
                }
            })
            .write(c -> {
                if (value <= 0) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM sell_mastery WHERE uuid = ? AND category = ?")) {
                        ps.setString(1, player.toString());
                        ps.setString(2, category);
                        ps.executeUpdate();
                    }
                    return null;
                }
                try (PreparedStatement ps = c.prepareStatement(this.database.dialect().replaceUpsert("sell_mastery",
                    new String[] {"uuid", "category"}, new String[] {"sold", "updated"}))) {
                    ps.setString(1, player.toString());
                    ps.setString(2, category);
                    ps.setLong(3, value);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
                return null;
            })
            .build();
    }

    /**
     * Resets every category of a player (staff tools): the in-memory totals and the rows as one domain transaction.
     * Rows of categories that are no longer configured go too.
     */
    LedgerTx resetAll(UUID player) {
        Map<String, Long> before = new HashMap<>();
        State[] applied = new State[1];
        return LedgerTx.builder()
            .actor("system")
            .silent()
            .apply(() -> {
                State state = this.players.get(player);
                if (state != null) {
                    before.putAll(state.snapshot());
                    state.clear();
                    applied[0] = state;
                }
            }, () -> {
                State state = applied[0];
                if (state != null) {
                    before.forEach(state::set);
                    applied[0] = null;
                }
            })
            .write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM sell_mastery WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                return null;
            })
            .build();
    }

    /**
     * Reads a player's stored totals on the database writer, in order with the sales queued before it. Used for the
     * join load and for offline players in staff tools.
     */
    CompletableFuture<Map<String, Long>> read(UUID player) {
        return this.database.write(c -> {
            Map<String, Long> stored = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT category, sold FROM sell_mastery WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stored.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return stored;
        });
    }

    /** The additive upsert: parameters uuid, category, amount, now. */
    static String addSql(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> "INSERT INTO sell_mastery (uuid, category, sold, updated) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(uuid, category) DO UPDATE SET sold = sold + excluded.sold, updated = excluded.updated";
            case MYSQL -> "INSERT INTO sell_mastery (uuid, category, sold, updated) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE sold = sold + VALUES(sold), updated = VALUES(updated)";
        };
    }
}
