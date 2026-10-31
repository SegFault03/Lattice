package com.segfault03.ideadb.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.provider.DatabaseProvider;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.sql.run.SqlLiteralRenderer;
import com.segfault03.ideadb.sql.run.SqlStatementKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shows a statement's execution plan on its own connection, without running the statement. */
public final class DryRunService {
    public interface ConnectionOpener { Connection open(ConnectionConfig config) throws Exception; }

    private static final Pattern VERSION = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\.(\\d+)");
    private static volatile DryRunService instance;
    private final ConnectionOpener opener;

    @TestOnly public DryRunService(ConnectionOpener opener) { this.opener = opener; }

    public static DryRunService getInstance() {
        DryRunService current = instance;
        if (current == null) {
            synchronized (DryRunService.class) {
                current = instance;
                if (current == null) instance = current = new DryRunService(DatabaseConnectionManager.getInstance()::openConnection);
            }
        }
        return current;
    }

    @TestOnly public static void setInstanceForTests(DryRunService service, Disposable parent) {
        DryRunService previous = instance;
        instance = service;
        Disposer.register(parent, () -> instance = previous);
    }

    /** Never ANALYZE: it would execute the statement. */
    static String explainPrefix(DatabaseType type, int mysqlMajor, int mysqlMinor, int mysqlPatch) {
        return switch (type) {
            case MYSQL -> mysqlMajor > 8 || (mysqlMajor == 8 && (mysqlMinor > 0 || mysqlPatch >= 16)) ? "EXPLAIN FORMAT=TREE " : "EXPLAIN ";
            case POSTGRESQL -> "EXPLAIN (FORMAT TEXT, VERBOSE) ";
            case H2 -> "EXPLAIN ";
            case HSQLDB -> "EXPLAIN PLAN FOR ";
        };
    }

    /** Runs off the EDT and never throws; every failure becomes a result. */
    public DryRunResult explain(ConnectionConfig config, SqlLiteralRenderer.JdbcStatement statement, String literalSql, QueryExecution execution) {
        if (execution.isCancellationRequested()) return new DryRunResult.Cancelled(false);
        long started = System.nanoTime();
        Connection connection = null;
        try {
            connection = opener.open(config);
            var provider = DatabaseProviders.get(config.getType());
            String prefix = explainPrefix(config.getType(), connection);
            try {
                String text = explainBound(connection, provider, prefix + statement.sql(), statement, execution);
                return new DryRunResult.Plan(text, false, elapsedMs(started));
            } catch (SQLException first) {
                if (execution.isCancellationRequested()) return new DryRunResult.Cancelled(false);
                if (statement.bindings().isEmpty()) return failed(first);
                try {
                    String text = explainLiteral(connection, provider, prefix + literalSql, execution);
                    return new DryRunResult.Plan(text, true, elapsedMs(started));
                } catch (Exception retry) {
                    return execution.isCancellationRequested() ? new DryRunResult.Cancelled(false) : failed(first);
                }
            }
        } catch (Exception e) {
            return execution.isCancellationRequested() ? new DryRunResult.Cancelled(false) : failed(e);
        } finally {
            if (connection != null) try { connection.close(); } catch (Exception ignored) { }
        }
    }

    private static final Set<String> ROLLBACK_ENGINES = Set.of("innodb", "ndbcluster", "ndb");

    /**
     * Runs DML in a transaction on its own connection and always rolls it back. Deliberately not
     * {@link DataService#inTransaction}, which commits on success: nothing here ever calls commit().
     * Runs off the EDT and never throws; every failure becomes a result.
     * <p>On MySQL the caller must pass {@code SqlStatementKind.targetTableNames(sql, profile)} and
     * {@code SqlStatementKind.userFunctionCalls(sql, profile)}, both computed from the same SQL as {@code statement}.
     * Other database types ignore both lists.
     */
    public DryRunResult runAndRollBack(ConnectionConfig config, SqlStatementKind kind, SqlLiteralRenderer.JdbcStatement statement,
                                       List<SqlStatementKind.QualifiedName> targetTables, List<String> userFunctionCalls, QueryExecution execution) {
        if (kind != SqlStatementKind.DML) return new DryRunResult.Failed(SqlStatementKind.rollbackRefusal(kind));
        if (execution.isCancellationRequested()) return new DryRunResult.Cancelled(false);
        if (config.getType() == DatabaseType.MYSQL) {
            String refusal = mysqlStatementRefusal(targetTables, userFunctionCalls);
            if (refusal != null) return new DryRunResult.Failed(refusal);
        }
        long started = System.nanoTime();
        Connection connection = null;
        try {
            try {
                connection = opener.open(config);
                if (config.getType() == DatabaseType.MYSQL) {
                    String refusal = mysqlRollbackRefusal(connection, targetTables);
                    if (refusal != null) return new DryRunResult.Failed(refusal);
                    try (Statement set = connection.createStatement()) { set.execute("SET SESSION innodb_lock_wait_timeout = 60"); }
                }
                connection.setAutoCommit(false);
            } catch (Exception beforeTransaction) {
                return execution.isCancellationRequested() ? new DryRunResult.Cancelled(false) : failed(beforeTransaction);
            }
            return runInTransaction(config, connection, statement, execution, started);
        } finally {
            if (connection != null) try { connection.close(); } catch (Exception ignored) { }
        }
    }

    /** Auto-commit is already off. The rollback in the finally is the only way out of the transaction. */
    private DryRunResult runInTransaction(ConnectionConfig config, Connection connection, SqlLiteralRenderer.JdbcStatement statement,
                                          QueryExecution execution, long started) {
        int rows = 0;
        List<String> warnings = new ArrayList<>();
        Exception failure = null;
        boolean rolledBack = false;
        Exception rollbackFailure = null;
        try {
            try (PreparedStatement prepared = connection.prepareStatement(statement.sql())) {
                for (int i = 0; i < statement.bindings().size(); i++) prepared.setObject(i + 1, statement.bindings().get(i));
                prepared.setQueryTimeout(DataService.QueryOptions.defaults().timeoutSeconds());
                var provider = DatabaseProviders.get(config.getType());
                try {
                    execution.attach(prepared, () -> provider.cancelStatement(prepared));
                    execution.markSqlStarted();
                    execution.checkCancelled();
                    rows = prepared.executeUpdate();
                    collectWarnings(prepared, warnings);
                } finally {
                    execution.detach();
                }
            }
        } catch (Exception e) {
            failure = e;
        } finally {
            try {
                connection.rollback();
                rolledBack = true;
            } catch (Exception e) {
                rollbackFailure = e;
            }
        }
        if (!rolledBack) {
            return new DryRunResult.OutcomeUnknown(config.getName(), "The changes may not have been rolled back: " + describe(rollbackFailure)
                    + (failure == null ? "" : " (the statement itself failed with: " + describe(failure) + ")"));
        }
        if (failure != null) {
            return execution.isCancellationRequested() ? new DryRunResult.Cancelled(true) : failed(failure);
        }
        return new DryRunResult.RolledBack(rows, warnings, elapsedMs(started));
    }

    private static void collectWarnings(Statement statement, List<String> into) {
        try {
            for (SQLWarning w = statement.getWarnings(); w != null; w = w.getNextWarning()) into.add(w.getMessage());
        } catch (SQLException ignored) { }
    }

    /** Refusals that need no connection. A stored function can write to any table, and a quoted name with a dot cannot be split safely. */
    private static String mysqlStatementRefusal(List<SqlStatementKind.QualifiedName> targetTables, List<String> userFunctionCalls) {
        if (targetTables.isEmpty()) return "Cannot confirm which tables this statement changes.";
        for (var table : targetTables) {
            if (table.name().contains(".") || (table.schema() != null && table.schema().contains("."))) return "Cannot confirm that " + table + " can roll back.";
        }
        if (!userFunctionCalls.isEmpty()) return userFunctionCalls.get(0) + " is not a built-in function; cannot confirm it can roll back.";
        return null;
    }

    /** Fail closed: null only when every table is known to be on an engine that can roll back and has no triggers. */
    private static String mysqlRollbackRefusal(Connection connection, List<SqlStatementKind.QualifiedName> targetTables) throws SQLException {
        for (var qualified : targetTables) {
            String table = qualified.toString();
            String schema = qualified.schema() != null ? qualified.schema() : connection.getCatalog();
            String name = qualified.name();
            String engine = null;
            try (PreparedStatement lookup = connection.prepareStatement("select ENGINE from information_schema.TABLES where TABLE_SCHEMA = ? and TABLE_NAME = ?")) {
                lookup.setString(1, schema);
                lookup.setString(2, name);
                try (ResultSet found = lookup.executeQuery()) {
                    if (found.next()) engine = found.getString(1);
                }
            }
            if (engine == null || schema == null) return "Cannot confirm that " + table + " can roll back.";
            if (!ROLLBACK_ENGINES.contains(engine.toLowerCase(Locale.ROOT))) return table + " uses the " + engine + " engine, which cannot roll back.";
            // A trigger can write to a table on another engine, and that write survives the rollback.
            try (PreparedStatement triggers = connection.prepareStatement("select 1 from information_schema.TRIGGERS where EVENT_OBJECT_SCHEMA = ? and EVENT_OBJECT_TABLE = ? limit 1")) {
                triggers.setString(1, schema);
                triggers.setString(2, name);
                try (ResultSet found = triggers.executeQuery()) {
                    if (found.next()) return table + " has triggers; cannot confirm they can roll back.";
                }
            }
        }
        return null;
    }

    private static String describe(Exception e) { return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(); }

    private static String explainPrefix(DatabaseType type, Connection connection) {
        if (type != DatabaseType.MYSQL) return explainPrefix(type, 0, 0, 0);
        try {
            Matcher m = VERSION.matcher(String.valueOf(connection.getMetaData().getDatabaseProductVersion()));
            if (m.find()) return explainPrefix(type, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (Exception | LinkageError ignored) { }
        return "EXPLAIN ";
    }

    private static String explainBound(Connection connection, DatabaseProvider provider, String sql, SqlLiteralRenderer.JdbcStatement statement, QueryExecution execution) throws SQLException {
        try (PreparedStatement prepared = connection.prepareStatement(sql)) {
            for (int i = 0; i < statement.bindings().size(); i++) prepared.setObject(i + 1, statement.bindings().get(i));
            return run(prepared, provider, execution, prepared::executeQuery);
        }
    }

    private static String explainLiteral(Connection connection, DatabaseProvider provider, String sql, QueryExecution execution) throws SQLException {
        try (Statement plain = connection.createStatement()) {
            return run(plain, provider, execution, () -> plain.executeQuery(sql));
        }
    }

    private interface Query { ResultSet execute() throws SQLException; }

    private static String run(Statement statement, DatabaseProvider provider, QueryExecution execution, Query query) throws SQLException {
        statement.setQueryTimeout(DataService.QueryOptions.defaults().timeoutSeconds());
        try {
            execution.attach(statement, () -> provider.cancelStatement(statement));
            execution.markSqlStarted();
            execution.markQueryOnly();
            execution.checkCancelled();
            try (ResultSet rows = query.execute()) { return planText(rows); }
        } finally {
            execution.detach();
        }
    }

    private static String planText(ResultSet rows) throws SQLException {
        int columns = rows.getMetaData().getColumnCount();
        StringBuilder text = new StringBuilder();
        while (rows.next()) {
            if (!text.isEmpty()) text.append('\n');
            for (int c = 1; c <= columns; c++) {
                if (c > 1) text.append('\t');
                String cell = rows.getString(c);
                text.append(cell == null ? "NULL" : cell);
            }
        }
        return text.toString();
    }

    private static long elapsedMs(long startedNanos) { return (System.nanoTime() - startedNanos) / 1_000_000; }

    private static DryRunResult failed(@NotNull Exception e) {
        return new DryRunResult.Failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }
}
