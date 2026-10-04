package com.vibe.ideadb.service;

import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.ConnectionTestResult;
import com.vibe.ideadb.model.DatabaseType;
import com.vibe.ideadb.model.HsqlMode;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseConnectionManager implements com.intellij.openapi.Disposable {
    private static final class Standalone { private static final DatabaseConnectionManager INSTANCE=new DatabaseConnectionManager(); }
    private volatile boolean disposed;
    private final Map<String, ConnectionGroup> activeConnections = new ConcurrentHashMap<>();

    private static final class ConnectionGroup {
        private final Map<String, Connection> connections = new java.util.HashMap<>();
        private ConnectionConfig configuration;
        private boolean registered;
        private boolean removed;
    }

    private record Identity(String url, String user, String password, boolean autoCommit) {
        static Identity of(ConnectionConfig config) { return new Identity(config.buildJdbcUrl(), config.getUser(), config.getPassword(), config.isAutoCommit()); }
    }
    public void registerConfiguration(ConnectionConfig config) {
        if(disposed) throw new IllegalStateException("Connection manager is disposed");
        ConnectionGroup group = activeConnections.computeIfAbsent(config.getId(), id -> new ConnectionGroup());
        synchronized (group) {
            if (group.configuration != null && !Identity.of(group.configuration).equals(Identity.of(config))) closeGroup(group);
            group.configuration = config.copy(); group.registered = true; group.removed = false;
        }
    }
    public void removeConfiguration(String id) {
        ConnectionGroup group = activeConnections.computeIfAbsent(id, key -> new ConnectionGroup());
        synchronized (group) { closeGroup(group); group.removed = true; group.registered = true; }
    }
    private void validateConfiguration(ConnectionGroup group, ConnectionConfig config) throws SQLException {
        if(disposed) throw new SQLException("Connection manager is disposed");
        if (group.removed) throw new SQLException("This connection was removed. Close this editor and choose an existing connection.");
        if (group.configuration != null && !Identity.of(group.configuration).equals(Identity.of(config))) {
            if (group.registered) throw new SQLException("Connection settings changed. Close and reopen this editor before accessing the database.");
            closeGroup(group);
        }
        if (!group.registered) group.configuration = config.copy();
    }
    private void closeGroup(ConnectionGroup group) {
        group.connections.values().forEach(DatabaseConnectionManager::closeQuietly);
        group.connections.clear();
    }

    public DatabaseConnectionManager() {
    }

    public static DatabaseConnectionManager getInstance() {
        var application=com.intellij.openapi.application.ApplicationManager.getApplication();
        return application==null ? Standalone.INSTANCE : application.getService(DatabaseConnectionManager.class);
    }

    public Connection getConnection(ConnectionConfig config) throws Exception {
        return getConnection(config, "browser");
    }

    public DatabaseSession createSession(ConnectionConfig config) {
        return new DatabaseSession(this, config.copy());
    }

    /** Exclusive short-lived connection for a transaction; caller closes it. */
    public Connection openConnection(ConnectionConfig config) throws Exception {
        ConnectionGroup group = activeConnections.computeIfAbsent(config.getId(), id -> new ConnectionGroup());
        synchronized (group) {
            validateConfiguration(group, config);
            Connection connection = createRawConnection(config.copy());
            try { connection.setAutoCommit(true); return connection; }
            catch (Exception e) { connection.close(); throw e; }
        }
    }

    Connection getConnection(ConnectionConfig config, String sessionId) throws Exception {
        ConnectionGroup group = activeConnections.computeIfAbsent(config.getId(), id -> new ConnectionGroup());
        synchronized (group) {
            validateConfiguration(group, config);
            Connection conn = group.connections.get(sessionId);
            if (conn != null && !conn.isClosed()) return conn;
            conn = createRawConnection(config);
            try {
                conn.setAutoCommit(config.isAutoCommit());
                group.connections.put(sessionId, conn);
                return conn;
            } catch (Exception e) {
                conn.close();
                throw e;
            }
        }
    }

    void closeSession(String connectionId, String sessionId) {
        ConnectionGroup group = activeConnections.get(connectionId);
        if (group == null) return;
        synchronized (group) { closeQuietly(group.connections.remove(sessionId)); }
    }

    private static void closeQuietly(Connection conn) {
        var application=com.intellij.openapi.application.ApplicationManager.getApplication();
        if(conn!=null && application!=null && application.isDispatchThread()) {
            application.executeOnPooledThread(() -> closeQuietly(conn)); return;
        }
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) { }
        }
    }

    public ConnectionTestResult testConnection(ConnectionConfig config) {
        long start = System.currentTimeMillis();
        Connection conn = null;
        try {
            try {
                conn = createRawConnection(config);
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : "";
                // If MySQL was selected but server at port 9001 or returned HSQL handshake ('Packet for query is too large' / 5,329,736)
                if (config.getType() == DatabaseType.MYSQL && (msg.contains("Packet for query is too large") || msg.contains("5329736") || msg.contains("5,329,736") || config.getPort() == 9001)) {
                    ConnectionConfig fallback = config.copy();
                    fallback.setType(DatabaseType.HSQLDB);
                    fallback.setHsqlMode(HsqlMode.SERVER);
                    fallback.setCustomUrl("");
                    try {
                        conn = createRawConnection(fallback);
                        config.setType(DatabaseType.HSQLDB);
                        config.setHsqlMode(HsqlMode.SERVER);
                        config.setCustomUrl("");
                        DatabaseMetaData meta = conn.getMetaData();
                        long elapsed = System.currentTimeMillis() - start;
                        return ConnectionTestResult.success(
                                meta.getDatabaseProductName() + " (Auto-detected HSQLDB on port " + fallback.getPort() + ")",
                                meta.getDatabaseProductVersion(),
                                meta.getDriverName(),
                                meta.getDriverVersion(),
                                elapsed
                        );
                    } catch (Throwable fallbackErr) {
                        throw t;
                    }
                } else if (config.getType() == DatabaseType.HSQLDB && config.getPort() == 3306) {
                    ConnectionConfig fallback = config.copy();
                    fallback.setType(DatabaseType.MYSQL);
                    fallback.setCustomUrl("");
                    try {
                        conn = createRawConnection(fallback);
                        config.setType(DatabaseType.MYSQL);
                        config.setCustomUrl("");
                        DatabaseMetaData meta = conn.getMetaData();
                        long elapsed = System.currentTimeMillis() - start;
                        return ConnectionTestResult.success(
                                meta.getDatabaseProductName() + " (Auto-detected MySQL on port " + fallback.getPort() + ")",
                                meta.getDatabaseProductVersion(),
                                meta.getDriverName(),
                                meta.getDriverVersion(),
                                elapsed
                        );
                    } catch (Throwable fallbackErr) {
                        throw t;
                    }
                }
                throw t;
            }

            DatabaseMetaData meta = conn.getMetaData();
            String dbProduct = meta.getDatabaseProductName();
            String dbVer = meta.getDatabaseProductVersion();
            String driverName = meta.getDriverName();
            String driverVer = meta.getDriverVersion();
            long elapsed = System.currentTimeMillis() - start;
            return ConnectionTestResult.success(dbProduct, dbVer, driverName, driverVer, elapsed);
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            String msg = t.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = t.getClass().getSimpleName();
            }
            if (msg.contains("Packet for query is too large") || (config.getType() == DatabaseType.MYSQL && config.getPort() == 9001)) {
                msg = "Port " + config.getPort() + " appears to be running an HSQLDB server, but 'MySQL' was selected.\n\n"
                        + "• To connect to HSQLDB: Select Database Type 'HSQLDB' (Remote Server mode, port 9001, db 'testdb', user 'SA').\n"
                        + "• To connect to MySQL: Change Port to 3306 (user 'root', db 'shop_db').";
            }
            return ConnectionTestResult.failure(msg, elapsed);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public boolean isConnected(String connectionId) {
        ConnectionGroup group = activeConnections.get(connectionId);
        if (group == null) return false;
        synchronized (group) {
            for (Connection conn : group.connections.values()) {
                try { if (!conn.isClosed()) return true; } catch (SQLException ignored) { }
            }
            return false;
        }
    }

    public void closeConnection(String connectionId) {
        ConnectionGroup group = activeConnections.get(connectionId);
        if (group != null) {
            synchronized (group) {
                group.connections.values().forEach(DatabaseConnectionManager::closeQuietly);
                group.connections.clear();
            }
        }
    }

    public void closeAll() {
        for (String id : activeConnections.keySet()) {
            closeConnection(id);
        }
    }

    @Override public void dispose() { disposed=true; closeAll(); activeConnections.clear(); }

    private Connection createRawConnection(ConnectionConfig config) throws Exception {
        if(disposed) throw new SQLException("Connection manager is disposed");
        DatabaseType type = config.getType();
        String url = config.buildJdbcUrl();
        if (url != null) {
            String lower = url.trim().toLowerCase();
            if (lower.startsWith("jdbc:hsqldb:")) {
                type = DatabaseType.HSQLDB;
                config.setType(DatabaseType.HSQLDB);
            } else if (lower.startsWith("jdbc:mysql:")) {
                type = DatabaseType.MYSQL;
                config.setType(DatabaseType.MYSQL);
            }
        }
        Driver driver = DriverRegistry.getInstance().getDriver(type);

        Properties props = new Properties();
        if (config.getUser() != null) {
            props.setProperty("user", config.getUser());
        }
        if (config.getPassword() != null) {
            props.setProperty("password", config.getPassword());
        }

        try {
            Connection conn = driver.connect(url, props);
            if (conn == null) {
                throw new SQLException("Driver returned null connection for URL: " + url);
            }
            return conn;
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (type == DatabaseType.MYSQL && (msg.contains("Packet for query is too large") || msg.contains("5329736") || msg.contains("5,329,736") || config.getPort() == 9001)) {
                ConnectionConfig fallback = config.copy();
                fallback.setType(DatabaseType.HSQLDB);
                fallback.setHsqlMode(HsqlMode.SERVER);
                fallback.setCustomUrl("");
                Driver hsqlDriver = DriverRegistry.getInstance().getDriver(DatabaseType.HSQLDB);
                String hsqlUrl = fallback.buildJdbcUrl();
                Connection conn = hsqlDriver.connect(hsqlUrl, props);
                if (conn != null) {
                    config.setType(DatabaseType.HSQLDB);
                    config.setHsqlMode(HsqlMode.SERVER);
                    return conn;
                }
            }
            throw ex;
        }
    }
}
