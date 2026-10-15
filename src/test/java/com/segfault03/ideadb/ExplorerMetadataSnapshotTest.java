package com.segfault03.ideadb;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class ExplorerMetadataSnapshotTest {
    @Test void metadataSearchDiscoversUnexpandedSchemasTablesAndOrdinaryColumnNamesAndCanBeCancelled() throws Exception {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "Search snapshot");
        var properties = new java.util.Properties();
        properties.setProperty("user", "SA");
        properties.setProperty("password", "");
        try (var connection = new org.hsqldb.jdbc.JDBCDriver().connect(
                "jdbc:hsqldb:mem:search_snapshot_" + System.nanoTime() + ";shutdown=true", properties)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA SEARCH_DEMO AUTHORIZATION DBA");
                statement.execute("CREATE TABLE SEARCH_DEMO.CUSTOMERS (ID INTEGER PRIMARY KEY, CUSTOMER_NAME VARCHAR(100))");
            }
            var schemas = ExplorerMetadataSnapshot.read(connection, config, 1, () -> false);
            assertTrue(schemas.namespaces().stream().anyMatch(namespace -> namespace.name().equals("SEARCH_DEMO")));
            assertTrue(schemas.namespaces().stream().noneMatch(ExplorerMetadataSnapshot.Namespace::tablesLoaded));
            var tables = ExplorerMetadataSnapshot.read(connection, config, 2, () -> false);
            var table = tables.namespaces().stream().filter(namespace -> namespace.name().equals("SEARCH_DEMO"))
                    .findFirst().orElseThrow().tables().getFirst();
            assertEquals("CUSTOMERS", table.metadata().getName());
            assertFalse(table.columnsLoaded());
            var columns = ExplorerMetadataSnapshot.read(connection, config, 3, () -> false).namespaces().stream()
                    .filter(namespace -> namespace.name().equals("SEARCH_DEMO")).findFirst().orElseThrow().tables().getFirst();
            assertTrue(columns.columnsLoaded());
            assertTrue(columns.columns().stream().anyMatch(column -> column.getName().equals("CUSTOMER_NAME") && !column.isPrimaryKey()));
            assertThrows(CancellationException.class, () -> ExplorerMetadataSnapshot.read(connection, config, 3, () -> true));
        }
    }
}
