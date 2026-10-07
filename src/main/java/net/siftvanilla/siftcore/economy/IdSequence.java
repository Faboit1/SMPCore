package net.siftvanilla.siftcore.economy;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Hands out ids for tables whose rows must be referenced before they are committed (listings, orders, deliveries).
 * Seeded from the table's current maximum at startup; only this process writes these tables.
 */
public final class IdSequence {

    private final AtomicLong next;

    private IdSequence(long start) {
        this.next = new AtomicLong(start);
    }

    public static IdSequence forTable(Database database, String table) throws Exception {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Bad table name " + table);
        }
        long max = database.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + table)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }).get();
        return new IdSequence(max + 1);
    }

    public long next() {
        return this.next.getAndIncrement();
    }
}
