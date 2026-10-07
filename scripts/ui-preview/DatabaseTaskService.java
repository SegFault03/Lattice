package com.segfault03.ideadb.service;

import java.util.concurrent.*;

/** Preview-only service: no JDBC, background tasks, or application bootstrap. */
public final class DatabaseTaskService {
    private static final DatabaseTaskService INSTANCE = new DatabaseTaskService();
    public static DatabaseTaskService getInstance() { return INSTANCE; }
    public DatabaseTaskScope newScope() { return new DatabaseTaskScope(operation -> {}); }
    public Future<?> submit(Runnable operation) { return new CompletableFuture<Void>(); }
}
