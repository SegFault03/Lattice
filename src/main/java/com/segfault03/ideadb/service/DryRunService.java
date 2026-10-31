package com.segfault03.ideadb.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.run.SqlLiteralRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
            String prefix = explainPrefix(config.getType(), connection);
            try {
                String text = explainBound(connection, prefix + statement.sql(), statement, execution);
                return new DryRunResult.Plan(text, false, elapsedMs(started));
            } catch (SQLException first) {
                if (execution.isCancellationRequested()) return new DryRunResult.Cancelled(false);
                if (statement.bindings().isEmpty()) return failed(first);
                try {
                    String text = explainLiteral(connection, prefix + literalSql, execution);
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

    private static String explainPrefix(DatabaseType type, Connection connection) {
        if (type != DatabaseType.MYSQL) return explainPrefix(type, 0, 0, 0);
        try {
            Matcher m = VERSION.matcher(String.valueOf(connection.getMetaData().getDatabaseProductVersion()));
            if (m.find()) return explainPrefix(type, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (Exception | LinkageError ignored) { }
        return "EXPLAIN ";
    }

    private static String explainBound(Connection connection, String sql, SqlLiteralRenderer.JdbcStatement statement, QueryExecution execution) throws SQLException {
        try (PreparedStatement prepared = connection.prepareStatement(sql)) {
            for (int i = 0; i < statement.bindings().size(); i++) prepared.setObject(i + 1, statement.bindings().get(i));
            return run(prepared, execution, prepared::executeQuery);
        }
    }

    private static String explainLiteral(Connection connection, String sql, QueryExecution execution) throws SQLException {
        try (Statement plain = connection.createStatement()) {
            return run(plain, execution, () -> plain.executeQuery(sql));
        }
    }

    private interface Query { ResultSet execute() throws SQLException; }

    private static String run(Statement statement, QueryExecution execution, Query query) throws SQLException {
        statement.setQueryTimeout(DataService.QueryOptions.defaults().timeoutSeconds());
        execution.attach(statement);
        try {
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
