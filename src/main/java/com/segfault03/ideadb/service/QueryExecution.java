package com.segfault03.ideadb.service;

import java.sql.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** One execution's cancel handle; cancellation never waits on its connection's lock. */
public final class QueryExecution {
    private final AtomicReference<Statement> statement = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /** Marks the request immediately; JDBC cancellation itself can run off the EDT. */
    public boolean requestCancellation() { return cancelled.compareAndSet(false, true); }
    public boolean isCancellationRequested() { return cancelled.get(); }
    public void cancel() {
        requestCancellation();
        Statement active = statement.get();
        if (active != null) try { active.cancel(); } catch (SQLException ignored) { }
    }
    public boolean isRunning() { return statement.get() != null; }
    void checkCancelled() throws SQLException { if (cancelled.get()) throw new SQLException("Query cancelled"); }
    void attach(Statement active) throws SQLException { statement.set(active); checkCancelled(); }
    void detach() { statement.set(null); }
}
