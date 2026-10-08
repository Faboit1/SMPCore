package net.siftvanilla.siftcore.feature.shards;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Crate keys bought with shards, given safely although the keys live in another feature's storage.
 * <p>
 * The purchase is a saga with a journal ({@code shard_purchases}):
 * <ol>
 *   <li>One ledger transaction takes the shards (kind {@code shard_shop}) and writes the purchase as {@code pending}.
 *   Both commit together or not at all.</li>
 *   <li>After that commit, {@link CrateKeys#give} is called with the purchase's unique ref. The crates feature gives
 *   keys for a ref at most once (a second grant answers {@code duplicate}), so retrying is always safe.</li>
 *   <li>Keys stored: the purchase becomes {@code done}. Keys refused (or their storage failed, which reverts them): a
 *   compensating transaction gives the shards back (kind {@code shard_refund}) and marks the purchase
 *   {@code refunded}, atomically.</li>
 * </ol>
 * Purchases still pending after a crash, or because the refund itself could not be stored, are resumed at startup and
 * every few minutes. Which pending purchases exist is held in memory and changed only inside ledger transactions
 * (under the economy lock), so a purchase can be finished only once: two attempts can never both refund.
 * Bukkit-free; unit tested against a real ledger.
 */
final class KeyGrants {

    static final String SPEND_KIND = "shard_shop";
    static final String REFUND_KIND = "shard_refund";

    /** A crate key purchase. */
    record Purchase(String ref, UUID player, String offer, String crate, int keys, long cost, long created) {
    }

    /** How a purchase ended (this time). */
    enum Outcome {
        /** The keys were given. */
        GRANTED,
        /** The keys could not be given; the shards were given back. */
        REFUNDED,
        /** Neither could be completed now (storage trouble), or another attempt is running; it stays pending. */
        PENDING,
        /** It was already finished by an earlier attempt; nothing happened now. */
        FINISHED
    }

    private final Ledger ledger;
    private final Database database;
    private final CrateKeys keys;
    private final Executor async;
    private final Logger logger;
    private final Map<String, Purchase> pending = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    KeyGrants(Ledger ledger, Database database, CrateKeys keys, Executor async, Logger logger) {
        this.ledger = ledger;
        this.database = database;
        this.keys = keys;
        this.async = async;
        this.logger = logger;
    }

    /** A new purchase reference ({@code shards:<uuid>}, 43 characters). */
    static String newRef() {
        return "shards:" + UUID.randomUUID();
    }

    /** Purchases waiting for their keys or refund. */
    List<Purchase> pending() {
        return new ArrayList<>(this.pending.values());
    }

    /**
     * Adds the shard payment and the journal row to {@code tx}: the purchase exists exactly when the shards were
     * taken. Execute the transaction, then call {@link #grant} once {@code committed()} completes.
     */
    LedgerTx.Builder purchase(LedgerTx.Builder tx, Purchase purchase) {
        tx.sink(purchase.player(), Currency.SHARDS, purchase.cost(), SPEND_KIND, purchase.ref());
        tx.apply(() -> this.pending.put(purchase.ref(), purchase), () -> this.pending.remove(purchase.ref(), purchase));
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO shard_purchases (ref, uuid, offer, crate, key_count, cost, "
                + "created, state, closed) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', NULL)")) {
                ps.setString(1, purchase.ref());
                ps.setString(2, purchase.player().toString());
                ps.setString(3, purchase.offer());
                ps.setString(4, purchase.crate());
                ps.setInt(5, purchase.keys());
                ps.setLong(6, purchase.cost());
                ps.setLong(7, purchase.created());
                ps.executeUpdate();
            }
            return null;
        });
        return tx;
    }

    /**
     * Gives the keys of a stored purchase, or refunds it. Runs the grant on the async executor (the crates feature may
     * block on its storage). Completes with how it ended; never exceptionally. Calling it again while an attempt is
     * running answers {@link Outcome#PENDING} without doing anything.
     */
    CompletableFuture<Outcome> grant(Purchase purchase) {
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        if (!this.pending.containsKey(purchase.ref()) || !this.inFlight.add(purchase.ref())) {
            result.complete(this.pending.containsKey(purchase.ref()) ? Outcome.PENDING : Outcome.FINISHED);
            return result;
        }
        result.whenComplete((outcome, error) -> this.inFlight.remove(purchase.ref()));
        try {
            this.async.execute(() -> attempt(purchase, result));
        } catch (RuntimeException e) {
            this.logger.log(Level.WARNING, "Could not start giving the keys of shard purchase " + purchase.ref() + "; it will be retried", e);
            result.complete(Outcome.PENDING);
        }
        return result;
    }

    private void attempt(Purchase purchase, CompletableFuture<Outcome> result) {
        TransactionResult given;
        try {
            given = this.keys.give(purchase.player(), purchase.crate(), purchase.keys(), purchase.player().toString(), purchase.ref());
        } catch (RuntimeException e) {
            this.logger.log(Level.WARNING, "Giving the keys of shard purchase " + purchase.ref() + " failed; refunding", e);
            refund(purchase, "the crates feature failed", result);
            return;
        }
        if (given.success()) {
            given.committed().whenComplete((ignored, error) -> {
                if (error != null) {
                    // The crates feature could not store the keys and took them back: give the shards back instead.
                    refund(purchase, "the keys could not be stored", result);
                } else {
                    done(purchase, result);
                }
            });
            return;
        }
        if ("duplicate".equals(given.reason())) {
            // An earlier attempt gave them (we crashed before recording it).
            done(purchase, result);
            return;
        }
        refund(purchase, "the crates feature refused (" + given.status() + (given.reason() == null ? "" : ", " + given.reason()) + ")", result);
    }

    private void done(Purchase purchase, CompletableFuture<Outcome> result) {
        long now = System.currentTimeMillis();
        LedgerTx tx = LedgerTx.builder()
            .actor("system")
            .silent()
            .check(() -> this.pending.containsKey(purchase.ref()) ? null : "finished")
            .apply(() -> this.pending.remove(purchase.ref()), () -> this.pending.put(purchase.ref(), purchase))
            .write(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE shard_purchases SET state = 'done', closed = ? WHERE ref = ? AND state = 'pending'")) {
                    ps.setLong(1, now);
                    ps.setString(2, purchase.ref());
                    ps.executeUpdate();
                }
                return null;
            })
            .build();
        TransactionResult marked = this.ledger.executeDomain(tx);
        // The keys are given either way; if this mark is lost, the next attempt finds the ref already used and marks it.
        if (!marked.success()) {
            result.complete(Outcome.GRANTED);
            return;
        }
        marked.committed().whenComplete((ignored, error) -> result.complete(Outcome.GRANTED));
    }

    private void refund(Purchase purchase, String why, CompletableFuture<Outcome> result) {
        long now = System.currentTimeMillis();
        LedgerTx tx = LedgerTx.builder()
            .actor("system")
            .note("refund of " + purchase.offer() + ": " + why)
            .silent()
            .source(purchase.player(), Currency.SHARDS, purchase.cost(), REFUND_KIND, purchase.ref())
            .check(() -> this.pending.containsKey(purchase.ref()) ? null : "finished")
            .apply(() -> this.pending.remove(purchase.ref()), () -> this.pending.put(purchase.ref(), purchase))
            .write(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE shard_purchases SET state = 'refunded', closed = ? WHERE ref = ? AND state = 'pending'")) {
                    ps.setLong(1, now);
                    ps.setString(2, purchase.ref());
                    if (ps.executeUpdate() != 1) {
                        throw new java.sql.SQLException("Shard purchase " + purchase.ref() + " is not pending in storage");
                    }
                }
                return null;
            })
            .build();
        TransactionResult refunded = this.ledger.execute(tx);
        if (!refunded.success()) {
            if (!"finished".equals(refunded.reason())) {
                this.logger.warning("Shard purchase " + purchase.ref() + " could not be refunded now (" + refunded.status()
                    + "); it stays pending and is retried");
            }
            result.complete("finished".equals(refunded.reason()) ? Outcome.FINISHED : Outcome.PENDING);
            return;
        }
        this.logger.info("Refunded " + purchase.cost() + " shards to " + purchase.player() + " for " + purchase.keys() + " "
            + purchase.crate() + " key(s) that could not be given (" + why + ").");
        refunded.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "The refund of shard purchase " + purchase.ref() + " was not stored; it is retried", error);
                result.complete(Outcome.PENDING);
            } else {
                result.complete(Outcome.REFUNDED);
            }
        });
    }

    /**
     * Loads every pending purchase from storage (startup, before {@link #resume}). Blocking; call from enable or an
     * async thread.
     */
    int load() throws Exception {
        List<Purchase> rows = this.database.read(c -> {
            List<Purchase> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ref, uuid, offer, crate, key_count, cost, created FROM shard_purchases "
                + "WHERE state = 'pending' ORDER BY created");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Purchase(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getLong(6), rs.getLong(7)));
                }
            }
            return list;
        }).get();
        this.ledger.locked(() -> {
            this.pending.clear();
            for (Purchase purchase : rows) {
                this.pending.put(purchase.ref(), purchase);
            }
            return null;
        });
        return rows.size();
    }

    /** Retries every pending purchase not already being worked on; {@code outcome} hears how each ended. */
    void resume(BiConsumer<Purchase, Outcome> outcome) {
        for (Purchase purchase : pending()) {
            if (this.inFlight.contains(purchase.ref())) {
                continue;
            }
            grant(purchase).thenAccept(result -> outcome.accept(purchase, result));
        }
    }

    /** Self-test: the pending purchases in memory match the ones in storage. */
    CompletableFuture<String> check() {
        return this.database.write(c -> null).thenCompose(ignored -> this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM shard_purchases WHERE state = 'pending'");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        })).thenApply(stored -> {
            int memory = this.pending.size();
            return stored == memory ? null : memory + " pending key purchase(s) in memory but " + stored + " in storage";
        });
    }
}
