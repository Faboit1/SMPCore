package net.siftvanilla.siftcore.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of database work. Writes run inside a transaction; throwing rolls back only this unit. */
@FunctionalInterface
public interface SqlWork<T> {

    T run(Connection connection) throws SQLException;
}
