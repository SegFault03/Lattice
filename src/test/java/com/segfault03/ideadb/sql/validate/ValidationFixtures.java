package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.schema.SqlSchema;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;

/** A small shop schema for validation tests: customers, orders, items and a view. */
final class ValidationFixtures {
    private ValidationFixtures() {}

    private static ColumnMetadata column(String name) {
        return new ColumnMetadata(name, "INTEGER", Types.INTEGER, 10, 0, true, false, false, null);
    }

    private static ExplorerMetadataSnapshot.Table table(String name, String type, boolean columnsLoaded, String... columns) {
        var metadata = new TableMetadata(null, "public", name, type);
        var list = Arrays.stream(columns).map(ValidationFixtures::column).toList();
        metadata.setColumns(list);
        return new ExplorerMetadataSnapshot.Table(metadata, columnsLoaded ? list : List.of(), columnsLoaded);
    }

    static SqlSchema shop(DatabaseType type) {
        var snapshot = new ExplorerMetadataSnapshot(List.of(
                new ExplorerMetadataSnapshot.Namespace("public", List.of(
                        table("customers", "TABLE", true, "id", "name", "email"),
                        table("orders", "TABLE", true, "id", "customer_id", "total", "created_at", "status"),
                        table("items", "TABLE", true, "id", "order_id", "sku", "qty"),
                        table("order_summary", "VIEW", true, "customer_id", "total"),
                        table("unread", "TABLE", false, "a")), true),
                new ExplorerMetadataSnapshot.Namespace("audit", List.of(table("log", "TABLE", true, "id", "message")), true)), List.of(), 3);
        return SqlSchema.of(new ConnectionConfig(type, "shop"), snapshot, "public", 1);
    }
}
