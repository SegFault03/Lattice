package com.segfault03.ideadb.sql.schema;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlSchemaTest {
    private final ConnectionConfig config = new ConnectionConfig(DatabaseType.H2, "schema test");

    private static ColumnMetadata column(String name) {
        return new ColumnMetadata(name, "INTEGER", java.sql.Types.INTEGER, 10, 0, true, false, false, null);
    }

    private static ExplorerMetadataSnapshot.Table table(String name, boolean columnsLoaded, String... columns) {
        var metadata = new TableMetadata(null, "PUBLIC", name, "TABLE");
        var list = java.util.Arrays.stream(columns).map(SqlSchemaTest::column).toList();
        metadata.setColumns(list);
        return new ExplorerMetadataSnapshot.Table(metadata, list, columnsLoaded);
    }

    private SqlSchema schema(String defaultNamespace, ExplorerMetadataSnapshot.Namespace... namespaces) {
        return SqlSchema.of(config, new ExplorerMetadataSnapshot(List.of(namespaces), List.of(), 3), defaultNamespace, 7);
    }

    @Test void unqualifiedNamesResolveInTheDefaultNamespace() {
        var schema = schema("PUBLIC",
                new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(table("ORDERS", true, "ID", "TOTAL")), true),
                new ExplorerMetadataSnapshot.Namespace("AUDIT", List.of(table("LOG", true, "ID")), true));
        assertEquals("ORDERS", schema.table(null, "ORDERS").metadata().getName());
        assertEquals("LOG", schema.table("AUDIT", "LOG").metadata().getName());
        assertNull(schema.table(null, "LOG"), "LOG lives in AUDIT, not the default namespace");
        assertEquals(List.of("ID", "TOTAL"), schema.table(null, "ORDERS").columns().stream().map(ColumnMetadata::getName).toList());
        assertEquals(config.getId(), schema.connectionId());
        assertEquals(DatabaseType.H2, schema.type());
        assertEquals(7, schema.revision());
    }

    @Test void namesResolveExactlyThenIgnoringCaseWhenThatIsUnambiguous() {
        var schema = schema("public", new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(table("Orders", true), table("ITEMS", true)), true));
        assertNotNull(schema.namespace(null), "The default namespace matches ignoring case");
        assertEquals("Orders", schema.table(null, "orders").metadata().getName());
        assertEquals("ITEMS", schema.table(null, "items").metadata().getName());
        assertNull(schema.table(null, "missing"));
    }

    @Test void aNameThatMatchesTwoTablesOnlyByCaseIsAmbiguousUnlessItIsExact() {
        var schema = schema("db", new ExplorerMetadataSnapshot.Namespace("db", List.of(table("orders", true), table("Orders", true)), true));
        assertEquals("orders", schema.table(null, "orders").metadata().getName());
        assertEquals("Orders", schema.table(null, "Orders").metadata().getName());
        assertNull(schema.table(null, "ORDERS"), "Two candidates differ only by case");
    }

    @Test void withoutADefaultNamespaceOnlyQualifiedNamesResolve() {
        var schema = schema(null, new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(table("ORDERS", true)), true));
        assertNull(schema.defaultNamespace());
        assertNull(schema.namespace(null));
        assertNull(schema.table(null, "ORDERS"));
        assertNotNull(schema.table("PUBLIC", "ORDERS"));
        assertEquals(List.of(), schema.tables(null));
        assertEquals(List.of(), schema.tables("NOPE"));
    }

    @Test void aNamespaceIsCompleteOnlyWhenItsTablesAndEveryTablesColumnsAreRead() {
        var partial = schema("PUBLIC", new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(table("A", true, "X"), table("B", false)), true));
        assertFalse(partial.isComplete(null));
        var complete = schema("PUBLIC", new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(table("A", true, "X"), table("B", true, "Y")), true));
        assertTrue(complete.isComplete(null));
        var unlisted = schema("PUBLIC", new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(), false));
        assertFalse(unlisted.isComplete(null), "An unread table list is not complete");
        assertFalse(unlisted.isComplete("NOPE"));
    }
}
