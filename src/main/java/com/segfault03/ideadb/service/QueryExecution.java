package com.segfault03.ideadb.service;

import java.sql.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** One execution's cancel handle; cancellation never waits on its connection's lock. */
public final class QueryExecution {
    @FunctionalInterface interface CancelOperation { void cancel() throws SQLException; }
    private volatile CancelOperation cancelOperation;
    private final AtomicReference<Statement> statement = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final AtomicBoolean sqlStarted = new AtomicBoolean();
    private final AtomicBoolean queryOnly = new AtomicBoolean();
    private final AtomicBoolean connectionDiscarded = new AtomicBoolean();
    private final Object cancellationLock = new Object();
    /** Marks the request immediately; JDBC cancellation itself can run off the EDT. */
    public boolean requestCancellation() { return cancelled.compareAndSet(false, true); }
    public boolean isCancellationRequested() { return cancelled.get(); }
    public boolean hasStartedSql() { return sqlStarted.get(); }
    /** True once SQL ran; false when the whole run was provably a read, so nothing needs reloading. */
    public boolean mayHaveChangedData() { return sqlStarted.get() && !queryOnly.get(); }
    public boolean wasConnectionDiscarded() { return connectionDiscarded.get(); }
    void markConnectionDiscarded() { connectionDiscarded.set(true); }
    void markQueryOnly() { queryOnly.set(true); }
    void markSqlStarted() { sqlStarted.set(true); }
    boolean prepareRetryBeforeSql() {
        synchronized (cancellationLock) {
            if (cancelled.get() || sqlStarted.get() || statement.get() != null) return false;
            finishing.set(false);
            return true;
        }
    }
    public void cancel() {
        requestCancellation();
        synchronized (cancellationLock) {
            // JDBC drivers may ignore cancel() while a newly attached statement is still starting.
            // Retry the cancel request, never the SQL, until completion or the short startup window ends.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            do {
                if (finishing.get()) break;
                Statement active = statement.get();
                if (active == null) break;
                try { cancelOperation.cancel(); } catch (SQLException ignored) { }
                if (finishing.get()) break;
                try { Thread.sleep(25); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            } while (System.nanoTime() < deadline);
        }
    }
    public boolean isRunning() { return statement.get() != null; }
    void checkCancelled() throws SQLException { if (cancelled.get()) throw new SQLException("Query cancelled"); }
    void attach(Statement active) throws SQLException { attach(active,active::cancel); }
    void attach(Statement active, CancelOperation cancellation) throws SQLException { cancelOperation=cancellation; statement.set(active); checkCancelled(); }
    // A late JDBC cancel must finish before the console offers another query on this session.
    void detach() { finishing.set(true); synchronized (cancellationLock) { statement.set(null); } }
}
