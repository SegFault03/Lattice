package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The same production-service flow runs against embedded and real server providers. */
class ProviderEndToEndTest {
    @Test void embeddedHsqlWorkflow() throws Exception {
        var config = new ConnectionConfig(DatabaseType.HSQLDB, "embedded contract");
        config.setCustomUrl("jdbc:hsqldb:mem:provider_" + UUID.randomUUID() + ";shutdown=true");
        workflow(config);
    }
    @Test @Tag("integration") void mysqlServerWorkflow() throws Exception {
        var config = new ConnectionConfig(DatabaseType.MYSQL, "mysql contract");
        config.setPort(Integer.getInteger("lattice.test.mysql.port", 3306));
        workflow(config);
    }
    @Test @Tag("integration") void hsqlServerWorkflow() throws Exception {
        var config = new ConnectionConfig(DatabaseType.HSQLDB, "hsql server contract");
        config.setPort(Integer.getInteger("lattice.test.hsqldb.port", 9001));
        workflow(config);
    }

    private void workflow(ConnectionConfig config) throws Exception {
        var manager = new DatabaseConnectionManager();
        var ddl = DdlService.getInstance(); var data = DataService.getInstance();
        var provider = DatabaseProviders.get(config.getType());
        String namespace = "PROVIDER_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        var export = Files.createTempFile("provider-export", ".sql");
        try (Connection browser = manager.getConnection(config)) {
            assertSame(provider, DatabaseProviders.forConnection(browser));
            ddl.createDatabase(browser, config, namespace);
            try {
                ddl.createTable(browser, config, namespace, "ITEMS", List.of(
                        new ColumnDefinition("ID", "INTEGER", 0, false, true, false, ""),
                        new ColumnDefinition("PAYLOAD", "VARCHAR", 100, true, false, false, "")));
                assertTrue(MetadataService.getInstance().getDatabases(browser, config).contains(namespace));
                var table = MetadataService.getInstance().getTables(browser, config, namespace).stream().filter(t -> t.getName().equals("ITEMS")).findFirst().orElseThrow();
                assertEquals(provider.namespace(namespace).catalog(), table.getCatalog());
                assertEquals(provider.namespace(namespace).schema(), table.getSchema());
                assertEquals(2, MetadataService.getInstance().getColumns(browser, config, namespace, "ITEMS").size());
                try (Connection mutation = manager.openConnection(config)) {
                    data.commitChanges(mutation, config, namespace, "ITEMS", List.of(Map.of("ID", 1, "PAYLOAD", "O'Brien\\path"), Map.of("ID", 2, "PAYLOAD", "second")), List.of());
                    assertThrows(Exception.class, () -> data.commitChanges(mutation, config, namespace, "ITEMS", List.of(Map.of("ID", 3, "PAYLOAD", "rollback"), Map.of("ID", 1, "PAYLOAD", "duplicate")), List.of()));
                }
                assertEquals(2, data.countRows(browser, config, namespace, "ITEMS", null));
                try (Connection mutation = manager.openConnection(config)) {
                    data.commitChanges(mutation, config, namespace, "ITEMS", List.of(), List.of(new DataService.RowUpdate(Map.of("PAYLOAD", "updated"), Map.of("ID", 2))));
                    data.deleteRows(mutation, config, namespace, "ITEMS", List.of(Map.of("ID", 2)));
                    data.commitChanges(mutation, config, namespace, "ITEMS", List.of(Map.of("ID", 2, "PAYLOAD", "second")), List.of());
                }
                var page = data.fetchData(browser, config, namespace, "ITEMS", null, null, 1, 1);
                assertFalse(page.hasError(), page.getError()); assertEquals(2, ((Number) page.getRows().getFirst().getFirst()).intValue());
                assertTrue(ddl.getCreateTableStatement(browser, config, namespace, table).contains("CREATE"));
                assertEquals(2, ExportService.getInstance().exportPersisted(browser, config, namespace, "ITEMS", null, "sql", export.toFile()));
                String exported = Files.readString(export);
                ddl.truncateTable(browser, config, namespace, "ITEMS");
                try (var statement = browser.createStatement()) { for (String sql : exported.lines().toList()) statement.execute(sql); }
                assertEquals(2, data.countRows(browser, config, namespace, "ITEMS", null));
                var result = data.fetchData(browser, config, namespace, "ITEMS", null, null, 10, 0);
                assertEquals("O'Brien\\path", result.getRows().getFirst().get(1));
                try (var session = manager.createSession(config)) {
                    assertNotSame(browser, session.execute(conn -> conn));
                    var query = session.execute(conn -> data.executeQuery(conn, namespace, "SELECT COUNT(*) FROM " + provider.dialect().quoteIdentifier("ITEMS")));
                    assertFalse(query.hasError(), query.getError());
                    assertEquals(2, ((Number) query.getRows().getFirst().getFirst()).intValue());
                }
                ddl.alterTableAddColumn(browser, config, namespace, "ITEMS", new ColumnDefinition("NOTE", "VARCHAR", 30, true, false, false, ""));
                ddl.alterTableModifyColumn(browser, config, namespace, "ITEMS", new ColumnDefinition("NOTE", "VARCHAR", 50, true, false, false, ""));
                ddl.alterTableRenameColumn(browser, config, namespace, "ITEMS", "NOTE", "RENAMED_NOTE");
                ddl.alterTableDropColumn(browser, config, namespace, "ITEMS", "RENAMED_NOTE");
                var renamed = ddl.alterTableRename(browser, config, namespace, "ITEMS", "RENAMED_ITEMS");
                assertEquals("RENAMED_ITEMS", renamed.getName());
                ddl.dropTable(browser, config, namespace, renamed.getName());
            } finally { ddl.dropDatabase(browser, config, namespace); }
        } finally { manager.dispose(); Files.deleteIfExists(export); }
    }
}
