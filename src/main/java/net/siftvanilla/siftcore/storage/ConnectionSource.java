package net.siftvanilla.siftcore.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Supplies JDBC connections for one backend. */
public interface ConnectionSource extends AutoCloseable {

    Dialect dialect();

    /** A new connection for the single writer thread. */
    Connection openWriter() throws SQLException;

    /** A connection for a read; callers close it when done. */
    Connection openReader() throws SQLException;

    /** Whether reader connections should be kept per reader thread instead of closed after each read. */
    boolean pinReaders();

    int readerThreads();

    @Override
    void close();
}
