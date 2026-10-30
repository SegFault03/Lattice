package com.segfault03.ideadb.sql.doc;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.schema.SqlSchema;

import java.sql.Types;
import java.util.List;

/** A small commented schema with one foreign key, one view and a second schema, for resolver and card tests. */
final class DocTestSchema {
    private DocTestSchema() {}

    static ColumnMetadata column(String name, String type, boolean nullable, boolean key, boolean auto, String comment, ColumnReference reference) {
        var column = new ColumnMetadata(name, type, Types.INTEGER, 10, 0, nullable, key, auto, null);
        if (comment != null) column = column.withComment(comment);
        return reference == null ? column : column.withReference(reference);
    }

    static ExplorerMetadataSnapshot.Table table(String name, String type, String comment, boolean columnsLoaded, ColumnMetadata... columns) {
        var metadata = new TableMetadata(null, "PUBLIC", name, type, comment);
        metadata.setColumns(List.of(columns));
        return new ExplorerMetadataSnapshot.Table(metadata, columnsLoaded ? List.of(columns) : List.of(), columnsLoaded);
    }

    static SqlSchema schema(DatabaseType type) {
        var customers = table("customers", "TABLE", "People who buy things", true,
                column("id", "INTEGER", false, true, true, "Surrogate key", null), column("name", "VARCHAR(40)", true, false, false, null, null));
        var orders = table("orders", "TABLE", null, true,
                column("id", "INTEGER", false, true, true, null, null), column("total", "DECIMAL(10,2)", false, false, false, "Gross, with tax", null),
                column("customer_id", "INTEGER", false, false, false, null, new ColumnReference("PUBLIC", "customers", "id")));
        var summary = table("order_summary", "VIEW", "<b>not</b> a table", true, column("total", "DECIMAL(10,2)", true, false, false, null, null));
        var unread = table("unread", "TABLE", null, false);
        var log = table("log", "TABLE", null, true, column("message", "TEXT", true, false, false, null, null));
        var snapshot = new ExplorerMetadataSnapshot(List.of(
                new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(customers, orders, summary, unread), true),
                new ExplorerMetadataSnapshot.Namespace("audit", List.of(log), true),
                new ExplorerMetadataSnapshot.Namespace("empty", List.of(), false)), List.of(), 3);
        return SqlSchema.of(new ConnectionConfig(type, "doc test"), snapshot, "PUBLIC", 1);
    }
}
