package com.vibe.ideadb;

import com.vibe.ideadb.model.*;
import com.vibe.ideadb.service.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Live regression tests use only unique temporary schemas and clean them on failure. */
public class FunctionalRegressionTest {
    private static final DatabaseConnectionManager manager = DatabaseConnectionManager.getInstance();
    private static final DataService data = DataService.getInstance();
    private static final DdlService ddl = DdlService.getInstance();
    private static final java.util.concurrent.atomic.AtomicInteger assertions = new java.util.concurrent.atomic.AtomicInteger();
    public static void check(boolean success, String message) {
        assertions.incrementAndGet();
        if (!success) throw new AssertionError(message);
    }
    public static void sql(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement()) { statement.execute(sql); }
    }
    public static Object scalar(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new AssertionError("No scalar result");
            return result.getObject(1);
        }
    }
    private static void sessions(ConnectionConfig config, String schema) throws Exception {
        try (DatabaseSession first = manager.createSession(config); DatabaseSession second = manager.createSession(config)) {
            Connection a = first.execute(c -> c), b = second.execute(c -> c);
            check(a != b && a != manager.getConnection(config), "Consoles and browser must own separate connections");
            String namespace = config.getType() == DatabaseType.MYSQL ? "SELECT DATABASE()" : "VALUES (CURRENT_SCHEMA)";
            String other = config.getType() == DatabaseType.MYSQL ? "shop_db" : "PUBLIC";
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> left = executor.submit(() -> {
                    try {
                        for (int i = 0; i < 20; i++) first.execute(c -> {
                            QueryResult result = data.executeQuery(c, schema, namespace);
                            check(!result.hasError() && schema.equalsIgnoreCase(String.valueOf(result.getRows().get(0).get(0))), "First session namespace crossed: " + result.getError());
                            return null;
                        });
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                Future<?> right = executor.submit(() -> {
                    try {
                        for (int i = 0; i < 20; i++) second.execute(c -> {
                            QueryResult result = data.executeQuery(c, other, namespace);
                            check(!result.hasError() && other.equalsIgnoreCase(String.valueOf(result.getRows().get(0).get(0))), "Second session namespace crossed: " + result.getError());
                            return null;
                        });
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                left.get(20, TimeUnit.SECONDS); right.get(20, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
            first.close();
            check(a.isClosed() && !b.isClosed(), "Closing one console must not close another");
        }
        ConnectionConfig fresh = config.copy(); fresh.setId(UUID.randomUUID().toString());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CyclicBarrier gate = new CyclicBarrier(8);
            List<Future<Connection>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> { gate.await(); return manager.getConnection(fresh); }));
            Set<Connection> connections = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Future<Connection> result : results) connections.add(result.get(20, TimeUnit.SECONDS));
            check(connections.size() == 1, "Concurrent browser acquisition leaked connections");
        } finally { executor.shutdownNow(); manager.closeConnection(fresh.getId()); }
    }
    private static void runEngine(ConnectionConfig config) throws Exception {
        String schema = "LATTICE_FIX_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        Connection connection = manager.getConnection(config);
        ddl.createDatabase(connection, config, schema);
        try {
            sessions(config, schema);
        } finally {
            if (config.getType() == DatabaseType.MYSQL) connection.setCatalog("shop_db");
            else connection.setSchema("PUBLIC");
            ddl.dropDatabase(connection, config, schema);
        }
        System.out.println("PASS " + config.getType() + " functional regressions (temporary schema removed)");
    }
    public static void main(String[] args) throws Exception {
        try {
            ConnectionConfig mysql = new ConnectionConfig(DatabaseType.MYSQL, "regression MySQL"); mysql.setDatabaseName("shop_db");
            runEngine(mysql);
            runEngine(new ConnectionConfig(DatabaseType.HSQLDB, "regression HSQLDB"));
            System.out.println("PASS functional regression assertions: " + assertions);
        } finally { manager.closeAll(); }
    }
}
