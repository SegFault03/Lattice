package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.TableMetadata;
import org.junit.jupiter.api.Test;

import javax.swing.*;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseExplorerHintTest {
    @Test
    void hintOnlyAppearsForTablesAndDisappearsWhenSelectionChangesOrClears() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DatabaseExplorerHint hint = new DatabaseExplorerHint();
            assertFalse(hint.isVisible());
            ConnectionConfig config = new ConnectionConfig();
            TableMetadata table = new TableMetadata("shop", null, "products", "TABLE");
            hint.setSelection(TreeNodeData.table(config, "shop", table));
            assertTrue(hint.isVisible());
            assertTrue(((JLabel) hint.getComponent(0)).getText().contains("table"));
            hint.setSelection(TreeNodeData.tablesFolder(config, "shop", 1));
            assertFalse(hint.isVisible());
            hint.setSelection(TreeNodeData.table(config, "shop", table));
            hint.setSelection(TreeNodeData.connection(config, false));
            assertFalse(hint.isVisible());
            TableMetadata view = new TableMetadata("shop", null, "product_view", "VIEW");
            hint.setSelection(TreeNodeData.table(config, "shop", view));
            assertFalse(hint.isVisible(), "The table hint is hidden for views and other node types");
            hint.setSelection(TreeNodeData.table(config, "shop", table));
            assertTrue(hint.isVisible());
            hint.setSelection(null);
            assertFalse(hint.isVisible(), "Clearing selection or reloading connections removes the entire footer");
            hint.setSelection(TreeNodeData.root());
            assertFalse(hint.isVisible());
        });
    }
}
