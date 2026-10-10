package net.siftvanilla.siftcore.feature.orders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.Dialect;
import net.siftvanilla.siftcore.storage.SqlWork;

/**
 * The {@code orders}, {@code order_fills} and {@code order_notices} tables (migrations V003 and V020-V023). Every change
 * to an order is SQL that runs inside the economy transaction that made it, and every update is guarded by the exact
 * state it expects (the previous count, price or state): if an earlier transaction it built on failed to store, the
 * guard fails, this one is rolled back as well, and memory and storage never drift apart.
 */
final class OrderStore {

    /** One delivery to an order. {@code paid} is what the order paid; the seller received {@code paid - tax}. */
    record Fill(UUID seller, int quantity, long paid, long tax, long timestamp, FillSource source) {
    }

    /** What {@link #loadOpen()} read: the orders, and rows that could not be read (described). */
    record Loaded(List<Order> orders, List<String> unreadable) {
    }

    /** A finished order for the history: the order and what it paid out in total. */
    record Past(Order order, long paidOut) {
    }

    /** One delivery a player made, for their deliveries history. */
    record Delivery(long orderId, UUID buyer, String itemType, String variant, int quantity, long paid, long tax, long timestamp,
                    FillSource source) {
        long earned() {
            return this.paid - this.tax;
        }
    }

    /** Totals of a player's deliveries. */
    record DeliveryTotals(long count, long earned) {
    }

    /** A notice row joined with its order (the order may be gone after a purge, then item fields are null). */
    record NoticeRow(long orderId, String kind, int units, long amount, String detail, long created, String itemType, String variant,
                     int quantity, long expires) {
    }

    private static final String COLUMNS = "id, owner, item_type, variant, quantity, filled, collected, price_each, escrow, created, "
        + "expires, state, ended, refunded, warned";

    private final Database database;

    OrderStore(Database database) {
        this.database = database;
    }

    Dialect dialect() {
        return this.database.dialect();
    }

    // ------------------------------------------------------------------ reads

    /** Orders that are active or still have items to collect. */
    CompletableFuture<Loaded> loadOpen() {
        return this.database.read(c -> {
            List<Order> orders = new ArrayList<>();
            List<String> unreadable = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM orders WHERE state = ? OR collected < filled ORDER BY id")) {
                ps.setString(1, OrderState.ACTIVE.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        try {
                            orders.add(read(rs));
                        } catch (IllegalArgumentException e) {
                            unreadable.add("order " + id + ": " + e.getMessage());
                        }
                    }
                }
            }
            return new Loaded(orders, unreadable);
        });
    }

    private static Order read(ResultSet rs) throws SQLException {
        return new Order(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
            rs.getInt(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11), OrderState.parse(rs.getString(12)),
            rs.getLong(13), rs.getLong(14), rs.getInt(15) != 0);
    }

    /** Recent deliveries to an order, newest first. */
    CompletableFuture<List<Fill>> fills(long orderId, int limit) {
        return this.database.read(c -> {
            List<Fill> fills = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT seller, quantity, paid, tax, ts, source FROM order_fills WHERE order_id = ? ORDER BY id DESC LIMIT ?")) {
                ps.setLong(1, orderId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fills.add(new Fill(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                            FillSource.byId(rs.getString(6))));
                    }
                }
            }
            return fills;
        });
    }

    /** What an order paid out so far (the sum of its deliveries). */
    CompletableFuture<Long> paidOut(long orderId) {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(paid), 0) FROM order_fills WHERE order_id = ?")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** An order from storage, whatever its state (for staff lookups of closed orders). */
    CompletableFuture<Order> find(long id) {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM orders WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            }
        });
    }

    /** A player's orders that ended (complete, cancelled or expired), newest first, with what each paid out. */
    CompletableFuture<List<Past>> history(UUID owner, int limit) {
        return this.database.read(c -> {
            List<Past> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + ", (SELECT COALESCE(SUM(f.paid), 0) FROM order_fills f "
                + "WHERE f.order_id = orders.id) FROM orders WHERE owner = ? AND state <> ? ORDER BY COALESCE(ended, expires) DESC, id DESC LIMIT ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, OrderState.ACTIVE.name());
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            rows.add(new Past(read(rs), rs.getLong(16)));
                        } catch (IllegalArgumentException e) {
                            // An unreadable row (unknown state) is left out of the history view.
                        }
                    }
                }
            }
            return rows;
        });
    }

    /** A player's deliveries to other players' orders, newest first. */
    CompletableFuture<List<Delivery>> deliveries(UUID seller, int limit) {
        return this.database.read(c -> {
            List<Delivery> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT f.order_id, o.owner, o.item_type, o.variant, f.quantity, f.paid, f.tax, "
                + "f.ts, f.source FROM order_fills f JOIN orders o ON o.id = f.order_id WHERE f.seller = ? ORDER BY f.id DESC LIMIT ?")) {
                ps.setString(1, seller.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Delivery(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), FillSource.byId(rs.getString(9))));
                    }
                }
            }
            return rows;
        });
    }

    /** How many deliveries a player made and what they earned after tax, over their whole history. */
    CompletableFuture<DeliveryTotals> deliveryTotals(UUID seller) {
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(paid - tax), 0) FROM order_fills WHERE seller = ?")) {
                ps.setString(1, seller.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new DeliveryTotals(rs.getLong(1), rs.getLong(2)) : new DeliveryTotals(0, 0);
                }
            }
        });
    }

    /** How many orders were placed per order key since {@code since} (the item picker's "most ordered" sort). */
    CompletableFuture<Map<String, Integer>> popularity(long since) {
        return this.database.read(c -> {
            Map<String, Integer> counts = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_type, variant, COUNT(*) FROM orders WHERE created >= ? GROUP BY item_type, variant")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        counts.merge(OrderKeys.key(rs.getString(1), rs.getString(2)), rs.getInt(3), Integer::sum);
                    }
                }
            }
            return Map.copyOf(counts);
        });
    }

    /**
     * Reads {@code [active orders, money they hold, orders with items waiting]} as the ordered writer sees it, so the
     * result reflects exactly the transactions queued before this call.
     */
    CompletableFuture<long[]> totalsInOrder() {
        return this.database.write(OrderStore::totals);
    }

    private static long[] totals(Connection c) throws SQLException {
        long[] result = new long[3];
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(escrow), 0) FROM orders WHERE state = ?")) {
            ps.setString(1, OrderState.ACTIVE.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result[0] = rs.getLong(1);
                    result[1] = rs.getLong(2);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM orders WHERE state <> ? AND collected < filled")) {
            ps.setString(1, OrderState.ACTIVE.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result[2] = rs.getLong(1);
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ notices (outside transactions, best effort)

    /** Adds to the owner's notice for an order (deliveries add up), creating it when missing. */
    CompletableFuture<Void> notice(UUID owner, long orderId, String kind, int units, long amount, String detail, long now) {
        String clipped = detail == null ? null : detail.length() > 64 ? detail.substring(0, 64) : detail;
        String sql = switch (this.database.dialect()) {
            case SQLITE -> "INSERT INTO order_notices (owner, order_id, kind, units, amount, detail, created) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(owner, order_id, kind) DO UPDATE SET units = units + excluded.units, amount = amount + excluded.amount, "
                + "detail = excluded.detail, created = excluded.created";
            case MYSQL -> "INSERT INTO order_notices (owner, order_id, kind, units, amount, detail, created) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE units = units + VALUES(units), amount = amount + VALUES(amount), detail = VALUES(detail), "
                + "created = VALUES(created)";
        };
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                ps.setLong(2, orderId);
                ps.setString(3, kind);
                ps.setInt(4, units);
                ps.setLong(5, amount);
                if (clipped == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, clipped);
                }
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The owner's notices, newest first, with the order each one is about. */
    CompletableFuture<List<NoticeRow>> notices(UUID owner) {
        return this.database.read(c -> {
            List<NoticeRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT n.order_id, n.kind, n.units, n.amount, n.detail, n.created, o.item_type, "
                + "o.variant, o.quantity, o.expires FROM order_notices n LEFT JOIN orders o ON o.id = n.order_id WHERE n.owner = ? "
                + "ORDER BY n.created DESC, n.order_id DESC")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new NoticeRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getString(5), rs.getLong(6),
                            rs.getString(7), rs.getString(8), rs.getInt(9), rs.getLong(10)));
                    }
                }
            }
            return rows;
        });
    }

    /**
     * Deletes notices that were shown. A row is only deleted while it still holds what was shown, so a delivery added
     * meanwhile is shown next time instead of being lost.
     */
    CompletableFuture<Void> deleteNotices(UUID owner, List<NoticeRow> shown) {
        return this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM order_notices WHERE owner = ? AND order_id = ? AND kind = ? AND units = ? AND amount = ?")) {
                for (NoticeRow row : shown) {
                    ps.setString(1, owner.toString());
                    ps.setLong(2, row.orderId());
                    ps.setString(3, row.kind());
                    ps.setInt(4, row.units());
                    ps.setLong(5, row.amount());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /**
     * Deletes closed orders (ended, everything collected) that ended before {@code before}, with their deliveries and
     * notices; {@code keep} are ids still held in memory. Returns how many orders were deleted.
     */
    CompletableFuture<Integer> purge(long before) {
        return this.database.write(c -> {
            String closed = "SELECT id FROM orders WHERE state <> 'ACTIVE' AND collected >= filled AND ended IS NOT NULL AND ended > 0 AND ended < ?";
            try (PreparedStatement fills = c.prepareStatement("DELETE FROM order_fills WHERE order_id IN (" + closed + ")");
                 PreparedStatement notices = c.prepareStatement("DELETE FROM order_notices WHERE order_id IN (" + closed + ")");
                 PreparedStatement orders = c.prepareStatement("DELETE FROM orders WHERE id IN (SELECT id FROM (" + closed + ") t)")) {
                fills.setLong(1, before);
                fills.executeUpdate();
                notices.setLong(1, before);
                notices.executeUpdate();
                orders.setLong(1, before);
                return orders.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ writes (run inside economy transactions)

    static SqlWork<Void> insert(Order order) {
        return c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, order.id());
                ps.setString(2, order.owner().toString());
                ps.setString(3, order.itemType());
                if (order.variant() == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, order.variant());
                }
                ps.setInt(5, order.quantity());
                ps.setInt(6, order.filled());
                ps.setInt(7, order.collected());
                ps.setLong(8, order.priceEach());
                ps.setLong(9, order.escrow());
                ps.setLong(10, order.created());
                ps.setLong(11, order.expires());
                ps.setString(12, order.state().name());
                if (order.ended() > 0) {
                    ps.setLong(13, order.ended());
                } else {
                    ps.setNull(13, Types.BIGINT);
                }
                ps.setLong(14, order.refunded());
                ps.setInt(15, order.warned() ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        };
    }

    /** A delivery: the order's new counts, guarded by the count and price it had, plus the fill row. */
    static void fill(Connection c, Order before, Order after, UUID seller, long paid, long tax, long now, String source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE orders SET filled = ?, escrow = ?, state = ?, ended = ? WHERE id = ? AND filled = ? AND state = ? AND price_each = ?")) {
            ps.setInt(1, after.filled());
            ps.setLong(2, after.escrow());
            ps.setString(3, after.state().name());
            if (after.ended() > 0) {
                ps.setLong(4, after.ended());
            } else {
                ps.setNull(4, Types.BIGINT);
            }
            ps.setLong(5, after.id());
            ps.setInt(6, before.filled());
            ps.setString(7, OrderState.ACTIVE.name());
            ps.setLong(8, before.priceEach());
            expectOne(ps.executeUpdate(), after.id(), "fill");
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO order_fills (order_id, seller, quantity, paid, tax, ts, source) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, after.id());
            ps.setString(2, seller.toString());
            ps.setInt(3, after.filled() - before.filled());
            ps.setLong(4, paid);
            ps.setLong(5, tax);
            ps.setLong(6, now);
            ps.setString(7, source);
            ps.executeUpdate();
        }
    }

    /** Cancel or expiry: the end state and refund, guarded by the active state and the delivered count it had. */
    static void end(Connection c, Order before, Order after) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE orders SET escrow = ?, state = ?, ended = ?, refunded = ? WHERE id = ? AND state = ? AND filled = ?")) {
            ps.setLong(1, after.escrow());
            ps.setString(2, after.state().name());
            ps.setLong(3, after.ended());
            ps.setLong(4, after.refunded());
            ps.setLong(5, after.id());
            ps.setString(6, OrderState.ACTIVE.name());
            ps.setInt(7, before.filled());
            expectOne(ps.executeUpdate(), after.id(), "end");
        }
    }

    /** A raised price or quantity, guarded by the terms and count the owner saw. */
    static void terms(Connection c, Order before, Order after) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET price_each = ?, quantity = ?, escrow = ? "
            + "WHERE id = ? AND state = 'ACTIVE' AND filled = ? AND price_each = ? AND quantity = ?")) {
            ps.setLong(1, after.priceEach());
            ps.setInt(2, after.quantity());
            ps.setLong(3, after.escrow());
            ps.setLong(4, after.id());
            ps.setInt(5, before.filled());
            ps.setLong(6, before.priceEach());
            ps.setInt(7, before.quantity());
            expectOne(ps.executeUpdate(), after.id(), "edit");
        }
    }

    /** A new end time, guarded by the old one. */
    static void expires(Connection c, Order before, Order after) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE orders SET expires = ?, warned = ? WHERE id = ? AND state = 'ACTIVE' AND expires = ?")) {
            ps.setLong(1, after.expires());
            ps.setInt(2, after.warned() ? 1 : 0);
            ps.setLong(3, after.id());
            ps.setLong(4, before.expires());
            expectOne(ps.executeUpdate(), after.id(), "extend");
        }
    }

    /** The owner was warned that the order ends soon. */
    static void warned(Connection c, Order after) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET warned = 1 WHERE id = ? AND warned = 0")) {
            ps.setLong(1, after.id());
            expectOne(ps.executeUpdate(), after.id(), "warn");
        }
    }

    /** Collecting (or putting back): the new collected count, guarded by the old one and by what was delivered. */
    static void collected(Connection c, Order before, Order after) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE orders SET collected = ? WHERE id = ? AND collected = ? AND filled >= ?")) {
            ps.setInt(1, after.collected());
            ps.setLong(2, after.id());
            ps.setInt(3, before.collected());
            ps.setInt(4, after.collected());
            expectOne(ps.executeUpdate(), after.id(), "collect");
        }
    }

    private static void expectOne(int updated, long id, String what) throws SQLException {
        if (updated != 1) {
            throw new SQLException("Order " + id + " was not in the expected state in storage (" + what + ")");
        }
    }
}
