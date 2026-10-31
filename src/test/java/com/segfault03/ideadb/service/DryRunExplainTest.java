package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.run.SqlLiteralRenderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The explain path against real embedded engines, opened through the service's connection seam. */
@Tag("functional")
class DryRunExplainTest {
    private static final SqlLiteralRenderer.JdbcStatement SELECT = new SqlLiteralRenderer.JdbcStatement("select * from t where id = ?", List.of(1));

    private static ConnectionConfig config(DatabaseType type) { return new ConnectionConfig(type, "dry run " + type); }

    private static String h2Url() { return "jdbc:h2:mem:dry_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1"; }

    private static String hsqldbUrl() { return "jdbc:hsqldb:mem:dry_" + UUID.randomUUID().toString().replace('-', '_'); }

    /** Straight to the embedded driver: DriverManager would load every driver on the classpath, including MySQL's cleanup thread. */
    private static Connection connect(String url) throws SQLException {
        var driver = url.startsWith("jdbc:h2:") ? new org.h2.Driver() : new org.hsqldb.jdbc.JDBCDriver();
        var properties = new java.util.Properties();
        properties.setProperty("user", "sa");
        properties.setProperty("password", "");
        return driver.connect(url, properties);
    }

    private static void seed(String url, String... statements) throws SQLException {
        try (Connection connection = connect(url); var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private static final String[] TABLE = {"create table t (id int primary key, a int)", "insert into t values (1, 5)"};

    @Test void explainPrefixPerDialect() {
        assertEquals("EXPLAIN ", DryRunService.explainPrefix(DatabaseType.MYSQL, 8, 0, 15));
        assertEquals("EXPLAIN FORMAT=TREE ", DryRunService.explainPrefix(DatabaseType.MYSQL, 8, 0, 16));
        assertEquals("EXPLAIN FORMAT=TREE ", DryRunService.explainPrefix(DatabaseType.MYSQL, 8, 1, 0));
        assertEquals("EXPLAIN FORMAT=TREE ", DryRunService.explainPrefix(DatabaseType.MYSQL, 9, 0, 0));
        assertEquals("EXPLAIN ", DryRunService.explainPrefix(DatabaseType.MYSQL, 5, 7, 44));
        assertEquals("EXPLAIN (FORMAT TEXT, VERBOSE) ", DryRunService.explainPrefix(DatabaseType.POSTGRESQL, 0, 0, 0));
        assertEquals("EXPLAIN ", DryRunService.explainPrefix(DatabaseType.H2, 0, 0, 0));
        assertEquals("EXPLAIN PLAN FOR ", DryRunService.explainPrefix(DatabaseType.HSQLDB, 0, 0, 0));
    }

    @Test void h2ExplainWithBoundParameter() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var result = new DryRunService(c -> connect(url))
                .explain(config(DatabaseType.H2), SELECT, "select * from t where id = 1", new QueryExecution());
        var plan = assertInstanceOf(DryRunResult.Plan.class, result);
        assertFalse(plan.literalFallback());
        assertTrue(plan.text().toUpperCase().contains("SELECT"), plan.text());
    }

    @Test void hsqldbExplainWithBoundParameter() throws Exception {
        String url = hsqldbUrl();
        seed(url, TABLE);
        var result = new DryRunService(c -> connect(url))
                .explain(config(DatabaseType.HSQLDB), SELECT, "select * from t where id = 1", new QueryExecution());
        var plan = assertInstanceOf(DryRunResult.Plan.class, result);
        assertFalse(plan.text().isBlank());
    }

    @Test void explainDoesNotExecuteDml() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var update = new SqlLiteralRenderer.JdbcStatement("update t set a = 99 where id = ?", List.of(1));
        var result = new DryRunService(c -> connect(url))
                .explain(config(DatabaseType.H2), update, "update t set a = 99 where id = 1", new QueryExecution());
        assertInstanceOf(DryRunResult.Plan.class, result);
        try (Connection other = connect(url); var statement = other.createStatement();
             var rows = statement.executeQuery("select a from t where id = 1")) {
            assertTrue(rows.next());
            assertEquals(5, rows.getInt(1));
        }
    }

    /** Wraps a real connection so closes are counted. */
    private static Connection counting(Connection real, AtomicInteger closes) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("close")) closes.incrementAndGet();
            try { return method.invoke(real, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
    }

    @Test void connectionClosedAfterExplain() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var closes = new AtomicInteger();
        var result = new DryRunService(c -> counting(connect(url), closes))
                .explain(config(DatabaseType.H2), SELECT, "select * from t where id = 1", new QueryExecution());
        assertInstanceOf(DryRunResult.Plan.class, result);
        assertEquals(1, closes.get());
    }

    @Test void connectionClosedWhenExplainFails() throws Exception {
        String url = h2Url();
        var closes = new AtomicInteger();
        var bad = new SqlLiteralRenderer.JdbcStatement("select * from missing_table", List.of());
        var result = new DryRunService(c -> counting(connect(url), closes))
                .explain(config(DatabaseType.H2), bad, "select * from missing_table", new QueryExecution());
        assertInstanceOf(DryRunResult.Failed.class, result);
        assertEquals(1, closes.get());
    }

    @Test void cancelledBeforeStartGivesCancelled() {
        var opens = new AtomicInteger();
        var execution = new QueryExecution();
        execution.requestCancellation();
        var result = new DryRunService(c -> { opens.incrementAndGet(); throw new SQLException("must not open"); })
                .explain(config(DatabaseType.H2), SELECT, "select 1", execution);
        assertEquals(new DryRunResult.Cancelled(false), result);
        assertEquals(0, opens.get());
    }

    @Test void sqlErrorGivesFailedWithDriverMessage() throws Exception {
        String url = h2Url();
        var bad = new SqlLiteralRenderer.JdbcStatement("select * from missing_table", List.of());
        var result = new DryRunService(c -> connect(url))
                .explain(config(DatabaseType.H2), bad, "select * from missing_table", new QueryExecution());
        var failed = assertInstanceOf(DryRunResult.Failed.class, result);
        assertTrue(failed.message().toUpperCase().contains("MISSING_TABLE"), failed.message());
    }

    @Test void literalFallbackUsedWhenBoundExplainFails() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var opener = (DryRunService.ConnectionOpener) c -> {
            Connection real = connect(url);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")) throw new SQLException("cannot prepare");
                try { return method.invoke(real, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
        };
        var result = new DryRunService(opener).explain(config(DatabaseType.H2), SELECT, "select * from t where id = 1", new QueryExecution());
        var plan = assertInstanceOf(DryRunResult.Plan.class, result);
        assertTrue(plan.literalFallback());
        assertFalse(plan.text().isBlank());
    }
}
