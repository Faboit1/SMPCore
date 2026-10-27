package net.siftvanilla.siftcore.feature.spawners;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * XP that left a spawner (collected, picked up, refunded) but has not reached its player yet, like a claim box for XP.
 * It is credited here in the same transaction that takes it out of the spawner, so it is stored with that change, and
 * then {@linkplain #take taken} again to be paid out on the player's thread: right away when they are online, otherwise
 * when they next join. If that payout can't happen after all (they left, the server stopped), it is credited back.
 * <p>
 * Amounts live in memory (loaded at startup) and change only inside transactions, under the economy lock. Storage is
 * changed by deltas in the same database unit, so a transaction that fails to store rolls back exactly its own part.
 */
final class XpBox {

    /** What {@link #take} took (0 when the transaction did not go ahead). */
    record Taken(TransactionResult result, long xp) {
    }

    private static final String NOTHING = "nothing_waiting";

    private final Ledger ledger;
    private final Database database;
    private final Logger logger;
    private final Map<UUID, Long> waiting = new ConcurrentHashMap<>();
    private final String addSql;

    XpBox(Ledger ledger, Database database, Logger logger) {
        this.ledger = ledger;
        this.database = database;
        this.logger = logger;
        this.addSql = database.dialect().addUpsert("spawner_xp", new String[] {"uuid"}, "xp");
    }

    /** Loads what is waiting and removes rows that are empty. Blocking; call at startup only. */
    void load() throws Exception {
        this.database.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM spawner_xp WHERE xp <= 0");
            }
            return null;
        }).get();
        Map<UUID, Long> rows = this.database.read(c -> {
            Map<UUID, Long> map = new HashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, xp FROM spawner_xp WHERE xp > 0")) {
                while (rs.next()) {
                    try {
                        map.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                    } catch (IllegalArgumentException e) {
                        this.logger.severe("Spawner XP waiting for '" + rs.getString(1) + "' has an unreadable owner and was skipped");
                    }
                }
            }
            return map;
        }).get();
        this.waiting.clear();
        this.waiting.putAll(rows);
    }

    /** XP waiting for a player. */
    long waiting(UUID player) {
        return this.waiting.getOrDefault(player, 0L);
    }

    /** XP waiting for every player together. */
    long total() {
        long total = 0;
        for (long xp : this.waiting.values()) {
            total = saturatingAdd(total, xp);
        }
        return total;
    }

    /** Players with XP waiting. */
    int players() {
        return this.waiting.size();
    }

    /** Adds {@code xp} for a player to a transaction: it is waiting once the transaction applies, stored with it. */
    void credit(LedgerTx.Builder tx, UUID player, long xp) {
        if (xp <= 0) {
            return;
        }
        tx.apply(() -> this.waiting.merge(player, xp, XpBox::saturatingAdd), () -> subtract(player, xp));
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(this.addSql)) {
                ps.setString(1, player.toString());
                ps.setLong(2, xp);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Credits XP in a transaction of its own (XP that could not be paid out after all). If even that can't be stored,
     * logs it so staff can give it by hand. Null when there was nothing to credit or the transaction threw.
     */
    TransactionResult credit(UUID player, long xp, String why) {
        if (xp <= 0) {
            return null;
        }
        LedgerTx.Builder tx = LedgerTx.builder().actor("system").silent().note("spawner xp waiting: " + why);
        credit(tx, player, xp);
        TransactionResult result;
        try {
            result = this.ledger.executeDomain(tx.build());
        } catch (RuntimeException e) {
            lost(player, xp, why, e);
            return null;
        }
        if (!result.success()) {
            lost(player, xp, why, new IllegalStateException(result.status() + " " + result.reason()));
            return result;
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                lost(player, xp, why, error);
            }
        });
        return result;
    }

    /**
     * Takes everything waiting for a player in one transaction, to pay it out. The XP is out of the box once this
     * returns a success; credit it back if it can't be given after all.
     */
    Taken take(UUID player) {
        long[] taken = new long[1];
        LedgerTx tx = LedgerTx.builder().actor("system").silent().note("pay out spawner xp")
            .check(() -> waiting(player) > 0 ? null : NOTHING)
            .apply(() -> {
                Long removed = this.waiting.remove(player);
                taken[0] = removed == null ? 0 : removed;
            }, () -> {
                if (taken[0] > 0) {
                    this.waiting.merge(player, taken[0], XpBox::saturatingAdd);
                }
            })
            .write(c -> {
                try (PreparedStatement ps = c.prepareStatement(this.addSql)) {
                    ps.setString(1, player.toString());
                    ps.setLong(2, -taken[0]);
                    ps.executeUpdate();
                }
                return null;
            })
            .build();
        TransactionResult result = this.ledger.executeDomain(tx);
        return new Taken(result, result.success() ? taken[0] : 0);
    }

    private void subtract(UUID player, long xp) {
        this.waiting.computeIfPresent(player, (k, now) -> now - xp <= 0 ? null : now - xp);
    }

    private void lost(UUID player, long xp, String why, Throwable error) {
        this.logger.log(Level.SEVERE, xp + " spawner XP for " + player + " could not be stored (" + why + "). Give it by hand: /xp add "
            + player + " " + xp, error);
    }

    static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
