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
    private Connection idle; private String idleKey;
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
        private final Connection connection; private final String key; private boolean released;
        private ReadConnection(Connection connection,String key) { this.connection=connection; this.key=key; }
        public Connection connection() { return connection; }
        /** Hands the connection back for the next read; it is closed instead if it is unusable or the scope is done. */
        @Override public void close() throws java.sql.SQLException {
            boolean reusable=false;
            try { reusable=!connection.isClosed() && connection.getAutoCommit(); } catch(java.sql.SQLException unusable) { }
            synchronized(DatabaseTaskScope.this) {
                if(released) return;
                released=true; reads.remove(connection);
                if(reusable && !closed && idle==null) { idle=connection; idleKey=key; return; }
            }
            connection.close();
        }
    }
    /** Reads share one idle connection per scope; cancellation and close still end every connection. */
    public ReadConnection openRead(ConnectionConfig config) throws Exception {
        // The close generation is part of the key: a Disconnect since parking discards the idle connection.
        String key=identity(config)+"\n"+DatabaseConnectionManager.getInstance().validateOpen(config);
        Connection reused=null, stale=null;
        synchronized(this) {
            if(closed) throw new IllegalStateException("Editor task scope is closed");
            if(idle!=null) { if(key.equals(idleKey)) reused=idle; else stale=idle; idle=null; idleKey=null; }
        }
        closeQuietly(stale);
        if(reused!=null) {
            try { if(reused.isClosed() || !reused.isValid(2)) { closeQuietly(reused); reused=null; } }
            catch(Exception unusable) { closeQuietly(reused); reused=null; }
        }
        Connection connection=reused!=null ? reused : DatabaseConnectionManager.getInstance().openConnection(config);
        synchronized(this) {
            if(closed) { closeQuietly(connection); throw new IllegalStateException("Editor task scope is closed"); }
            reads.add(connection); return new ReadConnection(connection,key);
        }
    }
    private static String identity(ConnectionConfig config) {
        return String.join("\n",config.getId(),config.buildJdbcUrl(),String.valueOf(config.getUser()),String.valueOf(config.getPassword()),
                String.valueOf(config.isAutoCommit()),String.valueOf(config.getDriverSource()),config.getDriverVersion(),config.getDriverJarPath());
    }
    private static void closeQuietly(Connection connection) {
        if(connection!=null) try { connection.close(); } catch(java.sql.SQLException ignored) { }
    }
    public void cancelPending() {
        Connection stale;
        synchronized(this) {
            closed=true;
            for(Work task:new ArrayList<>(pending)) if(!task.mutation || !task.started) task.cancel(true);
            stale=idle; idle=null; idleKey=null;
        }
        closeQuietly(stale);
    }
    @Override public void close() {
        List<Connection> connections;
        cancelPending();
        synchronized(this) { connections=new ArrayList<>(reads); reads.clear(); }
        connections.forEach(DatabaseTaskScope::closeQuietly);
    }
}
