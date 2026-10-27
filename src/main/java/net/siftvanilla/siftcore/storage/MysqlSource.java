package net.siftvanilla.siftcore.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** MariaDB/MySQL through a small HikariCP pool, using the MySQL Connector/J driver bundled with the server. */
public final class MysqlSource implements ConnectionSource {

    private final HikariDataSource pool;
    private final int readers;

    public MysqlSource(String host, int port, String database, String user, String password, int poolSize,
                       boolean useSsl) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("SiftCore");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=" + useSsl
            + "&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&tcpKeepAlive=true");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, poolSize));
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);
        config.setMaxLifetime(1_500_000);
        config.setKeepaliveTime(300_000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        this.pool = new HikariDataSource(config);
        this.readers = Math.max(1, poolSize - 1);
    }

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public Connection openWriter() throws SQLException {
        return this.pool.getConnection();
    }

    @Override
    public Connection openReader() throws SQLException {
        return this.pool.getConnection();
    }

    @Override
    public boolean pinReaders() {
        return false;
    }

    @Override
    public int readerThreads() {
        return this.readers;
    }

    @Override
    public void close() {
        this.pool.close();
    }
}
