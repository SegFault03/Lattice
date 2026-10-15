package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import org.hsqldb.Server;
import org.hsqldb.server.ServerConstants;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSessionCancellationTest {
    @Test void anInvalidServerSessionAfterCancellationIsDiscardedBeforeTheNextQuery() throws Exception {
        assertInvalidServerSessionRecovers(false, false);
    }

    @Test void aLegacyDriverSessionIsDiscardedAfterServerDisconnect() throws Exception {
        assertInvalidServerSessionRecovers(true, false);
    }

    @Test void aQueryRecoversWhenTheServerClosesItsSessionBeforeSqlIsSubmitted() throws Exception {
        assertInvalidServerSessionRecovers(false, true);
        assertInvalidServerSessionRecovers(true, true);
    }

    private void assertInvalidServerSessionRecovers(boolean legacy, boolean automatic) throws Exception {
        Server server = new Server();
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        server.setLogWriter(null);
        server.setErrWriter(null);
        server.setAddress("127.0.0.1");
        server.setPort(port);
        server.setDatabaseName(0, "cancel_recovery");
        server.setDatabasePath(0, "mem:cancel_recovery");
        server.setDaemon(true);
        server.setSilent(true);
        server.start();
        assertEquals(ServerConstants.SERVER_STATE_ONLINE, server.getState());
        DatabaseConnectionManager manager = new DatabaseConnectionManager();
        ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "Cancellation recovery");
        config.setCustomUrl("jdbc:hsqldb:hsql://127.0.0.1:" + port + "/cancel_recovery");
        if (legacy) {
            config.setDriverSource(com.segfault03.ideadb.model.DriverSource.LOCAL_JAR);
            config.setDriverJarPath(System.getProperty("lattice.test.cancellation.driver"));
        }
        try (DatabaseSession session = manager.createSession(config)) {
            Connection original = session.execute(connection -> {
                if (legacy) assertTrue(connection.getMetaData().getDriverVersion().startsWith("2.7.3"));
                // Exercise metadata reads and cancellation with both actual driver versions.
                connection.getMetaData().getDatabaseProductName();
                connection.getMetaData().getDatabaseMajorVersion();
                try (var statement = connection.createStatement()) {
                    statement.execute("VALUES(1)");
                    statement.cancel();
                }
                return connection;
            });
            // Cancelling an already-finished statement may leave its session healthy, depending
            // on server timing. Close this fixture's sockets explicitly to test the invalid
            // branch deterministically; the server stays online for the reconnect assertion.
            server.signalCloseAllServerConnections();
            assertFalse(original.isClosed(), "isClosed alone misses this server-side disconnect");
            if (automatic) {
                var execution = new QueryExecution();
                var recovered = session.executeQuery("PUBLIC", "VALUES(7)", DataService.QueryOptions.defaults(), execution);
                assertFalse(recovered.hasError(), recovered.getError());
                assertEquals(7, ((Number) recovered.getRows().get(0).get(0)).intValue());
                assertTrue(execution.hasStartedSql());
                assertTrue(execution.wasConnectionDiscarded());
            } else assertTrue(session.discardInvalidConnection());
            assertTrue(original.isClosed());
            session.execute(connection -> {
                assertNotSame(original, connection);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("VALUES(7)")) {
                    assertTrue(rows.next());
                    assertEquals(7, rows.getInt(1));
                }
                return null;
            });
        } finally { manager.dispose(); server.stop(); }
    }

    @Test void aHealthySessionKeepsItsUncommittedTransaction() throws Exception {
        DatabaseConnectionManager manager = new DatabaseConnectionManager();
        ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "Healthy cancellation");
        config.setCustomUrl("jdbc:hsqldb:mem:healthy_cancel_recovery");
        config.setAutoCommit(false);
        try (DatabaseSession session = manager.createSession(config)) {
            Connection original = session.execute(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE CANCEL_RECOVERY (ID INTEGER)");
                    statement.execute("INSERT INTO CANCEL_RECOVERY VALUES(7)");
                }
                return connection;
            });
            assertFalse(session.discardInvalidConnection());
            var invalidSql = new QueryExecution();
            var failed = session.executeQuery("PUBLIC", "INSERT INTO TABLE_THAT_DOES_NOT_EXIST VALUES(1)",
                    DataService.QueryOptions.defaults(), invalidSql);
            assertTrue(failed.hasError());
            assertTrue(invalidSql.hasStartedSql(), "SQL errors after submission must not trigger preparation recovery");
            assertFalse(invalidSql.wasConnectionDiscarded());
            session.execute(connection -> {
                assertSame(original, connection);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT ID FROM CANCEL_RECOVERY")) {
                    assertTrue(rows.next());
                    assertEquals(7, rows.getInt(1));
                }
                connection.rollback();
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM CANCEL_RECOVERY")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
                return null;
            });
        } finally { manager.dispose(); }
    }
}
