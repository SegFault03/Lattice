package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.run.SqlLiteralRenderer;
import com.segfault03.ideadb.sql.run.SqlStatementKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Run and Roll Back against real embedded engines and recording proxies; the statement must never be committed. */
@Tag("functional")
class DryRunRollbackTest {
    private static final SqlStatementKind DML = SqlStatementKind.DML;
    private static final SqlLiteralRenderer.JdbcStatement UPDATE = new SqlLiteralRenderer.JdbcStatement("update t set a = 99 where id = ?", List.of(1));
    private static final String[] TABLE = {"create table t (id int primary key, a int)", "insert into t values (1, 5)"};

    private static SqlStatementKind.QualifiedName name(String table) { return new SqlStatementKind.QualifiedName(null, table); }

    private static final List<SqlStatementKind.QualifiedName> T = List.of(name("t"));
    private static final List<String> NO_CALLS = List.of();

    private static ConnectionConfig config(DatabaseType type) { return new ConnectionConfig(type, "dry run " + type); }

    private static String h2Url() { return "jdbc:h2:mem:rb_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1"; }

    private static String hsqldbUrl() { return "jdbc:hsqldb:mem:rb_" + UUID.randomUUID().toString().replace('-', '_') + ";shutdown=true"; }

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

    private static int readA(String url) throws SQLException {
        try (Connection other = connect(url); var statement = other.createStatement();
             var rows = statement.executeQuery("select a from t where id = 1")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    /** Hook for a proxied call; return null to fall through to the delegate. */
    private interface Hook { Object intercept(String method, Object[] args) throws Throwable; }

    private static final Object PASS = new Object();

    @SuppressWarnings("unchecked")
    private static <T> T delegating(Class<T> type, T real, Hook hook) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object handled = hook.intercept(method.getName(), args);
            if (handled != PASS) return handled;
            try { return method.invoke(real, args); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    /** A real connection that records the calls the safety argument depends on. */
    private static final class Spy {
        final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger(), closes = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final List<String> autoCommits = new ArrayList<>();
        volatile boolean failRollback;
        volatile QueryExecution cancelDuringUpdate;

        Connection wrap(Connection real) {
            return delegating(Connection.class, real, (name, args) -> switch (name) {
                case "commit" -> { commits.incrementAndGet(); yield PASS; }
                case "rollback" -> {
                    rollbacks.incrementAndGet();
                    if (failRollback) throw new SQLException("link down");
                    yield PASS;
                }
                case "close" -> { closes.incrementAndGet(); yield PASS; }
                case "setAutoCommit" -> { autoCommits.add(String.valueOf(args[0])); yield PASS; }
                case "prepareStatement" -> wrapPrepared(real.prepareStatement((String) args[0]));
                default -> PASS;
            });
        }

        private PreparedStatement wrapPrepared(PreparedStatement real) {
            return delegating(PreparedStatement.class, real, (name, args) -> switch (name) {
                case "cancel" -> { cancels.incrementAndGet(); yield null; }
                case "executeUpdate" -> {
                    if (cancelDuringUpdate == null) yield PASS;
                    real.executeUpdate(); // the write really happened; only the rollback can undo it
                    cancelDuringUpdate.cancel();
                    throw new SQLException("statement cancelled");
                }
                default -> PASS;
            });
        }
    }

    @Test void h2UpdateReportsRowsAndLeavesTableUnchanged() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var result = new DryRunService(c -> connect(url))
                .runAndRollBack(config(DatabaseType.H2), DML, UPDATE, T, NO_CALLS, new QueryExecution());
        var rolledBack = assertInstanceOf(DryRunResult.RolledBack.class, result);
        assertEquals(1, rolledBack.rows());
        assertEquals(5, readA(url));
    }

    @Test void hsqldbDeleteRolledBack() throws Exception {
        String url = hsqldbUrl();
        var delete = new SqlLiteralRenderer.JdbcStatement("delete from t where id = ?", List.of(1));
        try (Connection keeper = connect(url)) { // the last close shuts the in-memory database down, ending its timer thread
            try (var statement = keeper.createStatement()) { for (String sql : TABLE) statement.execute(sql); }
            var result = new DryRunService(c -> connect(url))
                    .runAndRollBack(config(DatabaseType.HSQLDB), DML, delete, T, NO_CALLS, new QueryExecution());
            assertEquals(1, assertInstanceOf(DryRunResult.RolledBack.class, result).rows());
            assertEquals(5, readA(url));
        }
    }

    @Test void failingStatementStillRollsBackAndCloses() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var spy = new Spy();
        var bad = new SqlLiteralRenderer.JdbcStatement("update missing_table set a = 1", List.of());
        var result = new DryRunService(c -> spy.wrap(connect(url)))
                .runAndRollBack(config(DatabaseType.H2), DML, bad, List.of(name("missing_table")), NO_CALLS, new QueryExecution());
        var failed = assertInstanceOf(DryRunResult.Failed.class, result);
        assertTrue(failed.message().toUpperCase().contains("MISSING_TABLE"), failed.message());
        assertEquals(1, spy.rollbacks.get());
        assertEquals(1, spy.closes.get());
        assertEquals(0, spy.commits.get());
    }

    @Test void neverCallsCommit() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var spy = new Spy();
        var result = new DryRunService(c -> spy.wrap(connect(url)))
                .runAndRollBack(config(DatabaseType.H2), DML, UPDATE, T, NO_CALLS, new QueryExecution());
        assertInstanceOf(DryRunResult.RolledBack.class, result);
        assertEquals(0, spy.commits.get(), "commit() must never be called");
        assertEquals(List.of("false"), spy.autoCommits, "auto-commit is switched off once and never restored");
        assertEquals(1, spy.rollbacks.get());
        assertEquals(1, spy.closes.get());
        assertEquals(5, readA(url));
    }

    @Test void rollbackFailureGivesOutcomeUnknown() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var spy = new Spy();
        spy.failRollback = true;
        var result = new DryRunService(c -> spy.wrap(connect(url)))
                .runAndRollBack(config(DatabaseType.H2), DML, UPDATE, T, NO_CALLS, new QueryExecution());
        var unknown = assertInstanceOf(DryRunResult.OutcomeUnknown.class, result);
        assertEquals("dry run H2", unknown.connectionName());
        assertTrue(unknown.message().contains("link down"), unknown.message());
        assertEquals(1, spy.closes.get());
        assertEquals(0, spy.commits.get());
        assertEquals(List.of("false"), spy.autoCommits);
    }

    @Test void cancelDuringRunGivesCancelledRolledBack() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var spy = new Spy();
        var execution = new QueryExecution();
        spy.cancelDuringUpdate = execution;
        var result = new DryRunService(c -> spy.wrap(connect(url)))
                .runAndRollBack(config(DatabaseType.H2), DML, UPDATE, T, NO_CALLS, execution);
        assertEquals(new DryRunResult.Cancelled(true), result);
        assertEquals(1, spy.rollbacks.get());
        assertEquals(1, spy.closes.get());
        assertEquals(0, spy.commits.get());
        assertTrue(spy.cancels.get() > 0, "cancel goes through the provider to the attached statement");
        assertFalse(execution.isRunning(), "the statement is detached");
        assertEquals(5, readA(url));
    }

    @Test void cancelWithFailedRollbackGivesOutcomeUnknown() throws Exception {
        String url = h2Url();
        seed(url, TABLE);
        var spy = new Spy();
        spy.failRollback = true;
        var execution = new QueryExecution();
        spy.cancelDuringUpdate = execution;
        var result = new DryRunService(c -> spy.wrap(connect(url)))
                .runAndRollBack(config(DatabaseType.H2), DML, UPDATE, T, NO_CALLS, execution);
        assertInstanceOf(DryRunResult.OutcomeUnknown.class, result);
        assertEquals(1, spy.closes.get());
    }

    @Test void cancelledBeforeStartDoesNotConnect() {
        var opens = new AtomicInteger();
        var execution = new QueryExecution();
        execution.requestCancellation();
        var result = new DryRunService(c -> { opens.incrementAndGet(); throw new SQLException("must not open"); })
                .runAndRollBack(config(DatabaseType.H2), DML, UPDATE, T, NO_CALLS, execution);
        assertEquals(new DryRunResult.Cancelled(false), result);
        assertEquals(0, opens.get());
    }

    @Test void nonDmlRefusedWithoutConnecting() {
        var opens = new AtomicInteger();
        var service = new DryRunService(c -> { opens.incrementAndGet(); throw new SQLException("must not open"); });
        for (SqlStatementKind kind : new SqlStatementKind[]{SqlStatementKind.QUERY, SqlStatementKind.OTHER}) {
            var result = service.runAndRollBack(config(DatabaseType.H2), kind, UPDATE, T, NO_CALLS, new QueryExecution());
            assertEquals(new DryRunResult.Failed(SqlStatementKind.rollbackRefusal(kind)), result);
        }
        assertEquals(0, opens.get());
    }

    @Test void connectFailureGivesFailedNothingRan() {
        var result = new DryRunService(c -> { throw new SQLException("no route to host"); })
                .runAndRollBack(config(DatabaseType.H2), DML, UPDATE, T, NO_CALLS, new QueryExecution());
        assertEquals(new DryRunResult.Failed("no route to host"), result);
    }

    // ---- MySQL, answered by a recording fake ----

    /** Answers the information_schema engine query from {@code engines} (table name to engine; absent means no row). */
    private static final class FakeMysql {
        final java.util.Map<String, String> engines = new java.util.HashMap<>();
        final List<String> executed = new ArrayList<>();
        final java.util.Set<String> tablesWithTriggers = new java.util.HashSet<>();
        final List<String> engineLookups = new ArrayList<>();
        final AtomicInteger updates = new AtomicInteger(), rollbacks = new AtomicInteger(), closes = new AtomicInteger(), commits = new AtomicInteger();

        Connection connection() {
            return stub(Connection.class, (name, args) -> switch (name) {
                case "getCatalog" -> "shop";
                case "rollback" -> { rollbacks.incrementAndGet(); yield null; }
                case "commit" -> { commits.incrementAndGet(); yield null; }
                case "close" -> { closes.incrementAndGet(); yield null; }
                case "createStatement" -> stub(Statement.class, (n, a) -> {
                    if (n.equals("execute")) executed.add((String) a[0]);
                    return null;
                });
                case "prepareStatement" -> ((String) args[0]).contains("information_schema.TRIGGERS") ? triggers()
                        : ((String) args[0]).contains("information_schema") ? lookup() : dml();
                default -> null;
            });
        }

        private PreparedStatement lookup() {
            var params = new ArrayList<String>();
            return stub(PreparedStatement.class, (name, args) -> switch (name) {
                case "setString" -> { params.add((String) args[1]); yield null; }
                case "executeQuery" -> {
                    engineLookups.add(String.join(".", params));
                    String engine = engines.get(params.get(1));
                    yield stub(ResultSet.class, new Hook() {
                        boolean served;
                        public Object intercept(String n, Object[] a) {
                            if (n.equals("next")) { boolean has = !served && engines.containsKey(params.get(1)); served = true; return has; }
                            if (n.equals("getString")) return engine;
                            return null;
                        }
                    });
                }
                default -> null;
            });
        }

        private PreparedStatement triggers() {
            var params = new ArrayList<String>();
            return stub(PreparedStatement.class, (name, args) -> switch (name) {
                case "setString" -> { params.add((String) args[1]); yield null; }
                case "executeQuery" -> {
                    boolean has = tablesWithTriggers.contains(params.get(1));
                    yield stub(ResultSet.class, new Hook() {
                        boolean served;
                        public Object intercept(String n, Object[] a) {
                            if (n.equals("next")) { boolean next = !served && has; served = true; return next; }
                            return null;
                        }
                    });
                }
                default -> null;
            });
        }

        private PreparedStatement dml() {
            return stub(PreparedStatement.class, (name, args) -> {
                if (name.equals("executeUpdate")) { updates.incrementAndGet(); return 3; }
                return null;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Hook hook) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object value = hook.intercept(method.getName(), args);
            if (value != null) return value;
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            return null;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private DryRunResult runMysql(FakeMysql fake, List<SqlStatementKind.QualifiedName> tables) {
        return runMysql(fake, tables, NO_CALLS);
    }

    private DryRunResult runMysql(FakeMysql fake, List<SqlStatementKind.QualifiedName> tables, List<String> calls) {
        return new DryRunService(c -> fake.connection())
                .runAndRollBack(config(DatabaseType.MYSQL), DML, UPDATE, tables, calls, new QueryExecution());
    }

    @Test void mysqlNonTransactionalEngineRefusedBeforeExecute() {
        var fake = new FakeMysql();
        fake.engines.put("t", "MyISAM");
        var result = runMysql(fake, T);
        assertEquals(new DryRunResult.Failed("t uses the MyISAM engine, which cannot roll back."), result);
        assertEquals(0, fake.updates.get());
        assertTrue(fake.executed.isEmpty(), "not even the SET ran: " + fake.executed);
        assertEquals(1, fake.closes.get());
        assertEquals(0, fake.commits.get());
    }

    @Test void mysqlUnknownTableOrViewRefused() {
        var fake = new FakeMysql(); // no row for t, as for a view or a missing table
        var result = runMysql(fake, T);
        assertEquals(new DryRunResult.Failed("Cannot confirm that t can roll back."), result);
        assertEquals(0, fake.updates.get());
        assertEquals(1, fake.closes.get());
    }

    @Test void mysqlEmptyTargetTablesRefused() {
        var fake = new FakeMysql();
        var result = runMysql(fake, List.of());
        assertEquals(new DryRunResult.Failed("Cannot confirm which tables this statement changes."), result);
        assertEquals(0, fake.updates.get());
        assertTrue(fake.executed.isEmpty());
    }

    @Test void mysqlSetsLockWaitTimeout() {
        var fake = new FakeMysql();
        fake.engines.put("t", "innodb");
        fake.engines.put("u", "NDB");
        var result = runMysql(fake, List.of(name("t"), new SqlStatementKind.QualifiedName("audit", "u")));
        assertEquals(3, assertInstanceOf(DryRunResult.RolledBack.class, result).rows());
        assertEquals(List.of("SET SESSION innodb_lock_wait_timeout = 60"), fake.executed);
        assertEquals(List.of("shop.t", "audit.u"), fake.engineLookups, "unqualified tables use the connection's catalog");
        assertEquals(1, fake.updates.get());
        assertEquals(1, fake.rollbacks.get());
        assertEquals(1, fake.closes.get());
        assertEquals(0, fake.commits.get());
    }

    @Test void mysqlTableWithTriggersRefused() {
        var fake = new FakeMysql();
        fake.engines.put("t", "InnoDB");
        fake.tablesWithTriggers.add("t");
        var result = runMysql(fake, T);
        assertEquals(new DryRunResult.Failed("t has triggers; cannot confirm they can roll back."), result);
        assertEquals(0, fake.updates.get());
        assertTrue(fake.executed.isEmpty(), "not even the SET ran: " + fake.executed);
        assertEquals(1, fake.closes.get());
        assertEquals(0, fake.commits.get());
    }

    @Test void mysqlUserFunctionRefused() {
        var fake = new FakeMysql();
        fake.engines.put("t", "InnoDB");
        var result = runMysql(fake, T, List.of("f"));
        assertEquals(new DryRunResult.Failed("f is not a built-in function; cannot confirm it can roll back."), result);
        assertEquals(0, fake.updates.get());
        assertTrue(fake.executed.isEmpty());
    }

    @Test void mysqlDottedNameRefused() {
        var fake = new FakeMysql();
        fake.engines.put("a.b", "InnoDB");
        fake.engines.put("b", "InnoDB");
        var result = runMysql(fake, List.of(name("a.b")));
        assertEquals(new DryRunResult.Failed("Cannot confirm that a.b can roll back."), result);
        assertEquals(0, fake.updates.get());
        assertTrue(fake.executed.isEmpty());
        assertTrue(fake.engineLookups.isEmpty(), "no guess was made at a split");
    }
}
