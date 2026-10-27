package net.siftvanilla.siftcore.feature.kits;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Kit claim times: loaded at startup, held in a {@link ClaimBook} and changed only inside economy transactions, so
 * the cooldown check, the new claim time, its row in {@code kit_claims} and the kit's items in the claim box are one
 * atomic unit. Two claims at the same moment can never both pass the check.
 */
final class KitClaims {

    /** Failure reason when the kit is not ready (cooldown running or a once-only kit already claimed). */
    static final String NOT_READY = "not_ready";
    /** Failure reason of a reset that found nothing to reset. */
    static final String NOTHING = "nothing";

    private final Ledger ledger;
    private final Database database;
    private final LongSupplier clock;
    private final ClaimBook book = new ClaimBook();
    private final String upsert;

    /** @param clock current time in epoch milliseconds */
    KitClaims(Ledger ledger, Database database, LongSupplier clock) {
        this.ledger = ledger;
        this.database = database;
        this.clock = clock;
        this.upsert = database.dialect().replaceUpsert("kit_claims", new String[] {"uuid", "kit"}, new String[] {"last_claim"});
    }

    ClaimBook book() {
        return this.book;
    }

    long now() {
        return this.clock.getAsLong();
    }

    /** Loads every claim. Blocking; call at startup only. */
    void load() throws Exception {
        Map<UUID, Map<String, Long>> loaded = this.database.read(c -> {
            Map<UUID, Map<String, Long>> rows = new HashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, kit, last_claim FROM kit_claims")) {
                while (rs.next()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    rows.computeIfAbsent(uuid, k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                }
            }
            return rows;
        }).get();
        this.book.load(loaded);
    }

    /** The player's status with a kit right now. */
    KitStatus status(UUID player, Kit kit) {
        return kit.cooldown().status(this.book.last(player, kit.id()), now());
    }

    /**
     * Adds a claim to a transaction: refused with {@link #NOT_READY} unless the kit is ready under the economy lock;
     * otherwise the claim time is set in memory and stored with the transaction.
     */
    void claim(LedgerTx.Builder tx, UUID player, Kit kit) {
        String id = kit.id();
        Cooldown cooldown = kit.cooldown();
        long[] at = new long[1];
        Long[] previous = new Long[1];
        tx.check(() -> {
            at[0] = now();
            return cooldown.status(this.book.last(player, id), at[0]).ready() ? null : NOT_READY;
        });
        tx.apply(() -> previous[0] = this.book.set(player, id, at[0]), () -> this.book.restore(player, id, previous[0]));
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(this.upsert)) {
                ps.setString(1, player.toString());
                ps.setString(2, id);
                ps.setLong(3, at[0]);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Forgets claims so the kits can be claimed again: one kit, or every kit when {@code kit} is null. Refused with
     * {@link #NOTHING} when there is nothing to forget.
     */
    TransactionResult reset(UUID player, String kit, String actor) {
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor).silent().note(kit == null ? "kits reset" : "kits reset " + kit);
        if (kit == null) {
            Map<String, Long> removed = new HashMap<>();
            tx.check(() -> this.book.of(player).isEmpty() ? NOTHING : null);
            tx.apply(() -> {
                removed.clear();
                removed.putAll(this.book.removeAll(player));
            }, () -> this.book.restoreAll(player, removed));
            tx.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM kit_claims WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                return null;
            });
        } else {
            Long[] previous = new Long[1];
            tx.check(() -> this.book.last(player, kit) == null ? NOTHING : null);
            tx.apply(() -> previous[0] = this.book.remove(player, kit), () -> this.book.restore(player, kit, previous[0]));
            tx.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM kit_claims WHERE uuid = ? AND kit = ?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, kit);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        return this.ledger.execute(tx.build());
    }

    /**
     * Compares memory with storage exactly: the memory snapshot is taken under the economy lock and the storage read
     * is queued on the ordered writer in the same moment, so it sees precisely the transactions applied before it.
     * Completes with null when both agree, otherwise with what differs.
     */
    CompletableFuture<String> verify() {
        return this.ledger.locked(() -> {
            Map<UUID, Map<String, Long>> memory = this.book.snapshot();
            return this.database.write(c -> {
                Map<UUID, Map<String, Long>> stored = new HashMap<>();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, kit, last_claim FROM kit_claims")) {
                    while (rs.next()) {
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(rs.getString(1));
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        stored.computeIfAbsent(uuid, k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                    }
                }
                if (stored.equals(memory)) {
                    return null;
                }
                Set<UUID> players = new HashSet<>(stored.keySet());
                players.addAll(memory.keySet());
                int differing = 0;
                for (UUID uuid : players) {
                    if (!Objects.equals(stored.get(uuid), memory.get(uuid))) {
                        differing++;
                    }
                }
                return differing + " player(s) have different kit claims in memory and in storage";
            });
        });
    }
}
