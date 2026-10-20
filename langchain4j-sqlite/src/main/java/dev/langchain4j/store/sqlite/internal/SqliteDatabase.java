package dev.langchain4j.store.sqlite.internal;

import dev.langchain4j.Internal;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

/** Per-operation JDBC ownership shared by the SQLite stores; not a public compatibility API. */
@Internal
public final class SqliteDatabase implements AutoCloseable {
    private final String jdbcUrl;
    private final int busyTimeoutMillis;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;

    public SqliteDatabase(Path databasePath, int busyTimeoutMillis) {
        Path file = ensureNotNull(databasePath, "databasePath").toAbsolutePath().normalize();
        if (Files.isDirectory(file)) throw new IllegalArgumentException("databasePath must be a file");
        if (busyTimeoutMillis < 0) throw new IllegalArgumentException("busyTimeoutMillis must not be negative");
        this.busyTimeoutMillis = busyTimeoutMillis;
        this.jdbcUrl = "jdbc:sqlite:" + file.toUri().toASCIIString();
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException error) { throw new IllegalStateException("SQLite JDBC driver is not available", error); }
    }

    public interface Work<T> { T run(Connection connection) throws SQLException; }

    public <T> T transaction(boolean write, Work<T> work) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("SQLite store is closed");
            Properties properties = new Properties();
            properties.setProperty("busy_timeout", Integer.toString(busyTimeoutMillis));
            properties.setProperty("transaction_mode", write ? "IMMEDIATE" : "DEFERRED");
            try (Connection connection = DriverManager.getConnection(jdbcUrl, properties)) {
                connection.setAutoCommit(false);
                try {
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException | Error failure) {
                    try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            } catch (SQLException error) { throw new IllegalStateException("SQLite store operation failed", error); }
        } finally { lifecycle.readLock().unlock(); }
    }

    public void checkOpen() {
        lifecycle.readLock().lock();
        try { if (closed) throw new IllegalStateException("SQLite store is closed"); }
        finally { lifecycle.readLock().unlock(); }
    }

    @Override public void close() {
        lifecycle.writeLock().lock();
        try { closed = true; } finally { lifecycle.writeLock().unlock(); }
    }
}
