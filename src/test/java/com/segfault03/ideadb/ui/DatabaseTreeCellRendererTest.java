package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.intellij.ui.RowIcon;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTreeCellRendererTest {
    @Test void compactConnectionRowsKeepFullIdentityAndStateInTheirTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "A connection name longer than the explorer");
            DatabaseTreeCellRenderer renderer = new DatabaseTreeCellRenderer();
            for (boolean connected : new boolean[]{true, false}) {
                renderer.getTreeCellRendererComponent(new JTree(), new DefaultMutableTreeNode(TreeNodeData.connection(config, connected)),
                        false, false, false, 0, false);
                String tooltip = renderer.getToolTipText();
                assertTrue(tooltip.startsWith(config.getName() + " · HSQLDB · "));
                assertTrue(tooltip.contains(connected ? "Connected" : "Disconnected"));
                assertInstanceOf(RowIcon.class, renderer.getIcon(), "State must be visible before the connection text");
                assertEquals(tooltip, renderer.getAccessibleContext().getAccessibleDescription());
            }
            renderer.getTreeCellRendererComponent(new JTree(), new DefaultMutableTreeNode(TreeNodeData.root()),
                    false, false, false, 0, false);
            assertNull(renderer.getAccessibleContext().getAccessibleDescription(), "Reusing the renderer must clear the previous connection state");
        });
    }
}
