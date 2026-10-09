package net.siftvanilla.siftcore.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.Flow;
import net.siftvanilla.siftcore.api.economy.Posting;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.Dialect;
import net.siftvanilla.siftcore.storage.SqlWork;

/**
 * The money engine. Every balance lives in memory and is changed only here, under one lock, so checks and changes
 * are atomic across all accounts and all economic domain state that joins a transaction. Each transaction is then
 * written as one database unit (ledger rows + balance deltas + domain SQL) through the ordered writer. If storing
 * fails the transaction is reverted in memory and its {@code committed} future fails, so callers that wait for it
 * before handing out items can never hand out items for money that was not saved.
 * <p>
 * Memory: two longs per account that ever held currency. That is bounded by the number of players who ever joined.
 */
public final class Ledger {

    private static final int CURRENCIES = Currency.values().length;
    private static final int FAILURES_BEFORE_READ_ONLY = 5;

    private final Database database;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, AtomicLongArray> balances = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Consumer<CommittedTx>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong storeFailures = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private volatile LedgerHooks hooks = LedgerHooks.NONE;
    private volatile long maxBalance;
    private volatile boolean available = true;
    private final String upsertSql;

    public Ledger(Database database, Logger logger, long maxBalance) {
        this.database = database;
        this.logger = logger;
        this.maxBalance = maxBalance;
        this.upsertSql = database.dialect().addUpsert("accounts", new String[] {"uuid", "currency"}, "balance");
    }

    public void hooks(LedgerHooks hooks) {
        this.hooks = hooks == null ? LedgerHooks.NONE : hooks;
    }

    public void maxBalance(long maxBalance) {
        this.maxBalance = maxBalance;
    }

    public long maxBalance() {
        return this.maxBalance;
    }

    public boolean available() {
        return this.available;
    }

    /** Re-enables the ledger after an operator fixed the storage problem. */
    public void resume() {
        this.storeFailures.set(0);
        this.available = true;
    }

    public long executedCount() {
        return this.executed.get();
    }

    public long storeFailures() {
        return this.storeFailures.get();
    }

    /** Subscribes to committed transactions. Listeners run on a database callback thread and must be quick. */
    public void subscribe(Consumer<CommittedTx> listener) {
        this.listeners.add(listener);
    }

    /** Loads every balance from storage. Call once at startup before any transaction. */
    public void load() throws Exception {
        Map<UUID, long[]> loaded = this.database.read(c -> {
            Map<UUID, long[]> result = new HashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, currency, balance FROM accounts")) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    Currency currency;
                    try {
                        currency = Currency.byId(rs.getString(2));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    result.computeIfAbsent(uuid, k -> new long[CURRENCIES])[currency.ordinal()] = rs.getLong(3);
                }
            }
            return result;
        }).get();
        this.balances.clear();
        loaded.forEach((uuid, values) -> this.balances.put(uuid, new AtomicLongArray(values)));
    }

    public long balance(UUID account, Currency currency) {
        AtomicLongArray values = this.balances.get(account);
        return values == null ? 0L : values.get(currency.ordinal());
    }

    /** Number of accounts held in memory. */
    public int accountCount() {
        return this.balances.size();
    }

    /** Sum of all balances in memory for a currency (players and system accounts). */
    public long totalSupply(Currency currency) {
        long total = 0;
        int index = currency.ordinal();
        for (AtomicLongArray values : this.balances.values()) {
            total = Math.addExact(total, values.get(index));
        }
        return total;
    }

    /** Visits every account balance of a currency (for leaderboards). Values may change during the walk. */
    public void forEach(Currency currency, AccountVisitor visitor) {
        int index = currency.ordinal();
        this.balances.forEach((uuid, values) -> visitor.visit(uuid, values.get(index)));
    }

    /** Receives account balances. */
    @FunctionalInterface
    public interface AccountVisitor {
        void visit(UUID account, long balance);
    }

    /**
     * Runs a transaction: fires the cancellable event, then checks and applies it atomically in memory and queues
     * it for storage. Never blocks on I/O. Safe from any thread.
     */
    public TransactionResult execute(LedgerTx tx) {
        if (!this.available) {
            return TransactionResult.failed(tx.id(), TransactionStatus.UNAVAILABLE, "read_only");
        }
        if (tx.fireEvent() && !this.hooks.allow(tx)) {
            return TransactionResult.failed(tx.id(), TransactionStatus.CANCELLED, "cancelled");
        }
        List<Posting> postings = tx.postings();
        long[] balancesAfter = new long[postings.size()];
        this.lock.lock();
        try {
            if (!this.available) {
                return TransactionResult.failed(tx.id(), TransactionStatus.UNAVAILABLE, "read_only");
            }
            for (var check : tx.checks()) {
                String reason = check.get();
                if (reason != null) {
                    return TransactionResult.failed(tx.id(), TransactionStatus.REJECTED, reason);
                }
            }
            Map<UUID, long[]> net = new HashMap<>(4);
            for (Posting posting : postings) {
                long[] sums = net.computeIfAbsent(posting.account(), k -> new long[CURRENCIES]);
                sums[posting.currency().ordinal()] = Math.addExact(sums[posting.currency().ordinal()], posting.delta());
            }
            for (Map.Entry<UUID, long[]> entry : net.entrySet()) {
                long[] sums = entry.getValue();
                for (int i = 0; i < CURRENCIES; i++) {
                    if (sums[i] == 0) {
                        continue;
                    }
                    long current = balance(entry.getKey(), Currency.values()[i]);
                    long next;
                    try {
                        next = Math.addExact(current, sums[i]);
                    } catch (ArithmeticException e) {
                        return TransactionResult.failed(tx.id(), TransactionStatus.BALANCE_LIMIT, "overflow");
                    }
                    if (next < 0) {
                        return TransactionResult.failed(tx.id(), TransactionStatus.INSUFFICIENT_FUNDS, entry.getKey().toString());
                    }
                    if (sums[i] > 0 && next > this.maxBalance && !SystemAccounts.isSystem(entry.getKey())) {
                        return TransactionResult.failed(tx.id(), TransactionStatus.BALANCE_LIMIT, entry.getKey().toString());
                    }
                }
            }
            for (int i = 0; i < postings.size(); i++) {
                Posting posting = postings.get(i);
                AtomicLongArray values = this.balances.computeIfAbsent(posting.account(), k -> new AtomicLongArray(CURRENCIES));
                balancesAfter[i] = values.addAndGet(posting.currency().ordinal(), posting.delta());
            }
            for (Runnable apply : tx.applies()) {
                apply.run();
            }
            this.executed.incrementAndGet();
            CompletableFuture<Void> committed = store(tx, balancesAfter);
            return new TransactionResult(tx.id(), TransactionStatus.SUCCESS, null, committed);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Runs domain-only changes (no money) atomically with the economy lock and storage ordering, e.g. creating an
     * auction listing whose item was already taken from the player. Same rules as {@link #execute(LedgerTx)}.
     */
    public TransactionResult executeDomain(LedgerTx tx) {
        return execute(tx);
    }

    /** Runs {@code action} while holding the economy lock, for reading a consistent view of domain state. */
    public <T> T locked(java.util.function.Supplier<T> action) {
        this.lock.lock();
        try {
            return action.get();
        } finally {
            this.lock.unlock();
        }
    }

    private CompletableFuture<Void> store(LedgerTx tx, long[] balancesAfter) {
        long now = System.currentTimeMillis();
        List<Posting> postings = tx.postings();
        SqlWork<Void> work = c -> {
            if (!postings.isEmpty()) {
                writeLedger(c, tx, balancesAfter, now);
            }
            for (SqlWork<?> write : tx.writes()) {
                write.run(c);
            }
            return null;
        };
        CompletableFuture<Void> result = new CompletableFuture<>();
        this.database.write(work).whenComplete((ignored, error) -> {
            if (error == null) {
                for (Runnable callback : tx.afterCommit()) {
                    try {
                        callback.run();
                    } catch (Throwable t) {
                        this.logger.log(Level.WARNING, "An after-commit step of transaction " + tx.id() + " failed", t);
                    }
                }
                result.complete(null);
                if (!postings.isEmpty()) {
                    CommittedTx committed = new CommittedTx(tx.id(), tx.actor(), tx.kind(), postings, balancesAfter, now);
                    notifyCommitted(committed);
                }
                return;
            }
            revert(tx, error);
            result.completeExceptionally(error);
        });
        return result;
    }

    private void notifyCommitted(CommittedTx committed) {
        try {
            this.hooks.committed(committed);
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "An economy event listener failed", t);
        }
        for (Consumer<CommittedTx> listener : this.listeners) {
            try {
                listener.accept(committed);
            } catch (Throwable t) {
                this.logger.log(Level.WARNING, "An economy listener failed", t);
            }
        }
    }

    private void revert(LedgerTx tx, Throwable error) {
        this.lock.lock();
        try {
            List<Posting> postings = tx.postings();
            for (int i = postings.size() - 1; i >= 0; i--) {
                Posting posting = postings.get(i);
                AtomicLongArray values = this.balances.get(posting.account());
                if (values != null) {
                    values.addAndGet(posting.currency().ordinal(), -posting.delta());
                }
            }
            List<Runnable> reverts = tx.reverts();
            for (int i = reverts.size() - 1; i >= 0; i--) {
                try {
                    reverts.get(i).run();
                } catch (Throwable t) {
                    this.logger.log(Level.SEVERE, "Reverting transaction " + tx.id() + " failed", t);
                }
            }
        } finally {
            this.lock.unlock();
        }
        long failures = this.storeFailures.incrementAndGet();
        this.logger.log(Level.SEVERE, "Transaction " + tx.id() + " (" + tx.kind() + ") could not be stored and was reverted", error);
        if (failures >= FAILURES_BEFORE_READ_ONLY && this.available) {
            this.available = false;
            this.logger.severe("The economy is now read-only after " + failures
                + " storage failures. Fix the database, then run /eco resume.");
        }
    }

    private void writeLedger(Connection c, LedgerTx tx, long[] balancesAfter, long now) throws SQLException {
        List<Posting> postings = tx.postings();
        try (PreparedStatement upsert = c.prepareStatement(this.upsertSql);
             PreparedStatement insert = c.prepareStatement("INSERT INTO ledger (tx_id, ts, currency, account, delta, "
                 + "balance_after, kind, flow, counterparty, ref, actor, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            String txId = tx.id().toString();
            for (int i = 0; i < postings.size(); i++) {
                Posting posting = postings.get(i);
                upsert.setString(1, posting.account().toString());
                upsert.setString(2, posting.currency().id());
                upsert.setLong(3, posting.delta());
                upsert.addBatch();
                insert.setString(1, txId);
                insert.setLong(2, now);
                insert.setString(3, posting.currency().id());
                insert.setString(4, posting.account().toString());
                insert.setLong(5, posting.delta());
                insert.setLong(6, balancesAfter[i]);
                insert.setString(7, posting.kind());
                insert.setString(8, posting.flow().name());
                if (posting.counterparty() == null) {
                    insert.setNull(9, Types.VARCHAR);
                } else {
                    insert.setString(9, posting.counterparty().toString());
                }
                if (posting.ref() == null) {
                    insert.setNull(10, Types.VARCHAR);
                } else {
                    insert.setString(10, posting.ref());
                }
                insert.setString(11, tx.actor());
                if (tx.note() == null) {
                    insert.setNull(12, Types.VARCHAR);
                } else {
                    insert.setString(12, tx.note());
                }
                insert.addBatch();
            }
            upsert.executeBatch();
            insert.executeBatch();
        }
    }

    /** Result of {@link #audit()}. */
    public record AuditReport(Map<Currency, long[]> perCurrency, List<String> problems) {
        /** perCurrency values: [memory sum, db balance sum, ledger delta sum, sources, sinks, transfer sum]. */
        public boolean healthy() {
            return this.problems.isEmpty();
        }
    }

    /**
     * Verifies the ledger invariants from storage after flushing pending writes:
     * total supply in memory = stored balances = sum of ledger deltas = sources - sinks, transfers net to zero,
     * every transaction's transfers net to zero, no negative balances, and every account matches its ledger.
     */
    public CompletableFuture<AuditReport> audit() {
        return this.database.write(c -> null).thenCompose(ignored -> this.database.read(c -> {
            Map<Currency, long[]> report = new EnumMap<>(Currency.class);
            List<String> problems = new ArrayList<>();
            for (Currency currency : Currency.values()) {
                long[] row = new long[6];
                row[0] = totalSupply(currency);
                row[1] = scalar(c, "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE currency = ?", currency.id());
                row[2] = scalar(c, "SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE currency = ?", currency.id());
                row[3] = scalar(c, "SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE currency = ? AND flow = 'SOURCE'", currency.id());
                row[4] = -scalar(c, "SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE currency = ? AND flow = 'SINK'", currency.id());
                row[5] = scalar(c, "SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE currency = ? AND flow = 'TRANSFER'", currency.id());
                report.put(currency, row);
                if (row[0] != row[1]) {
                    problems.add(currency.id() + ": memory supply " + row[0] + " differs from stored balances " + row[1]);
                }
                if (row[1] != row[2]) {
                    problems.add(currency.id() + ": stored balances " + row[1] + " differ from the ledger sum " + row[2]);
                }
                if (row[2] != row[3] - row[4]) {
                    problems.add(currency.id() + ": ledger sum " + row[2] + " differs from sources - sinks " + (row[3] - row[4]));
                }
                if (row[5] != 0) {
                    problems.add(currency.id() + ": transfers do not net to zero (" + row[5] + ")");
                }
                long unbalanced = scalar(c, "SELECT COUNT(*) FROM (SELECT tx_id FROM ledger WHERE currency = ? AND flow = 'TRANSFER' "
                    + "GROUP BY tx_id HAVING SUM(delta) <> 0) t", currency.id());
                if (unbalanced != 0) {
                    problems.add(currency.id() + ": " + unbalanced + " transaction(s) have transfers that do not net to zero");
                }
                long negative = scalar(c, "SELECT COUNT(*) FROM accounts WHERE currency = ? AND balance < 0", currency.id());
                if (negative != 0) {
                    problems.add(currency.id() + ": " + negative + " account(s) have a negative balance");
                }
                long mismatched = scalar(c, "SELECT COUNT(*) FROM accounts a LEFT JOIN (SELECT account, SUM(delta) s FROM ledger "
                    + "WHERE currency = ? GROUP BY account) l ON l.account = a.uuid WHERE a.currency = ? AND a.balance <> COALESCE(l.s, 0)",
                    currency.id(), currency.id());
                if (mismatched != 0) {
                    problems.add(currency.id() + ": " + mismatched + " account(s) differ from their ledger history");
                }
            }
            return new AuditReport(report, problems);
        }));
    }

    private static long scalar(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Recent ledger rows for an account, newest first. */
    public CompletableFuture<List<net.siftvanilla.siftcore.api.economy.EconomyApi.LedgerEntry>> history(UUID account, int limit, int offset) {
        return this.database.read(c -> {
            List<net.siftvanilla.siftcore.api.economy.EconomyApi.LedgerEntry> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, tx_id, ts, currency, account, delta, balance_after, kind, flow, "
                + "counterparty, ref FROM ledger WHERE account = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, account.toString());
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String counterparty = rs.getString(10);
                        rows.add(new net.siftvanilla.siftcore.api.economy.EconomyApi.LedgerEntry(rs.getLong(1),
                            UUID.fromString(rs.getString(2)), rs.getLong(3), Currency.byId(rs.getString(4)),
                            UUID.fromString(rs.getString(5)), rs.getLong(6), rs.getLong(7), rs.getString(8),
                            Flow.valueOf(rs.getString(9)), counterparty == null ? null : UUID.fromString(counterparty), rs.getString(11)));
                    }
                }
            }
            return rows;
        });
    }

    Dialect dialect() {
        return this.database.dialect();
    }
}
