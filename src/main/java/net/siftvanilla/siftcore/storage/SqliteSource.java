package net.siftvanilla.siftcore.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.IOException;

/**
 * SQLite in WAL mode: one writer connection plus pinned per-thread reader connections, which WAL lets run
 * concurrently with the writer. Uses the sqlite-jdbc driver bundled with the server.
 */
public final class SqliteSource implements ConnectionSource {

    private final String url;
    private final int readers;

    public SqliteSource(Path file, int readers) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        this.readers = Math.max(1, readers);
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IOException("The SQLite JDBC driver is not available on this server", e);
        }
    }

    @Override
    public Dialect dialect() {
        return Dialect.SQLITE;
    }

    @Override
    public Connection openWriter() throws SQLException {
        Connection connection = DriverManager.getConnection(this.url);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=10000");
            st.execute("PRAGMA temp_store=MEMORY");
        }
        return connection;
    }

    @Override
    public Connection openReader() throws SQLException {
        Connection connection = DriverManager.getConnection(this.url);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout=10000");
            st.execute("PRAGMA query_only=ON");
        }
        return connection;
    }

    @Override
    public boolean pinReaders() {
        return true;
    }

    @Override
    public int readerThreads() {
        return this.readers;
    }

    @Override
    public void close() {
    }
}
