package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Which table types are listed as the user's tables, and that the adapter asks the driver for exactly those. */
@Tag("functional")
class TableTypesTest {
    @Test void userObjectsAreKeptInTheDriversSpelling() {
        String[] chosen = TableTypes.select(List.of("TABLE", "VIEW", "PARTITIONED TABLE", "MATERIALIZED VIEW", "FOREIGN TABLE",
                "Base Table", "SYSTEM VERSIONED TABLE"));
        assertArrayEquals(new String[] {"TABLE", "VIEW", "PARTITIONED TABLE", "MATERIALIZED VIEW", "FOREIGN TABLE", "Base Table", "SYSTEM VERSIONED TABLE"}, chosen);
    }

    @Test void systemTemporaryIndexAndSequenceTypesAreNeverRequested() {
        String[] chosen = TableTypes.select(List.of("SYSTEM TABLE", "SYSTEM VIEW", "TEMPORARY TABLE", "GLOBAL TEMPORARY", "LOCAL TEMPORARY",
                "INDEX", "PARTITIONED INDEX", "SEQUENCE", "TYPE", "TABLE", "TABLE", "VIEW"));
        assertArrayEquals(new String[] {"TABLE", "VIEW"}, chosen);
    }

    @Test void aDriverThatReportsNothingUsableIsAskedForTablesAndViews() {
        assertArrayEquals(new String[] {"TABLE", "VIEW"}, TableTypes.select(List.of()));
        assertArrayEquals(new String[] {"TABLE", "VIEW"}, TableTypes.select(List.of("SYSTEM TABLE", "INDEX")));
        assertArrayEquals(new String[] {"TABLE", "VIEW"}, TableTypes.select(Arrays.asList((String) null)));
    }

    @Test void materializedViewsAreViewsAndPartitionedAndForeignTablesAreTables() {
        assertTrue(new TableMetadata(null, "public", "m", "MATERIALIZED VIEW").isView());
        assertTrue(new TableMetadata(null, "public", "v", "VIEW").isView());
        assertFalse(new TableMetadata(null, "public", "p", "PARTITIONED TABLE").isView());
        assertFalse(new TableMetadata(null, "public", "f", "FOREIGN TABLE").isView());
        assertFalse(new TableMetadata(null, "public", "t", "BASE TABLE").isView());
    }

    @Test void aForeignTableIsDroppedWithItsOwnStatementAndHasNoTableActions() {
        var config = new ConnectionConfig(DatabaseType.POSTGRESQL, "foreign");
        var dialect = (PostgresDialect) DatabaseProviders.get(DatabaseType.POSTGRESQL).dialect();
        assertEquals("DROP FOREIGN TABLE \"public\".\"remote_orders\"", dialect.buildDropForeignTableSql(config, "public", "remote_orders"));
        assertTrue(new TableMetadata(null, "public", "f", "FOREIGN TABLE").isForeignTable());
        assertFalse(new TableMetadata(null, "public", "p", "PARTITIONED TABLE").isForeignTable());
    }

    @Test void theAdapterRequestsTheReportedUserTypesAndKeepsWhatComesBack() throws Exception {
        List<String> requested = new ArrayList<>();
        DatabaseMetaData meta = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
            case "getSearchStringEscape" -> "\\";
            case "storesLowerCaseIdentifiers" -> true;
            case "getSchemas" -> rows(List.of(Map.of("TABLE_SCHEM", "public")));
            case "getTableTypes" -> rows(List.of(Map.of("TABLE_TYPE", "TABLE"), Map.of("TABLE_TYPE", "SYSTEM TABLE"), Map.of("TABLE_TYPE", "PARTITIONED TABLE"),
                    Map.of("TABLE_TYPE", "MATERIALIZED VIEW"), Map.of("TABLE_TYPE", "FOREIGN TABLE"), Map.of("TABLE_TYPE", "INDEX"), Map.of("TABLE_TYPE", "VIEW")));
            case "getTables" -> {
                requested.addAll(Arrays.asList((String[]) a[3]));
                yield rows(List.of(Map.of("TABLE_NAME", "orders", "TABLE_TYPE", "PARTITIONED TABLE"), Map.of("TABLE_NAME", "totals", "TABLE_TYPE", "MATERIALIZED VIEW")));
            }
            default -> throw new AssertionError("Unexpected metadata method: " + m.getName());
        });
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {Connection.class}, (p, m, a) -> switch (m.getName()) {
            case "getMetaData" -> meta;
            case "getSchema" -> "public";
            default -> throw new AssertionError("Unexpected connection method: " + m.getName());
        });
        var config = new ConnectionConfig(DatabaseType.POSTGRESQL, "types");
        var tables = DatabaseProviders.get(DatabaseType.POSTGRESQL).metadata().getTables(connection, config, "public");
        assertEquals(List.of("TABLE", "PARTITIONED TABLE", "MATERIALIZED VIEW", "FOREIGN TABLE", "VIEW"), requested);
        assertEquals(List.of("orders", "totals"), tables.stream().map(TableMetadata::getName).toList());
        assertEquals(List.of(false, true), tables.stream().map(TableMetadata::isView).toList());
    }

    @Test void embeddedH2ListsItsTablesAndViews() throws Exception {
        var config = new ConnectionConfig(DatabaseType.H2, "H2 table types");
        config.setDatabaseName("types_" + UUID.randomUUID());
        var manager = new com.segfault03.ideadb.service.DatabaseConnectionManager();
        try (Connection connection = manager.openConnection(config); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY)");
            statement.execute("CREATE VIEW ITEM_IDS AS SELECT ID FROM ITEMS");
            var tables = DatabaseProviders.get(DatabaseType.H2).metadata().getTables(connection, config, "PUBLIC");
            assertEquals(List.of("ITEMS", "ITEM_IDS"), tables.stream().map(TableMetadata::getName).toList());
            assertEquals(List.of(false, true), tables.stream().map(TableMetadata::isView).toList());
        } finally { manager.dispose(); }
    }

    private static ResultSet rows(List<? extends Map<?, ?>> data) {
        AtomicInteger index = new AtomicInteger(-1);
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[] {ResultSet.class}, (p, m, a) -> switch (m.getName()) {
            case "next" -> index.incrementAndGet() < data.size();
            case "close" -> null;
            case "getString" -> data.get(index.get()).get(a[0]);
            default -> throw new AssertionError("Unexpected result method: " + m.getName());
        });
    }
}
