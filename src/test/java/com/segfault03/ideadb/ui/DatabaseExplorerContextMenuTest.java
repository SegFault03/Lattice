package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.DdlService;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseExplorerContextMenuTest {
    @Test void insertTemplatesRequireLoadedColumnsAndIncludeThemForEveryProvider() {
        for (var type : DatabaseType.values()) {
            var config = new ConnectionConfig(type, "Template source");
            var metadata = new TableMetadata(null, "PUBLIC", "products", "TABLE");
            var data = TreeNodeData.table(config, "PUBLIC", metadata);
            assertThrows(IllegalStateException.class, () -> DatabaseMainPanel.generatedSql(data, DatabaseExplorerContextMenu.Action.GENERATE_INSERT));
            assertTrue(DatabaseMainPanel.generatedSql(data, DatabaseExplorerContextMenu.Action.GENERATE_SELECT).startsWith("SELECT *"));
            metadata.setColumns(List.of(new ColumnMetadata("id", "INTEGER", java.sql.Types.INTEGER, 10, 0, false, true, false, null)));
            String sql = DatabaseMainPanel.generatedSql(data, DatabaseExplorerContextMenu.Action.GENERATE_INSERT);
            assertTrue(sql.contains(type == DatabaseType.MYSQL ? "(`id`) VALUES (?)" : "(\"id\") VALUES (?)"), sql);
        }
    }
    @Test void disconnectedSourceAndSchemaMenusKeepSettingsAndReconnectButDisableLiveOperations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var type : DatabaseType.values()) {
                var config = new ConnectionConfig(type, "Offline");
                var calls = new ArrayList<DatabaseExplorerContextMenu.Action>();
                var source = DatabaseExplorerContextMenu.create(TreeNodeData.connection(config, false), false, calls::add);
                assertEnabled(source, true, "Connect", "Edit connection…", "Remove connection");
                assertEnabled(source, false, "Disconnect", "Open SQL console");
                for (var item : items(source)) if (item.getText().startsWith("Create ")) assertFalse(item.isEnabled());
                clickDisabled(source);
                assertTrue(calls.isEmpty(), "Unavailable actions must not invoke their handlers");
                item(source, "Connect").doClick(0);
                assertEquals(List.of(DatabaseExplorerContextMenu.Action.CONNECT), calls);
                var schema = DatabaseExplorerContextMenu.create(TreeNodeData.database(config, "PUBLIC"), false, calls::add);
                assertEnabled(schema, true, "Refresh tables");
                assertEnabled(schema, false, "Open SQL console", "Create table…");
                for (var item : items(schema)) if (item.getText().startsWith("Drop ")) assertFalse(item.isEnabled());
            }
        });
    }

    @Test void offlineTableMenusDisableLiveActionsButKeepCachedSqlGeneration() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var type : DatabaseType.values()) {
                var config = new ConnectionConfig(type, "Offline");
                var metadata = new TableMetadata(null, "PUBLIC", "products", "TABLE");
                metadata.addColumn(new ColumnMetadata("id", "INTEGER", java.sql.Types.INTEGER, 10, 0, false, true, false, null));
                var data = TreeNodeData.table(config, "PUBLIC", metadata); data.setCached(true);
                var calls = new ArrayList<DatabaseExplorerContextMenu.Action>();
                var menu = DatabaseExplorerContextMenu.create(data, false, calls::add);
                assertEnabled(menu, false, "Open data editor", "Open in SQL console", "Alter table…", "Truncate table…", "Drop table…");
                assertEnabled(menu, true, "Generate SQL", "SELECT Statement", "INSERT Statement");
                clickDisabled(menu); assertTrue(calls.isEmpty());
                item(menu, "SELECT Statement").doClick(0);
                item(menu, "INSERT Statement").doClick(0);
                assertEquals(List.of(DatabaseExplorerContextMenu.Action.GENERATE_SELECT, DatabaseExplorerContextMenu.Action.GENERATE_INSERT), calls);
                assertFalse(DatabaseConnectionManager.getInstance().isConnected(config.getId()));
                var sql = DdlService.getInstance().buildSelectSql(config, "PUBLIC", metadata.getName(), List.of("id"));
                var preview = GeneratedSqlDialog.sqlView(sql);
                assertEquals(sql, preview.getText());
                assertFalse(preview.isEditable(), "Offline SQL must open in a read-only preview");
                assertTrue(preview.isEnabled(), "SQL remains selectable for copying");
            }
        });
    }

    @Test void reconnectRestoresSupportedActionsAndUnknownColumnsDoNotGenerateAnEmptyOfflineInsert() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var config = new ConnectionConfig(DatabaseType.HSQLDB, "Source");
            var table = TreeNodeData.table(config, "PUBLIC", new TableMetadata(null, "PUBLIC", "products", "TABLE"));
            var offline = DatabaseExplorerContextMenu.create(table, false, action -> {});
            assertEnabled(offline, true, "SELECT Statement");
            assertEnabled(offline, false, "INSERT Statement");
            var live = DatabaseExplorerContextMenu.create(table, true, action -> {});
            assertEnabled(live, true, "Open data editor", "Open in SQL console", "Alter table…", "Truncate table…", "Drop table…", "INSERT Statement");
            var source = DatabaseExplorerContextMenu.create(TreeNodeData.connection(config, true), true, action -> {});
            assertEnabled(source, true, "Refresh connection", "Disconnect", "Open SQL console", "Create schema…");
            var view = TreeNodeData.table(config, "PUBLIC", new TableMetadata(null, "PUBLIC", "report", "VIEW"));
            var viewMenu = DatabaseExplorerContextMenu.create(view, false, action -> {});
            assertFalse(items(viewMenu).stream().anyMatch(item -> item.getText().equals("Drop table…")));
            assertEnabled(viewMenu, false, "Open data editor", "Open in SQL console");
            assertEnabled(viewMenu, true, "SELECT Statement");
        });
    }

    private static List<JMenuItem> items(JPopupMenu menu) {
        var result = new ArrayList<JMenuItem>();
        for (Component component : menu.getComponents()) if (component instanceof JMenuItem item) {
            result.add(item);
            if (item instanceof JMenu submenu) result.addAll(items(submenu.getPopupMenu()));
        }
        return result;
    }
    private static JMenuItem item(JPopupMenu menu, String text) {
        return items(menu).stream().filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
    }
    private static void assertEnabled(JPopupMenu menu, boolean enabled, String... labels) {
        for (String label : labels) assertEquals(enabled, item(menu, label).isEnabled(), label);
    }
    private static void clickDisabled(JPopupMenu menu) {
        for (var item : items(menu)) if (!item.isEnabled()) item.doClick(0);
    }
}
