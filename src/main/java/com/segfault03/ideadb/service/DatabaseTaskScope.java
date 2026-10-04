package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

/** Editor-owned work. Running writes finish atomically; reads and queued writes are cancelled on close. */
public final class DatabaseTaskScope implements AutoCloseable {
    private final Executor executor;
    private final Set<Work> pending=new HashSet<>();
    private final Set<Connection> reads=new HashSet<>();
    private boolean closed;
    public DatabaseTaskScope(Executor executor) { this.executor=executor; }
    private final class Work extends FutureTask<Void> {
        final boolean mutation; boolean started;
        Work(Runnable operation,boolean mutation) { super(operation,null); this.mutation=mutation; }
        @Override public void run() {
            synchronized(DatabaseTaskScope.this) { if(closed) { cancel(false); return; } started=true; }
            super.run();
        }
        @Override protected void done() { synchronized(DatabaseTaskScope.this) { pending.remove(this); } }
    }
    public Future<?> submit(Runnable operation) { return submit(operation,false); }
    public Future<?> submitMutation(Runnable operation) { return submit(operation,true); }
    private synchronized Future<?> submit(Runnable operation,boolean mutation) {
        if(closed) throw new IllegalStateException("Editor task scope is closed");
        Work task=new Work(operation,mutation); pending.add(task);
        try { executor.execute(task); return task; } catch(RuntimeException failure) { pending.remove(task); throw failure; }
    }
    public final class ReadConnection implements AutoCloseable {
        private final Connection connection;
        private ReadConnection(Connection connection) { this.connection=connection; }
        public Connection connection() { return connection; }
        @Override public void close() throws java.sql.SQLException {
            synchronized(DatabaseTaskScope.this) { reads.remove(connection); }
            connection.close();
        }
    }
    public ReadConnection openRead(ConnectionConfig config) throws Exception {
        synchronized(this) { if(closed) throw new IllegalStateException("Editor task scope is closed"); }
        Connection connection=DatabaseConnectionManager.getInstance().openConnection(config);
        synchronized(this) {
            if(closed) { connection.close(); throw new IllegalStateException("Editor task scope is closed"); }
            reads.add(connection); return new ReadConnection(connection);
        }
    }
    public synchronized void cancelPending() {
        closed=true;
        for(Work task:new ArrayList<>(pending)) if(!task.mutation || !task.started) task.cancel(true);
    }
    @Override public void close() {
        List<Connection> connections;
        synchronized(this) { cancelPending(); connections=new ArrayList<>(reads); reads.clear(); }
        for(Connection connection:connections) try { connection.close(); } catch(java.sql.SQLException ignored) { }
    }
}
