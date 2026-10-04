package com.vibe.ideadb.service;

import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.ConnectionTestResult;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseConnectionManager {
    private static final DatabaseConnectionManager INSTANCE = new DatabaseConnectionManager();
    private final Map<String, Connection> activeConnections = new ConcurrentHashMap<>();

    private DatabaseConnectionManager() {
    }

    public static DatabaseConnectionManager getInstance() {
        return INSTANCE;
    }

    public Connection getConnection(ConnectionConfig config) throws Exception {
        Connection conn = activeConnections.get(config.getId());
        if (conn != null && !conn.isClosed()) {
            return conn;
        }

        conn = createRawConnection(config);
        conn.setAutoCommit(config.isAutoCommit());
        activeConnections.put(config.getId(), conn);
        return conn;
    }

    public ConnectionTestResult testConnection(ConnectionConfig config) {
        long start = System.currentTimeMillis();
        Connection conn = null;
        try {
            conn = createRawConnection(config);
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
        Connection conn = activeConnections.get(connectionId);
        try {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public void closeConnection(String connectionId) {
        Connection conn = activeConnections.remove(connectionId);
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException ignored) {
            }
        }
    }

    public void closeAll() {
        for (String id : activeConnections.keySet()) {
            closeConnection(id);
        }
    }

    private Connection createRawConnection(ConnectionConfig config) throws Exception {
        Driver driver = DriverRegistry.getInstance().getDriver(config.getType());
        String url = config.buildJdbcUrl();

        Properties props = new Properties();
        if (config.getUser() != null) {
            props.setProperty("user", config.getUser());
        }
        if (config.getPassword() != null) {
            props.setProperty("password", config.getPassword());
        }

        Connection conn = driver.connect(url, props);
        if (conn == null) {
            throw new SQLException("Driver returned null connection for URL: " + url);
        }
        return conn;
    }
}
