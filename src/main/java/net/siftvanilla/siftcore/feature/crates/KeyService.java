package net.siftvanilla.siftcore.feature.crates;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Virtual crate keys: the {@link CrateKeys} contract other features use (the shard shop, store delivery, keyall),
 * plus the building blocks the crate opening transaction is made of.
 * <p>
 * Keys live in memory ({@link KeyBook}) and change only inside economy transactions, so a key spent to open a crate
 * and the reward it pays out are one atomic unit. Storage rows are changed by deltas in transaction order, which
 * keeps them right even when an earlier transaction is rolled back after a storage failure. A grant with a
 * reference is applied at most once: the reference is checked under the economy lock and stored with the grant.
 */
final class KeyService implements CrateKeys {

    /** Failure reasons of {@link #give} and {@link #take}. */
    static final String UNKNOWN_CRATE = "unknown_crate";
    static final String BAD_AMOUNT = "bad_amount";
    static final String BAD_REF = "bad_ref";
    static final String DUPLICATE = "duplicate";
    static final String LIMIT = "limit";
    static final String NOT_ENOUGH = "not_enough";
    static final String NO_KEYS = "no_keys";

    private final Ledger ledger;
    private final Database database;
    private final Supplier<Set<String>> crates;
    private final LongSupplier clock;
    private final BiFunction<String, Long, Component> text;
    private final KeyBook book = new KeyBook();
    private final String upsert;

    /**
     * @param crates the ids of the configured crates (follows reloads)
     * @param clock  current time in epoch milliseconds
     */
    KeyService(Ledger ledger, Database database, Supplier<Set<String>> crates, LongSupplier clock) {
        this(ledger, database, crates, clock, null);
    }

    /** @param text how an amount of a crate's keys reads ({@link #keysText}), or null for the plain default */
    KeyService(Ledger ledger, Database database, Supplier<Set<String>> crates, LongSupplier clock,
               BiFunction<String, Long, Component> text) {
        this.ledger = ledger;
        this.database = database;
        this.crates = crates;
        this.clock = clock;
        this.text = text;
        this.upsert = database.dialect().addUpsert("crate_keys", new String[] {"uuid", "crate"}, "amount");
    }

    KeyBook book() {
        return this.book;
    }

    /**
     * Loads every key and the grant references younger than {@code remember}; older references are deleted first.
     * Blocking; call at startup only.
     */
    void load(Duration remember) throws Exception {
        long cutoff = this.clock.getAsLong() - remember.toMillis();
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM crate_grants WHERE ts < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        }).get();
        Map<UUID, Map<String, Integer>> keys = new HashMap<>();
        Map<String, Long> refs = new HashMap<>();
        this.database.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, crate, amount FROM crate_keys")) {
                while (rs.next()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    int amount = rs.getInt(3);
                    if (amount > 0) {
                        keys.computeIfAbsent(uuid, k -> new HashMap<>()).put(rs.getString(2), amount);
                    }
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT ref, ts FROM crate_grants")) {
                while (rs.next()) {
                    refs.put(rs.getString(1), rs.getLong(2));
                }
            }
            return null;
        }).get();
        this.book.load(keys, refs);
    }

    /**
     * Forgets grant references older than {@code remember}, in memory and in storage. The delete is queued while the
     * economy lock is held, so a later grant that reuses a forgotten reference is always stored after it.
     */
    int prune(Duration remember) {
        long cutoff = this.clock.getAsLong() - remember.toMillis();
        return this.ledger.locked(() -> {
            int removed = this.book.forgetOlderThan(cutoff);
            if (removed > 0) {
                this.database.write(c -> {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM crate_grants WHERE ts < ?")) {
                        ps.setLong(1, cutoff);
                        return ps.executeUpdate();
                    }
                });
            }
            return removed;
        });
    }

    // ------------------------------------------------------------------ CrateKeys

    @Override
    public Set<String> crates() {
        return this.crates.get();
    }

    @Override
    public int keys(UUID player, String crate) {
        return this.book.get(player, crate);
    }

    @Override
    public Component keysText(String crate, long amount) {
        return this.text == null ? CrateKeys.super.keysText(crate, amount) : this.text.apply(crate, amount);
    }

    /** A player's keys by crate (only crates with keys). */
    Map<String, Integer> keysOf(UUID player) {
        return this.book.of(player);
    }

    @Override
    public TransactionResult give(UUID player, String crate, int amount, String actor, String ref) {
        String problem = validate(crate, amount, ref);
        if (problem != null) {
            return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.REJECTED, problem);
        }
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor == null ? "system" : actor).silent()
            .note("keys " + crate + " +" + amount);
        grant(tx, player, crate, amount, ref, actor);
        return this.ledger.execute(tx.build());
    }

    /** Takes keys away (staff). Refused with {@link #NOT_ENOUGH} when the player has fewer. */
    TransactionResult take(UUID player, String crate, int amount, String actor) {
        if (amount <= 0) {
            return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.REJECTED, BAD_AMOUNT);
        }
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor == null ? "system" : actor).silent()
            .note("keys " + crate + " -" + amount);
        spend(tx, player, crate, amount, NOT_ENOUGH);
        return this.ledger.execute(tx.build());
    }

    private String validate(String crate, int amount, String ref) {
        if (crate == null || !this.crates.get().contains(crate)) {
            return UNKNOWN_CRATE;
        }
        if (amount <= 0 || amount > KeyBook.MAX_KEYS) {
            return BAD_AMOUNT;
        }
        if (ref != null && (ref.isBlank() || ref.length() > 64)) {
            return BAD_REF;
        }
        return null;
    }

    // ------------------------------------------------------------------ transaction parts

    /**
     * Adds a grant to a transaction: refused with {@link #DUPLICATE} when {@code ref} was applied before and with
     * {@link #LIMIT} above {@link KeyBook#MAX_KEYS}; otherwise the keys (and the reference) are applied and stored
     * with it.
     */
    void grant(LedgerTx.Builder tx, UUID player, String crate, int amount, String ref, String actor) {
        long now = this.clock.getAsLong();
        String storedActor = actor == null ? null : actor.length() > 36 ? actor.substring(0, 36) : actor;
        if (ref != null) {
            tx.check(() -> this.book.applied(ref) ? DUPLICATE : null);
        }
        tx.check(() -> (long) this.book.get(player, crate) + amount > KeyBook.MAX_KEYS ? LIMIT : null);
        tx.apply(() -> {
            this.book.add(player, crate, amount);
            if (ref != null) {
                this.book.remember(ref, now);
            }
        }, () -> {
            if (ref != null) {
                this.book.forget(ref);
            }
            this.book.add(player, crate, -amount);
        });
        tx.write(c -> {
            change(c, player, crate, amount);
            if (ref != null) {
                try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO crate_grants (ref, uuid, crate, amount, actor, ts) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, ref);
                    ps.setString(2, player.toString());
                    ps.setString(3, crate);
                    ps.setInt(4, amount);
                    if (storedActor == null) {
                        ps.setNull(5, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(5, storedActor);
                    }
                    ps.setLong(6, now);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Adds spending keys to a transaction; refused with {@code reason} when the player has fewer than {@code amount}. */
    void spend(LedgerTx.Builder tx, UUID player, String crate, int amount, String reason) {
        tx.check(() -> this.book.get(player, crate) >= amount ? null : reason);
        tx.apply(() -> this.book.add(player, crate, -amount), () -> this.book.add(player, crate, amount));
        tx.write(c -> {
            change(c, player, crate, -amount);
            return null;
        });
    }

    private void change(java.sql.Connection c, UUID player, String crate, int delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.upsert)) {
            ps.setString(1, player.toString());
            ps.setString(2, crate);
            ps.setInt(3, delta);
            ps.executeUpdate();
        }
        if (delta < 0) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM crate_keys WHERE uuid = ? AND crate = ? AND amount <= 0")) {
                ps.setString(1, player.toString());
                ps.setString(2, crate);
                ps.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------ checks

    /**
     * Compares memory with storage exactly: the memory snapshot is taken under the economy lock and the storage read
     * is queued on the ordered writer in the same moment, so it sees precisely the transactions applied before it.
     * Completes with null when both agree, otherwise with what differs.
     */
    CompletableFuture<String> verify() {
        return this.ledger.locked(() -> {
            Map<UUID, Map<String, Integer>> memory = this.book.snapshot();
            int refs = this.book.refCount();
            return this.database.write(c -> {
                Map<UUID, Map<String, Integer>> stored = new HashMap<>();
                int bad = 0;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, crate, amount FROM crate_keys")) {
                    while (rs.next()) {
                        int amount = rs.getInt(3);
                        if (amount <= 0) {
                            bad++;
                            continue;
                        }
                        stored.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new HashMap<>()).put(rs.getString(2), amount);
                    }
                }
                int storedRefs;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM crate_grants")) {
                    storedRefs = rs.next() ? rs.getInt(1) : 0;
                }
                if (bad > 0) {
                    return bad + " stored key row(s) are zero or negative";
                }
                if (!stored.equals(memory)) {
                    int differing = 0;
                    Set<UUID> players = new java.util.HashSet<>(stored.keySet());
                    players.addAll(memory.keySet());
                    for (UUID uuid : players) {
                        if (!java.util.Objects.equals(stored.get(uuid), memory.get(uuid))) {
                            differing++;
                        }
                    }
                    return differing + " player(s) have different keys in memory and in storage";
                }
                if (storedRefs != refs) {
                    return storedRefs + " grant references are stored but " + refs + " are in memory";
                }
                return null;
            });
        });
    }
}
