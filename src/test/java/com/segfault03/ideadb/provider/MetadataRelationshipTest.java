package com.segfault03.ideadb.provider;

import com.intellij.util.xmlb.XmlSerializer;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import com.segfault03.ideadb.state.MetadataCacheState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Foreign-key targets and table/column comments, read through the production adapters and kept by the cache. */
@Tag("functional")
class MetadataRelationshipTest {
    @Test void embeddedH2ReportsCommentsAndForeignKeyTargets() throws Exception {
        var config = new ConnectionConfig(DatabaseType.H2, "H2 relationships");
        config.setDatabaseName("relationships_" + UUID.randomUUID());
        verify(config);
    }

    @Test void embeddedHsqldbReportsCommentsAndForeignKeyTargets() throws Exception {
        var config = new ConnectionConfig(DatabaseType.HSQLDB, "HSQLDB relationships");
        config.setCustomUrl("jdbc:hsqldb:mem:relationships_" + UUID.randomUUID() + ";shutdown=true");
        verify(config);
    }

    @Test @Tag("integration") void mysqlServerReportsCommentsAndForeignKeyTargets() throws Exception {
        var config = new ConnectionConfig(DatabaseType.MYSQL, "MySQL relationships");
        config.setPort(Integer.getInteger("lattice.test.mysql.port", 3306));
        verify(config);
    }

    @Test @Tag("integration") @Tag("postgres") void postgresServerReportsCommentsAndForeignKeyTargets() throws Exception {
        var config = new ConnectionConfig(DatabaseType.POSTGRESQL, "PostgreSQL relationships");
        config.setPort(Integer.getInteger("lattice.test.postgres.port", 5432));
        config.setPassword(System.getProperty("lattice.test.postgres.password", "lattice-test"));
        verify(config);
    }

    private static void verify(ConnectionConfig config) throws Exception {
        var manager = new DatabaseConnectionManager();
        var provider = DatabaseProviders.get(config.getType());
        var dialect = provider.dialect();
        String namespace = "REL_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        try (Connection connection = manager.getConnection(config)) {
            DdlService.getInstance().createDatabase(connection, config, namespace);
            try {
                provider.selectNamespace(connection, namespace);
                String authors = DdlService.formatTable(config, namespace, "AUTHORS");
                String books = DdlService.formatTable(config, namespace, "BOOKS");
                String titles = DdlService.formatTable(config, namespace, "BOOK_TITLES");
                String id = dialect.quoteIdentifier("ID"), name = dialect.quoteIdentifier("NAME");
                String author = dialect.quoteIdentifier("AUTHOR_ID"), title = dialect.quoteIdentifier("TITLE");
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + authors + " (" + id + " INTEGER NOT NULL PRIMARY KEY, " + name + " VARCHAR(40))");
                    statement.execute("CREATE TABLE " + books + " (" + id + " INTEGER NOT NULL PRIMARY KEY, " + author + " INTEGER NOT NULL, "
                            + title + " VARCHAR(80), FOREIGN KEY (" + author + ") REFERENCES " + authors + " (" + id + "))");
                    statement.execute("CREATE VIEW " + titles + " AS SELECT " + id + ", " + title + " FROM " + books);
                    if (config.getType() == DatabaseType.MYSQL) {
                        statement.execute("ALTER TABLE " + authors + " COMMENT = 'Writers of books'");
                        statement.execute("ALTER TABLE " + authors + " MODIFY " + name + " VARCHAR(40) COMMENT 'Full name'");
                        statement.execute("ALTER TABLE " + books + " COMMENT = 'Published titles'");
                        statement.execute("ALTER TABLE " + books + " MODIFY " + title + " VARCHAR(80) COMMENT 'Display title'");
                    } else {
                        statement.execute("COMMENT ON TABLE " + authors + " IS 'Writers of books'");
                        statement.execute("COMMENT ON COLUMN " + authors + "." + name + " IS 'Full name'");
                        statement.execute("COMMENT ON TABLE " + books + " IS 'Published titles'");
                        statement.execute("COMMENT ON COLUMN " + books + "." + title + " IS 'Display title'");
                    }
                }

                var metadata = MetadataService.getInstance();
                var tables = metadata.getTables(connection, config, namespace);
                assertEquals("Writers of books", table(tables, "AUTHORS").getComment());
                assertEquals("Published titles", table(tables, "BOOKS").getComment());
                assertNull(table(tables, "BOOK_TITLES").getComment(), "No comment was set on the view");
                assertTrue(table(tables, "BOOK_TITLES").isView());
                assertFalse(table(tables, "BOOKS").isView());

                var columns = metadata.getColumns(connection, config, namespace, "BOOKS");
                var authorColumn = column(columns, "AUTHOR_ID");
                assertTrue(authorColumn.isForeignKey());
                ColumnReference reference = authorColumn.getReference();
                assertNotNull(reference, "The foreign key target must be reported");
                assertTrue(reference.namespace().equalsIgnoreCase(namespace), reference::toString);
                assertTrue(reference.table().equalsIgnoreCase("AUTHORS"), reference::toString);
                assertTrue(reference.column().equalsIgnoreCase("ID"), reference::toString);
                assertEquals("Display title", column(columns, "TITLE").getComment());
                assertNull(column(columns, "ID").getComment());
                assertNull(column(columns, "ID").getReference(), "A plain column has no target");
                assertFalse(column(columns, "ID").isForeignKey());
                assertEquals("Full name", column(metadata.getColumns(connection, config, namespace, "AUTHORS"), "NAME").getComment());

                assertCacheKeepsEverything(config, namespace);
            } finally {
                DdlService.getInstance().dropDatabase(connection, config, namespace);
            }
        }
    }

    /** The cache that backs offline browsing, and later completion, must keep targets and comments across a restart. */
    private static void assertCacheKeepsEverything(ConnectionConfig config, String namespace) {
        var cache = MetadataCacheState.getInstance();
        long before = cache.revision();
        var persisted = XmlSerializer.deserialize(XmlSerializer.serialize(cache.getState()), MetadataCacheState.State.class);
        var restored = new MetadataCacheState();
        restored.loadState(persisted);
        assertTrue(restored.revision() > 0, "Loading state is a change");
        var snapshot = restored.snapshot(config);
        assertNotNull(snapshot, "The cached connection must survive serialization");
        var namespaceEntry = snapshot.namespaces().stream().filter(n -> n.name().equalsIgnoreCase(namespace)).findFirst().orElseThrow();
        var books = namespaceEntry.tables().stream().filter(t -> t.metadata().getName().equalsIgnoreCase("BOOKS")).findFirst().orElseThrow();
        assertEquals("Published titles", books.metadata().getComment());
        var author = column(books.columns(), "AUTHOR_ID");
        assertEquals("AUTHORS", author.getReference().table().toUpperCase(Locale.ROOT));
        assertEquals("Display title", column(books.columns(), "TITLE").getComment());
        assertEquals(before, cache.revision(), "Reading the cache must not change it");
    }

    private static TableMetadata table(List<TableMetadata> tables, String name) {
        return tables.stream().filter(t -> t.getName().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new AssertionError(name + " not in " + tables));
    }

    private static ColumnMetadata column(List<ColumnMetadata> columns, String name) {
        return columns.stream().filter(c -> c.getName().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new AssertionError(name + " not in " + columns));
    }
}
