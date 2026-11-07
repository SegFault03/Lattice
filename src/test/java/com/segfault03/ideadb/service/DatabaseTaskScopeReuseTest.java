package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTaskScopeReuseTest {
    private static ConnectionConfig config() {
        var config = new ConnectionConfig(DatabaseType.H2, "scope");
        config.setHsqlMode(HsqlMode.MEM);
        config.setDatabaseName("scope_" + java.util.UUID.randomUUID());
        return config;
    }

    @Test void sequentialReadsShareOneIdleConnectionUntilTheScopeCloses() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var scope = new DatabaseTaskScope(executor);
        var config = config();
        try {
            var first = scope.openRead(config);
            var connection = first.connection();
            var concurrent = scope.openRead(config);
            assertNotSame(connection, concurrent.connection(), "An in-use connection is never shared");
            first.close();
            concurrent.close();
            assertTrue(connection.isClosed() == false);
            try (var again = scope.openRead(config)) {
                assertSame(connection, again.connection());
            }
            var changed = config.copy(); changed.setDatabaseName(config.getDatabaseName() + "_other");
            try (var other = scope.openRead(changed)) {
                assertNotSame(connection, other.connection(), "A changed configuration must not reuse the idle connection");
                assertTrue(connection.isClosed());
            }
            var reusable = scope.openRead(changed);
            var idleConnection = reusable.connection();
            reusable.close();
            scope.close();
            assertTrue(idleConnection.isClosed(), "Closing the scope closes the idle connection");
            assertThrows(IllegalStateException.class, () -> scope.openRead(changed));
        } finally { scope.close(); executor.shutdownNow(); }
    }

    @Test void aReadClosedAfterTheScopeIsCancelledIsNotKept() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var scope = new DatabaseTaskScope(executor);
        try {
            var read = scope.openRead(config());
            var connection = read.connection();
            scope.cancelPending();
            read.close();
            assertTrue(connection.isClosed());
        } finally { scope.close(); executor.shutdownNow(); }
    }

    @Test void aConnectionLeftInATransactionIsNotReused() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var scope = new DatabaseTaskScope(executor);
        var config = config();
        try {
            var read = scope.openRead(config);
            var connection = read.connection();
            connection.setAutoCommit(false);
            read.close();
            assertTrue(connection.isClosed());
            try (var next = scope.openRead(config)) { assertNotSame(connection, next.connection()); }
        } finally { scope.close(); executor.shutdownNow(); }
    }
}
