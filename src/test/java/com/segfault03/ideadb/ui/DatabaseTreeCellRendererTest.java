package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.intellij.ui.RowIcon;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTreeCellRendererTest {
    @Test void everyOccurrenceIsHighlightedAndReusingTheRendererClearsOldHighlights() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "Search demo");
            TreeNodeData data = TreeNodeData.table(config, "PUBLIC", new com.segfault03.ideadb.model.TableMetadata(null, "PUBLIC", "customer_CUSTOMER", "TABLE"));
            JTree tree = new JTree();
            tree.putClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY, java.util.Map.of(data,
                    new DatabaseExplorerSearch("customer", java.util.Set.of()).ranges(data.getName())));
            DatabaseTreeCellRenderer renderer = new DatabaseTreeCellRenderer();
            renderer.getTreeCellRendererComponent(tree, new DefaultMutableTreeNode(data), false, false, true, 0, false);
            assertEquals(3, renderer.getFragmentCount());
            java.util.List<String> highlighted = new java.util.ArrayList<>();
            var fragments = renderer.iterator();
            while (fragments.hasNext()) {
                fragments.next();
                if ((fragments.getTextAttributes().getStyle() & com.intellij.ui.SimpleTextAttributes.STYLE_SEARCH_MATCH) != 0)
                    highlighted.add(fragments.getFragment());
            }
            assertEquals(java.util.List.of("customer", "CUSTOMER"), highlighted);
            tree.putClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY, java.util.Map.of());
            renderer.getTreeCellRendererComponent(tree, new DefaultMutableTreeNode(data), false, false, true, 0, false);
            assertEquals(1, renderer.getFragmentCount());
            fragments = renderer.iterator();
            fragments.next();
            assertEquals(0, fragments.getTextAttributes().getStyle() & com.intellij.ui.SimpleTextAttributes.STYLE_SEARCH_MATCH);
        });
    }
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
