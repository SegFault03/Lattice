package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseExplorerSearchTest {
    @Test void allMatchesNamesAcrossCategoriesButDoesNotMatchTypeOrStatusText() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "Customer source");
        TableMetadata table = new TableMetadata(null, "customer_schema", "customers", "TABLE");
        DatabaseExplorerSearch search = new DatabaseExplorerSearch("CUSTOM", Set.of());
        for (TreeNodeData data : List.of(TreeNodeData.connection(config, true), TreeNodeData.database(config, "customer_schema"),
                TreeNodeData.table(config, "customer_schema", table), TreeNodeData.column(config, "customer_schema", table,
                        new ColumnMetadata("customer_name", "VARCHAR", Types.VARCHAR, 120, 0, true, false, false, null)))) {
            assertTrue(search.matches(data), data.getName());
        }
        assertEquals(3, search.metadataDepth());
        TreeNodeData column = TreeNodeData.column(config, "customer_schema", table,
                new ColumnMetadata("customer_name", "VARCHAR", Types.VARCHAR, 120, 0, true, false, false, null));
        assertFalse(new DatabaseExplorerSearch("VARCHAR", Set.of()).matches(column));
        assertFalse(new DatabaseExplorerSearch("Connected", Set.of()).matches(TreeNodeData.connection(config, true)));
        assertFalse(new DatabaseExplorerSearch("Tables", Set.of()).matches(TreeNodeData.tablesFolder(config, "customer_schema", 1)));
    }

    @Test void focusedCategoriesCombineWithOrAndKeysIncludeOrdinaryColumns() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "customer");
        TableMetadata table = new TableMetadata("customer", null, "customer", "TABLE");
        DatabaseExplorerSearch search = new DatabaseExplorerSearch("customer", Set.of(DatabaseExplorerSearch.Category.TABLE, DatabaseExplorerSearch.Category.KEY));
        assertTrue(search.matches(TreeNodeData.table(config, "customer", table)));
        assertTrue(search.matches(TreeNodeData.column(config, "customer", table,
                new ColumnMetadata("customer", "VARCHAR", Types.VARCHAR, 50, 0, true, false, false, null))));
        assertFalse(search.matches(TreeNodeData.database(config, "customer")));
        assertFalse(search.matches(TreeNodeData.connection(config, true)));
        assertEquals(0, new DatabaseExplorerSearch("customer", Set.of(DatabaseExplorerSearch.Category.DATA_SOURCE)).metadataDepth());
        assertEquals(1, new DatabaseExplorerSearch("customer", Set.of(DatabaseExplorerSearch.Category.SCHEMA)).metadataDepth());
        assertEquals(2, new DatabaseExplorerSearch("customer", Set.of(DatabaseExplorerSearch.Category.TABLE)).metadataDepth());
    }

    @Test void literalSearchFindsEveryOccurrenceWithoutTreatingNamesAsRegularExpressions() {
        assertEquals(List.of(new DatabaseExplorerSearch.Range(0, 3), new DatabaseExplorerSearch.Range(4, 7)),
                new DatabaseExplorerSearch("[x]", Set.of()).ranges("[X]_[x]"));
        assertFalse(new DatabaseExplorerSearch(" \t", Set.of()).isActive());
        assertEquals(List.of(), new DatabaseExplorerSearch("", Set.of()).ranges("anything"));
    }
}
