package com.segfault03.ideadb.service;

import java.sql.*;
import java.util.concurrent.atomic.AtomicReference;

/** One execution's cancel handle; cancellation never waits on its connection's lock. */
public final class QueryExecution {
    private final AtomicReference<Statement> statement = new AtomicReference<>();
    private volatile boolean cancelled;
    public void cancel() {
        cancelled = true;
        Statement active = statement.get();
        if (active != null) try { active.cancel(); } catch (SQLException ignored) { }
    }
    public boolean isRunning() { return statement.get() != null; }
    void checkCancelled() throws SQLException { if (cancelled) throw new SQLException("Query cancelled"); }
    void attach(Statement active) throws SQLException { statement.set(active); checkCancelled(); }
    void detach() { statement.set(null); }
}
