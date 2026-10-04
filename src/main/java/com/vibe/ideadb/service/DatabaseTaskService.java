package com.vibe.ideadb.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.util.concurrent.*;

/** Plugin-owned JDBC work is bounded and shuts down when the application service is disposed. */
public final class DatabaseTaskService implements Disposable {
    private final ExecutorService workers = AppExecutorUtil.createBoundedApplicationPoolExecutor("Lattice JDBC",4);
    public static DatabaseTaskService getInstance() { return ApplicationManager.getApplication().getService(DatabaseTaskService.class); }
    public DatabaseTaskScope newScope() { return new DatabaseTaskScope(workers); }
    public Future<?> submit(Runnable operation) { return workers.submit(operation); }
    @Override public void dispose() { workers.shutdownNow(); }
}
