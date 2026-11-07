package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.run.SqlLiteralRenderer.JdbcStatement;
import com.segfault03.ideadb.sql.run.SqlStatementKind;
import com.segfault03.ideadb.sql.run.SqlStatementKind.QualifiedName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Dry-run against the live MySQL and PostgreSQL fixtures (docs/CONTRIBUTING.md); each test owns its table. */
@Tag("integration")
class DryRunLiveIntegrationTest {
    private static final String UPDATE = "update %s set a = ? where id = ?";

    @Test @Tag("postgres")
    void postgresExplainWithBoundParameter() throws Exception {
        String table = table();
        try (Connection setup = postgres()) {
            seed(setup, table);
            var result = new DryRunService(config -> postgres())
                .explain(postgresConfig(), new JdbcStatement("select * from " + table + " where id = ?", List.of(1)),
                    "select * from " + table + " where id = 1", new QueryExecution());
            var plan = assertInstanceOf(DryRunResult.Plan.class, result);
            assertFalse(plan.literalFallback(), "PostgreSQL accepts bound parameters in EXPLAIN");
            assertTrue(plan.text().contains(table), plan.text());
        } finally { drop(postgres(), table); }
    }

    @Test @Tag("postgres")
    void postgresUpdateRolledBack() throws Exception {
        String table = table();
        try (Connection setup = postgres()) {
            seed(setup, table);
            var result = new DryRunService(config -> postgres()).runAndRollBack(postgresConfig(), SqlStatementKind.DML,
                new JdbcStatement(UPDATE.formatted(table), List.of(99, 1)), List.of(new QualifiedName(null, table)), List.of(), new QueryExecution());
            assertEquals(1, assertInstanceOf(DryRunResult.RolledBack.class, result).rows());
            assertEquals(1, valueOf(setup, table));
        } finally { drop(postgres(), table); }
    }

    @Test
    void mysqlExplainWithServerPreparedStatements() throws Exception {
        String table = table();
        try (Connection setup = mysql("")) {
            seed(setup, table);
            var result = new DryRunService(config -> mysql("&useServerPrepStmts=true"))
                .explain(mysqlConfig(), new JdbcStatement("select * from " + table + " where id = ?", List.of(1)),
                    "select * from " + table + " where id = 1", new QueryExecution());
            assertInstanceOf(DryRunResult.Plan.class, result);
        } finally { drop(mysql(""), table); }
    }

    @Test
    void mysqlMyIsamRefused() throws Exception {
        String table = table();
        try (Connection setup = mysql("")) {
            try (Statement statement = setup.createStatement()) {
                statement.execute("create table " + table + " (id int primary key, a int) engine=MyISAM");
                statement.execute("insert into " + table + " values (1, 1)");
            }
            var result = new DryRunService(config -> mysql("")).runAndRollBack(mysqlConfig(), SqlStatementKind.DML,
                new JdbcStatement(UPDATE.formatted(table), List.of(99, 1)), List.of(new QualifiedName(null, table)), List.of(), new QueryExecution());
            assertEquals(table + " uses the MyISAM engine, which cannot roll back.", assertInstanceOf(DryRunResult.Failed.class, result).message());
            assertEquals(1, valueOf(setup, table));
        } finally { drop(mysql(""), table); }
    }

    @Test
    void mysqlLockWaitTimeoutApplied() throws Exception {
        String table = table();
        var seen = new AtomicReference<Integer>();
        try (Connection setup = mysql("")) {
            seed(setup, table);
            var result = new DryRunService(config -> readingLockWaitOnClose(mysql(""), seen)).runAndRollBack(mysqlConfig(), SqlStatementKind.DML,
                new JdbcStatement(UPDATE.formatted(table), List.of(99, 1)), List.of(new QualifiedName(null, table)), List.of(), new QueryExecution());
            assertInstanceOf(DryRunResult.RolledBack.class, result);
            assertEquals(DataService.QueryOptions.defaults().timeoutSeconds(), seen.get());
            assertEquals(1, valueOf(setup, table));
        } finally { drop(mysql(""), table); }
    }

    /** Reads the session's lock wait timeout just before the service closes its connection. */
    private static Connection readingLockWaitOnClose(Connection real, AtomicReference<Integer> seen) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("close") && !real.isClosed()) {
                try (Statement statement = real.createStatement(); ResultSet rows = statement.executeQuery("select @@session.innodb_lock_wait_timeout")) {
                    rows.next(); seen.set(rows.getInt(1));
                }
            }
            try { return method.invoke(real, args); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    private static String table() { return "dry_run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private static void seed(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table " + table + " (id int primary key, a int)");
            statement.execute("insert into " + table + " values (1, 1)");
        }
    }

    private static int valueOf(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("select a from " + table + " where id = 1")) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }

    private static void drop(Connection connection, String table) throws Exception {
        try (connection; Statement statement = connection.createStatement()) { statement.execute("drop table if exists " + table); }
    }

    private static ConnectionConfig postgresConfig() {
        var config = new ConnectionConfig(DatabaseType.POSTGRESQL, "PostgreSQL dry run");
        config.setPort(Integer.getInteger("lattice.test.postgres.port", 5432));
        config.setPassword(System.getProperty("lattice.test.postgres.password", "lattice-test"));
        return config;
    }

    private static ConnectionConfig mysqlConfig() {
        var config = new ConnectionConfig(DatabaseType.MYSQL, "MySQL dry run");
        config.setPort(Integer.getInteger("lattice.test.mysql.port", 3306));
        config.setDatabaseName("shop_db");
        return config;
    }

    // Driver classes directly: DriverManager would load every bundled driver and trip the thread-leak check.
    private static Connection postgres() throws Exception {
        var properties = new Properties();
        properties.setProperty("user", "postgres");
        properties.setProperty("password", System.getProperty("lattice.test.postgres.password", "lattice-test"));
        return new org.postgresql.Driver().connect("jdbc:postgresql://localhost:" + Integer.getInteger("lattice.test.postgres.port", 5432) + "/postgres", properties);
    }

    private static Connection mysql(String options) throws Exception {
        var properties = new Properties();
        properties.setProperty("user", "root");
        properties.setProperty("password", "");
        return new com.mysql.cj.jdbc.Driver().connect("jdbc:mysql://127.0.0.1:" + Integer.getInteger("lattice.test.mysql.port", 3306)
            + "/shop_db?useSSL=false&allowPublicKeyRetrieval=true" + options, properties);
    }
}
