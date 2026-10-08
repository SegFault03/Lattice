package com.segfault03.ideadb.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryExecutionTest {
    @Test void aRequestBeforeAttachmentStopsExecutionAndIsIdempotent() {
        QueryExecution execution = new QueryExecution();
        assertTrue(execution.requestCancellation());
        assertFalse(execution.requestCancellation());
        assertThrows(SQLException.class, () -> execution.attach(statement(() -> {})));
        execution.detach();
        assertFalse(execution.isRunning());
        assertFalse(new QueryExecution().isCancellationRequested(), "A later query must have a fresh cancel handle");
    }

    @Test void aSlowDriverDoesNotDelayAcknowledgementOrPrematurelyDetachWork() throws Exception {
        CountDownLatch cancelling = new CountDownLatch(1), release = new CountDownLatch(1);
        QueryExecution execution = new QueryExecution();
        execution.attach(statement(() -> {
            cancelling.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            assertTrue(execution.requestCancellation());
            Future<?> cancel = worker.submit(execution::cancel);
            assertTrue(cancelling.await(2, TimeUnit.SECONDS));
            assertTrue(execution.isCancellationRequested());
            assertTrue(execution.isRunning(), "Run must stay blocked until the query worker detaches");
            assertThrows(SQLException.class, execution::checkCancelled);
            assertFalse(cancel.isDone());
            release.countDown();
            cancel.get(2, TimeUnit.SECONDS);
            execution.detach();
            assertFalse(execution.isRunning());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    private static Statement statement(Runnable cancel) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class[]{Statement.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("cancel")) { cancel.run(); return null; }
                    throw new AssertionError("Unexpected call " + method.getName());
                });
    }
}
