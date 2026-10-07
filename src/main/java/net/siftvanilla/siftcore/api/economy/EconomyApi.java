package net.siftvanilla.siftcore.api.economy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public economy API. Balances are whole numbers. Every method is thread-safe and non-blocking.
 * Each change fires a cancellable {@link net.siftvanilla.siftcore.api.event.EconomyTransactionEvent} first.
 */
public interface EconomyApi {

    /** The current balance, 0 for unknown accounts. */
    long balance(UUID account, Currency currency);

    boolean has(UUID account, Currency currency, long amount);

    /** Creates currency in an account (a {@link Flow#SOURCE}). {@code kind} names the reason, e.g. {@code plugin_reward}. */
    TransactionResult deposit(UUID account, Currency currency, long amount, String kind, String ref);

    /** Destroys currency from an account (a {@link Flow#SINK}). */
    TransactionResult withdraw(UUID account, Currency currency, long amount, String kind, String ref);

    /** Moves currency between two accounts atomically. */
    TransactionResult transfer(UUID from, UUID to, Currency currency, long amount, String kind, String ref);

    /** Formats an amount the way the server displays it, e.g. {@code $1,500} or {@code 1,500 shards}. */
    String format(Currency currency, long amount);

    /** A snapshot of the richest accounts, refreshed periodically, best first. */
    List<TopEntry> top(Currency currency, int limit);

    /** Recent ledger rows of an account, newest first. */
    CompletableFuture<List<LedgerEntry>> history(UUID account, int limit);

    /** An entry of a leaderboard. */
    record TopEntry(int rank, UUID account, String name, long value) {
    }

    /** A ledger row. */
    record LedgerEntry(long id, UUID transaction, long timestamp, Currency currency, UUID account, long delta,
                       long balanceAfter, String kind, Flow flow, UUID counterparty, String ref) {
    }
}
