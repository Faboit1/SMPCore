package net.siftvanilla.siftcore.feature.orders;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;

/**
 * The order engine against the real ledger, the ordered database writer and the real schema (SQLite in a temp
 * folder), with a clock the test moves.
 */
final class OrdersTestSupport implements AutoCloseable {

    static final UUID BUYER = new UUID(20, 1);
    static final UUID SELLER = new UUID(20, 2);
    static final UUID OTHER = new UUID(20, 3);
    static final int TAX = 200;
    static final long DAY = 86_400_000L;

    final Logger logger = Logger.getLogger("orders-test");
    final AtomicLong clock = new AtomicLong(1_000_000_000L);
    final AtomicLong ids = new AtomicLong(1);
    final JdbcDatabase database;
    final Ledger ledger;
    final OrderBook book = new OrderBook();
    final OrderEngine engine;

    OrdersTestSupport(Path dir) throws Exception {
        this(dir, OrderEngine.Rules.NONE);
    }

    OrdersTestSupport(Path dir, OrderEngine.Rules rules) throws Exception {
        this.logger.setLevel(Level.OFF);
        this.database = new JdbcDatabase(new SqliteSource(dir.resolve("orders.db"), 2), this.logger);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, this.logger, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.ledger = new Ledger(this.database, this.logger, 1_000_000_000_000_000L);
        this.ledger.load();
        this.engine = new OrderEngine(this.ledger, this.book, this.clock::get, this.ids::getAndIncrement, rules);
    }

    void fund(UUID player, long amount) throws Exception {
        TransactionResult result = this.ledger.execute(LedgerTx.builder().source(player, Currency.MONEY, amount, "test_grant", null).build());
        assertTrue(result.success());
        result.committed().get(10, TimeUnit.SECONDS);
    }

    long balance(UUID player) {
        return this.ledger.balance(player, Currency.MONEY);
    }

    long escrow() {
        return balance(SystemAccounts.ORDERS_ESCROW);
    }

    /** Places an order and waits until it is stored. */
    Order place(UUID owner, String key, int quantity, long price) throws Exception {
        OrderKeys.Parts parts = OrderKeys.parse(key);
        OrderEngine.Created created = this.engine.create(new OrderEngine.Draft(owner, parts.itemType(), parts.variant(), quantity, price,
            7 * DAY), Integer.MAX_VALUE, owner.toString());
        assertTrue(created.result().success(), () -> "placing failed: " + created.result());
        created.result().committed().get(10, TimeUnit.SECONDS);
        return created.order();
    }

    /** Waits for the commit of a successful transaction. */
    static void committed(TransactionResult result) throws Exception {
        assertTrue(result.success(), () -> "expected success, got " + result);
        result.committed().get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    interface Rows<T> {
        T read(ResultSet rs) throws java.sql.SQLException;
    }

    /** Reads one value after every queued write was committed. */
    <T> T sql(String query, Rows<T> reader, Object... params) throws Exception {
        this.database.flush();
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? reader.read(rs) : null;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    long number(String query, Object... params) throws Exception {
        Long value = sql(query, rs -> rs.getLong(1), params);
        return value == null ? 0 : value;
    }

    /** The ledger's own invariants, the escrow invariant and the book's rules all hold, and memory matches storage. */
    void assertHealthy() throws Exception {
        this.database.flush();
        Ledger.AuditReport report = this.ledger.audit().get(10, TimeUnit.SECONDS);
        assertTrue(report.healthy(), () -> "ledger problems: " + report.problems());
        assertNull(this.engine.verifyEscrow());
        assertNull(this.engine.verifyIndex());
        long storedHeld = number("SELECT COALESCE(SUM(escrow), 0) FROM orders WHERE state = 'ACTIVE'");
        assertTrue(storedHeld == this.book.escrowTotal(), () -> "storage holds " + storedHeld + " but memory " + this.book.escrowTotal());
    }

    @Override
    public void close() {
        this.database.close();
    }
}
