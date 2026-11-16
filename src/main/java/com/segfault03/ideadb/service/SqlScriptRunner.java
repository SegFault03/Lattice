package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.sql.run.SqlScript;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a script's statements in order on a connection of its own, so a console's session and transaction are never
 * touched. Statements that return rows are read only to count them. Call {@link #run} off the EDT; {@link #cancel}
 * may be called from any thread.
 */
public final class SqlScriptRunner {
    /** Rows of one result set counted before the count is reported as "at least". */
    static final int ROW_COUNT_LIMIT = 100_000;

    public enum Status { OK, FAILED, NOT_RUN, CANCELLED, ROLLED_BACK }

    /** How one statement ended: its status, what it did ("3 rows", the server's error) and how long it took. */
    public record Outcome(@NotNull Status status, @NotNull String result, long millis) {}

    /** {@code stopOnError} is ignored with {@code singleTransaction}: the first error then rolls everything back. */
    public record Options(boolean stopOnError, boolean singleTransaction) {}

    public interface Listener {
        default void started(@NotNull SqlScript.Statement statement) {}
        void finished(@NotNull SqlScript.Statement statement, @NotNull Outcome outcome);
        /** Statements that ran but whose work a rollback undid; called once, after the run. */
        default void rolledBack(@NotNull List<SqlScript.Statement> statements) {}
    }

    /** What the whole run did. */
    public record Summary(int succeeded, int failed, int notRun, boolean cancelled, boolean rolledBack, long millis,
                          @Nullable String connectionError) {
        public boolean changedSomething() { return succeeded > 0 && !rolledBack; }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Statement> current = new AtomicReference<>();

    /** Stops the run: the running statement is cancelled and the rest are not run. */
    public void cancel() {
        cancelled.set(true);
        Statement running = current.get();
        if (running == null) return;
        try { running.cancel(); } catch (SQLException ignored) { /* the statement may have just finished */ }
    }

    public boolean isCancelled() { return cancelled.get(); }

    public @NotNull Summary run(@NotNull ConnectionConfig config, @Nullable String namespace, @NotNull List<SqlScript.Statement> statements,
                                @NotNull Options options, @NotNull Listener listener) {
        return run(() -> DatabaseConnectionManager.getInstance().openConnection(config), config, namespace, statements, options, listener);
    }

    interface ConnectionSource { Connection open() throws Exception; }

    @NotNull Summary run(@NotNull ConnectionSource source, @NotNull ConnectionConfig config, @Nullable String namespace,
                         @NotNull List<SqlScript.Statement> statements, @NotNull Options options, @NotNull Listener listener) {
        long began = System.nanoTime();
        int succeeded = 0, failed = 0, notRun = 0;
        boolean rolledBack = false;
        boolean stopOnError = options.stopOnError() || options.singleTransaction();
        try (Connection connection = source.open()) {
            var provider = DatabaseProviders.get(config.getType());
            if (namespace != null && !namespace.isBlank()) provider.selectNamespace(connection, namespace);
            connection.setAutoCommit(!options.singleTransaction());
            boolean stopped = false;
            for (int i = 0; i < statements.size(); i++) {
                SqlScript.Statement statement = statements.get(i);
                if (stopped || cancelled.get()) {
                    listener.finished(statement, new Outcome(cancelled.get() && !stopped ? Status.CANCELLED : Status.NOT_RUN, cancelled.get() && !stopped ? "cancelled" : "not run", 0));
                    notRun++;
                    continue;
                }
                listener.started(statement);
                long start = System.nanoTime();
                try (Statement jdbc = connection.createStatement()) {
                    current.set(jdbc);
                    if (cancelled.get()) throw new SQLException("Cancelled");
                    String result = describe(jdbc, jdbc.execute(statement.sql()));
                    listener.finished(statement, new Outcome(Status.OK, result, elapsed(start)));
                    succeeded++;
                } catch (SQLException failure) {
                    boolean byUser = cancelled.get();
                    listener.finished(statement, new Outcome(byUser ? Status.CANCELLED : Status.FAILED,
                            byUser ? "cancelled" : message(failure), elapsed(start)));
                    if (byUser) notRun++; else failed++;
                    if (stopOnError || byUser) stopped = true;
                } finally {
                    current.set(null);
                }
            }
            if (options.singleTransaction()) {
                if (failed == 0 && !cancelled.get()) connection.commit();
                else {
                    connection.rollback();
                    rolledBack = succeeded > 0;
                    if (rolledBack) listener.rolledBack(statements.subList(0, Math.min(statements.size(), succeeded)));
                }
            }
        } catch (Exception failure) {
            String reason = failure instanceof SQLException sql ? message(sql) : String.valueOf(failure.getMessage());
            return new Summary(succeeded, failed, statements.size() - succeeded - failed, cancelled.get(), rolledBack, elapsed(began), reason);
        }
        return new Summary(succeeded, failed, notRun, cancelled.get(), rolledBack, elapsed(began), null);
    }

    /** Update counts and counted rows of every result the statement produced. */
    private static String describe(Statement jdbc, boolean resultSet) throws SQLException {
        long changed = -1;
        long fetched = -1;
        boolean more = false;
        boolean results = resultSet;
        while (true) {
            if (results) {
                try (ResultSet rows = jdbc.getResultSet()) {
                    long count = 0;
                    while (count <= ROW_COUNT_LIMIT && rows.next()) count++;
                    fetched = Math.max(fetched, 0) + count;
                    if (count > ROW_COUNT_LIMIT) more = true;
                }
            } else {
                long count = jdbc.getUpdateCount(); // getLargeUpdateCount is optional for drivers
                if (count == -1) break;
                changed = Math.max(changed, 0) + count;
            }
            results = jdbc.getMoreResults();
        }
        if (fetched >= 0) return (more ? "more than " + String.format("%,d", ROW_COUNT_LIMIT) : String.format("%,d", fetched)) + (fetched == 1 && !more ? " row" : " rows") + " returned";
        if (changed > 0) return String.format("%,d", changed) + (changed == 1 ? " row" : " rows") + " affected";
        return "OK";
    }

    static String message(SQLException failure) {
        String text = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage().strip();
        if (failure.getErrorCode() != 0) return text + " (" + failure.getErrorCode() + ")";
        if (failure.getSQLState() != null && !failure.getSQLState().isBlank()) return text + " [" + failure.getSQLState() + "]";
        return text;
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
