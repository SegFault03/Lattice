package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseRefreshConnectionTest {
    @Test void metadataRefreshUsesCommittedStateWithoutChangingAManualConsoleTransaction() throws Exception {
        for (var engine : List.of(DatabaseType.H2, DatabaseType.HSQLDB)) {
            var config = new ConnectionConfig(engine, "refresh");
            config.setHsqlMode(HsqlMode.MEM);
            config.setDatabaseName("refresh_" + UUID.randomUUID());
            config.setAutoCommit(false);
            var manager = new DatabaseConnectionManager();
            try (var session = manager.createSession(config)) {
                Connection manual = session.execute(c -> c);
                assertFalse(manual.getAutoCommit());
                manager.executeBrowser(config, metadata -> {
                    assertNotSame(manual, metadata);
                    assertTrue(metadata.getAutoCommit(), "Metadata must not keep an old transaction snapshot");
                    return null;
                });
                try (var mutation = manager.openConnection(config); var statement = mutation.createStatement()) {
                    assertTrue(mutation.getAutoCommit(), "UI DDL must commit independently of console settings");
                    statement.execute("CREATE TABLE refresh_data (id INTEGER PRIMARY KEY)");
                    statement.execute("INSERT INTO refresh_data VALUES (1)");
                }
                assertEquals(1, count(manager, config));
                try (var mutation = manager.openConnection(config); var statement = mutation.createStatement()) {
                    statement.execute("INSERT INTO refresh_data VALUES (2)");
                }
                assertEquals(2, count(manager, config), "A repeated refresh must see the latest committed rows");
                assertFalse(manual.getAutoCommit(), "Refreshing must preserve the console's transaction mode");
            } finally { manager.dispose(); }
        }
    }

    private int count(DatabaseConnectionManager manager, ConnectionConfig config) throws Exception {
        return manager.executeBrowser(config, connection -> {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM refresh_data")) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        });
    }
}
