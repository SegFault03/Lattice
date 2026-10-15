package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import java.sql.Connection;
import java.util.UUID;

/** A serialized JDBC session belonging to one console or editor. */
public final class DatabaseSession implements AutoCloseable {
    @FunctionalInterface
    public interface Operation<T> { T run(Connection connection) throws Exception; }

    private final DatabaseConnectionManager manager;
    private final ConnectionConfig config;
    private final String sessionId = UUID.randomUUID().toString();
    private boolean closed;

    DatabaseSession(DatabaseConnectionManager manager, ConnectionConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public synchronized <T> T execute(Operation<T> operation) throws Exception {
        if (closed) throw new IllegalStateException("Database session is closed");
        return operation.run(manager.getConnection(config, sessionId));
    }

    public synchronized com.segfault03.ideadb.model.QueryResult executeQuery(String namespace, String sql,
            DataService.QueryOptions options, QueryExecution execution) throws Exception {
        var result = execute(connection -> DataService.getInstance().executeQuery(connection, namespace, sql, options, execution));
        // A driver can lose its session after a successful post-cancel health check.
        // Retry preparation once on a new connection, only if no user SQL was sent.
        if (result.hasError() && !execution.hasStartedSql() && discardInvalidConnection()) {
            execution.markConnectionDiscarded();
            if (execution.prepareRetryBeforeSql())
                result = execute(connection -> DataService.getInstance().executeQuery(connection, namespace, sql, options, execution));
        }
        return result;
    }

    /** Validate only after cancellation; preserve healthy connections and their transactions. */
    public synchronized boolean discardInvalidConnection() {
        return !closed && manager.discardInvalidSession(config.getId(), sessionId);
    }

    @Override public synchronized void close() {
        closed = true;
        manager.closeSession(config.getId(), sessionId);
    }
}
