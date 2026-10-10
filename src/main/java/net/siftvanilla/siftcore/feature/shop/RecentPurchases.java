package net.siftvanilla.siftcore.feature.shop;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Every online player's shop purchases: one row per entry with the amount last bought, loaded when the player joins
 * and updated inside each purchase's transaction (so it is only remembered when the purchase is). The newest few make
 * the "Buy again" row; the amount of any entry the player ever bought starts the buy window when they chose "my last
 * amount" ({@code shop-default-amount}). Thread-safe: each player's entries are an immutable snapshot replaced as a
 * whole. At most one row per shop entry is kept per player, so the memory stays bounded by the shop's size.
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
    /** Player to their remembered purchases by entry (immutable snapshots). */
    private final Map<UUID, Map<String, Recent>> players = new ConcurrentHashMap<>();

    RecentPurchases(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** The newest purchases first, at most {@link ShopSettings#RECENT}. */
    List<Recent> of(UUID player) {
        return newest(this.players.getOrDefault(player, Map.of()).values());
    }

    /** The amount the player last bought of an entry, or 0 when they never bought it (or it is not loaded yet). */
    int lastAmount(UUID player, String ref) {
        Recent recent = this.players.getOrDefault(player, Map.of()).get(ref);
        return recent == null ? 0 : recent.amount();
    }

    /** Starts loading a joining player's purchases; purchases made meanwhile are kept. */
    void load(UUID player) {
        this.players.putIfAbsent(player, Map.of());
        this.database.read(c -> {
            List<Recent> stored = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ref, amount, ts FROM shop_recent WHERE uuid = ?")) {
                ps.setString(1, player.toString());
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
            this.players.computeIfPresent(player, (uuid, current) -> with(current, stored));
        });
    }

    void forget(UUID player) {
        this.players.remove(player);
    }

    /**
     * Adds a purchase to its transaction: the remembered entries change when the transaction applies (and back if it
     * is not stored), and the row is written with it.
     */
    void contribute(LedgerTx.Builder tx, UUID player, String ref, int amount) {
        long now = System.currentTimeMillis();
        Recent recent = new Recent(ref, amount, now);
        List<Map<String, Recent>> before = new ArrayList<>(1);
        tx.apply(() -> {
            Map<String, Recent> current = this.players.get(player);
            if (current != null) {
                before.add(current);
                this.players.put(player, with(current, List.of(recent)));
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

    /** The remembered entries with {@code added} merged in: one per entry, the newest of each. */
    static Map<String, Recent> with(Map<String, Recent> current, Collection<Recent> added) {
        Map<String, Recent> byRef = new HashMap<>(current);
        for (Recent recent : added) {
            byRef.merge(recent.ref(), recent, (x, y) -> x.ts() >= y.ts() ? x : y);
        }
        return Map.copyOf(byRef);
    }

    /** The newest purchases first (ties by entry), at most {@link ShopSettings#RECENT}. */
    static List<Recent> newest(Collection<Recent> recents) {
        List<Recent> sorted = new ArrayList<>(recents);
        sorted.sort(Comparator.comparingLong(Recent::ts).reversed().thenComparing(Recent::ref));
        return List.copyOf(sorted.subList(0, Math.min(ShopSettings.RECENT, sorted.size())));
    }

    /** Both lists together, one entry per ref (the newest), newest first, at most {@link ShopSettings#RECENT}. */
    static List<Recent> merge(List<Recent> a, List<Recent> b) {
        return newest(with(with(Map.of(), a), b).values());
    }
}
