package net.siftvanilla.siftcore.feature.shop;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Every online player's most recent shop purchases, for the "Buy again" row: one row per entry with the amount last
 * bought, loaded when the player joins and updated inside each purchase's transaction (so it is only remembered
 * when the purchase is). Thread-safe: each player's list is an immutable snapshot replaced as a whole.
 */
final class RecentPurchases {

    /**
     * One remembered purchase.
     *
     * @param ref    the entry ({@code category/entry})
     * @param amount how many were bought
     * @param ts     when (epoch millis)
     */
    record Recent(String ref, int amount, long ts) {
    }

    private final Database database;
    private final Logger logger;
    private final Map<UUID, List<Recent>> players = new ConcurrentHashMap<>();

    RecentPurchases(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** The newest purchases first, at most {@link ShopSettings#RECENT}. */
    List<Recent> of(UUID player) {
        return this.players.getOrDefault(player, List.of());
    }

    /** Starts loading a joining player's purchases; purchases made meanwhile are kept. */
    void load(UUID player) {
        this.players.putIfAbsent(player, List.of());
        this.database.read(c -> {
            List<Recent> stored = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT ref, amount, ts FROM shop_recent WHERE uuid = ? ORDER BY ts DESC LIMIT ?")) {
                ps.setString(1, player.toString());
                ps.setInt(2, ShopSettings.RECENT);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stored.add(new Recent(rs.getString(1), rs.getInt(2), rs.getLong(3)));
                    }
                }
            }
            return stored;
        }).whenComplete((stored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not load the recent shop purchases of " + player, error);
                return;
            }
            this.players.computeIfPresent(player, (uuid, current) -> merge(current, stored));
        });
    }

    void forget(UUID player) {
        this.players.remove(player);
    }

    /**
     * Adds a purchase to its transaction: the remembered list changes when the transaction applies (and back if it
     * is not stored), and the row is written with it.
     */
    void contribute(LedgerTx.Builder tx, UUID player, String ref, int amount) {
        long now = System.currentTimeMillis();
        Recent recent = new Recent(ref, amount, now);
        List<List<Recent>> before = new ArrayList<>(1);
        tx.apply(() -> {
            List<Recent> current = this.players.get(player);
            if (current != null) {
                before.add(current);
                this.players.put(player, merge(current, List.of(recent)));
            }
        }, () -> {
            if (!before.isEmpty()) {
                this.players.computeIfPresent(player, (uuid, current) -> before.getFirst());
            }
        });
        String sql = this.database.dialect().replaceUpsert("shop_recent", new String[] {"uuid", "ref"},
            new String[] {"amount", "ts"});
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                ps.setString(2, ref);
                ps.setInt(3, amount);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Both lists together, one entry per ref (the newest), newest first, at most {@link ShopSettings#RECENT}. */
    static List<Recent> merge(List<Recent> a, List<Recent> b) {
        Map<String, Recent> byRef = new LinkedHashMap<>();
        for (List<Recent> list : List.of(a, b)) {
            for (Recent recent : list) {
                byRef.merge(recent.ref(), recent, (x, y) -> x.ts() >= y.ts() ? x : y);
            }
        }
        List<Recent> merged = new ArrayList<>(byRef.values());
        merged.sort(Comparator.comparingLong(Recent::ts).reversed().thenComparing(Recent::ref));
        return List.copyOf(merged.subList(0, Math.min(ShopSettings.RECENT, merged.size())));
    }
}
