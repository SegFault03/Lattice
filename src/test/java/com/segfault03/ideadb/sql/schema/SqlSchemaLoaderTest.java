package com.segfault03.ideadb.sql.schema;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.state.MetadataCacheState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/** The loader against real embedded engines, through the same shared browser connection the explorer uses. */
@Tag("functional")
class SqlSchemaLoaderTest {
    private ConnectionConfig config;

    @AfterEach void cleanUp() {
        if (config == null) return;
        DatabaseConnectionManager.getInstance().closeConnection(config.getId());
        MetadataCacheState.getInstance().remove(config.getId());
    }

    private ConnectionConfig h2() {
        config = new ConnectionConfig(DatabaseType.H2, "loader H2");
        config.setDatabaseName("loader_" + UUID.randomUUID());
        return config;
    }

    private ConnectionConfig hsqldb() {
        config = new ConnectionConfig(DatabaseType.HSQLDB, "loader HSQLDB");
        config.setCustomUrl("jdbc:hsqldb:mem:loader_" + UUID.randomUUID() + ";shutdown=true");
        return config;
    }

    private void sql(String... statements) throws Exception {
        Connection connection = DatabaseConnectionManager.getInstance().getConnection(config);
        synchronized (connection) {
            try (var statement = connection.createStatement()) { for (String sql : statements) statement.execute(sql); }
        }
    }

    private ExplorerMetadataSnapshot.Table cached(String table) {
        var snapshot = MetadataCacheState.getInstance().snapshot(config);
        assertNotNull(snapshot, "Nothing was cached");
        var namespace = snapshot.namespaces().stream().filter(n -> n.name().equals("PUBLIC")).findFirst().orElseThrow();
        return namespace.tables().stream().filter(t -> t.metadata().getName().equalsIgnoreCase(table)).findFirst().orElse(null);
    }

    @Test void h2LoadsTheDefaultNamespaceWithRelationshipsAndRemembersIt() throws Exception { loadsDefaultNamespace(h2()); }
    @Test void hsqldbLoadsTheDefaultNamespaceWithRelationshipsAndRemembersIt() throws Exception { loadsDefaultNamespace(hsqldb()); }

    private void loadsDefaultNamespace(ConnectionConfig config) throws Exception {
        sql("CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, AUTHOR_ID INTEGER NOT NULL, FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))",
                "COMMENT ON TABLE AUTHORS IS 'Writers'", "COMMENT ON COLUMN AUTHORS.NAME IS 'Full name'");
        assertNull(MetadataCacheState.getInstance().defaultNamespace(config));

        SqlSchemaLoader.loadNamespace(config, null, false, 300, () -> false);

        assertEquals("PUBLIC", MetadataCacheState.getInstance().defaultNamespace(config));
        assertTrue(cached("AUTHORS").columnsLoaded());
        assertTrue(cached("BOOKS").columnsLoaded());
        assertEquals("Writers", cached("AUTHORS").metadata().getComment());
        assertEquals("Full name", cached("AUTHORS").columns().stream().filter(c -> c.getName().equals("NAME")).findFirst().orElseThrow().getComment());
        var target = cached("BOOKS").columns().stream().filter(c -> c.getName().equals("AUTHOR_ID")).findFirst().orElseThrow().getReference();
        assertNotNull(target);
        assertEquals("AUTHORS", target.table());
        assertEquals("ID", target.column());
    }

    @Test void onlyTheFirstTablesUpToTheLimitHaveTheirColumnsRead() throws Exception {
        h2();
        sql("CREATE TABLE T1 (A INTEGER)", "CREATE TABLE T2 (A INTEGER)", "CREATE TABLE T3 (A INTEGER)");
        SqlSchemaLoader.loadNamespace(config, null, false, 2, () -> false);
        assertTrue(cached("T1").columnsLoaded());
        assertTrue(cached("T2").columnsLoaded());
        assertFalse(cached("T3").columnsLoaded(), "Beyond the limit, columns wait until something asks");
        assertNotNull(cached("T3"), "but the table itself is known");
    }

    @Test void aSecondLoadKeepsWhatIsCachedUnlessRefreshing() throws Exception {
        h2();
        sql("CREATE TABLE ITEMS (ID INTEGER)", "COMMENT ON COLUMN ITEMS.ID IS 'first'");
        SqlSchemaLoader.loadNamespace(config, null, false, 300, () -> false);
        sql("COMMENT ON COLUMN ITEMS.ID IS 'second'");

        SqlSchemaLoader.loadNamespace(config, null, false, 300, () -> false);
        assertEquals("first", cached("ITEMS").columns().get(0).getComment(), "Tables already read are skipped");

        SqlSchemaLoader.loadNamespace(config, null, true, 300, () -> false);
        assertEquals("second", cached("ITEMS").columns().get(0).getComment(), "A refresh reads everything again");
    }

    @Test void reloadingOneTableSeesNewColumnsAndDroppedTables() throws Exception {
        h2();
        sql("CREATE TABLE KEEP (ID INTEGER)", "CREATE TABLE GONE (ID INTEGER)");
        SqlSchemaLoader.loadNamespace(config, null, false, 300, () -> false);
        assertEquals(1, cached("KEEP").columns().size());

        sql("ALTER TABLE KEEP ADD COLUMN EXTRA VARCHAR(5)", "DROP TABLE GONE");
        SqlSchemaLoader.reloadTable(config, "PUBLIC", "KEEP", () -> false);
        SqlSchemaLoader.reloadTable(config, "PUBLIC", "GONE", () -> false);

        assertEquals(List.of("ID", "EXTRA"), cached("KEEP").columns().stream().map(ColumnMetadata::getName).toList());
        assertNull(cached("GONE"), "A dropped table disappears from the cached list");
    }

    @Test void cancellationStopsTheLoadBeforeTheNextRead() throws Exception {
        h2();
        sql("CREATE TABLE T (ID INTEGER)");
        assertThrows(CancellationException.class, () -> SqlSchemaLoader.loadNamespace(config, null, false, 300, () -> true));
        var snapshot = MetadataCacheState.getInstance().snapshot(config);
        boolean anyColumnsRead = snapshot != null && snapshot.namespaces().stream().flatMap(n -> n.tables().stream())
                .anyMatch(ExplorerMetadataSnapshot.Table::columnsLoaded);
        assertFalse(anyColumnsRead, "No table was read after cancellation");
    }

    @Test void theMySqlAdapterUsesTheCatalogAsItsDefaultNamespace() throws Exception {
        // The engine-specific rule lives on the adapter, so it needs no server to check the contract it implements.
        var adapter = DatabaseProviders.get(DatabaseType.MYSQL).metadata();
        var connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> method.getName().equals("getCatalog") ? "shop" : null);
        assertEquals("shop", adapter.defaultNamespace(connection, new ConnectionConfig(DatabaseType.MYSQL, "mysql")));
        var blank = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> null);
        var fallback = new ConnectionConfig(DatabaseType.MYSQL, "mysql");
        fallback.setDatabaseName("configured");
        assertEquals("configured", adapter.defaultNamespace(blank, fallback));
    }
}
