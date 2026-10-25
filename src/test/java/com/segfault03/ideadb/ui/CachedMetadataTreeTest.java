package com.segfault03.ideadb.ui;

import com.intellij.ui.SimpleTextAttributes;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.state.MetadataCacheState;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CachedMetadataTreeTest {
    @Test void disconnectedTreeRetainsSchemasTablesViewsAndKeysAsGreyBrowsableNodes() throws Exception {
        var config = new ConnectionConfig(DatabaseType.HSQLDB, "Offline source");
        var cache = new MetadataCacheState();
        cache.databasesRefreshed(config, List.of("PUBLIC", "NOT_REFRESHED"));
        cache.tablesRefreshed(config, "PUBLIC", List.of(new TableMetadata(null, "PUBLIC", "CHILD", "TABLE"),
                new TableMetadata(null, "PUBLIC", "REPORT", "VIEW")));
        cache.columnsRefreshed(config, "PUBLIC", "CHILD", List.of(new ColumnMetadata("ID", "INTEGER", java.sql.Types.INTEGER, 10, 0, false, true, true, null)));
        SwingUtilities.invokeAndWait(() -> {
            var source = new DefaultMutableTreeNode(TreeNodeData.connection(config, true));
            CachedMetadataTree.restore(source, cache.snapshot(config));
            var sourceData = (TreeNodeData) source.getUserObject();
            assertFalse(sourceData.isConnected());
            assertTrue(sourceData.isLoaded());
            var nodes = source.depthFirstEnumeration();
            var renderer = new DatabaseTreeCellRenderer();
            boolean keyFound = false, tableFound = false, viewFound = false;
            while (nodes.hasMoreElements()) {
                var node = (DefaultMutableTreeNode) nodes.nextElement();
                var data = (TreeNodeData) node.getUserObject();
                if (node == source) continue;
                assertTrue(data.isCached());
                assertTrue(data.isLoaded(), "Expanding cached metadata must not open a connection");
                assertNotEquals(TreeNodeData.NodeType.LOADING, data.getType());
                renderer.getTreeCellRendererComponent(new JTree(), node, false, false, node.isLeaf(), 0, false);
                var fragments = renderer.iterator();
                while (fragments.hasNext()) {
                    fragments.next();
                    assertEquals(SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor(), fragments.getTextAttributes().getFgColor());
                }
                assertTrue(renderer.getToolTipText().contains("Cached metadata · Disconnected"));
                assertEquals(renderer.getToolTipText(), renderer.getAccessibleContext().getAccessibleDescription());
                if (data.getType() == TreeNodeData.NodeType.COLUMN) keyFound = data.getColumnMetadata().isPrimaryKey();
                if (data.getType() == TreeNodeData.NodeType.TABLE) tableFound = true;
                if (data.getType() == TreeNodeData.NodeType.VIEW) viewFound = true;
            }
            assertTrue(keyFound); assertTrue(tableFound); assertTrue(viewFound);
            var root = new DefaultMutableTreeNode(TreeNodeData.root());
            var group = new DefaultMutableTreeNode(TreeNodeData.sourceType(config.getType()));
            root.add(group); group.add(source);
            var tree = new JTree(new DatabaseSourceTreeModel(root));
            var bar = new DatabaseExplorerSearchBar(query -> {}, () -> {}, () -> {}, () -> {});
            try (var search = new DatabaseExplorerSearchController(tree, (DatabaseSourceTreeModel) tree.getModel(), bar)) {
                search.changed(new DatabaseExplorerSearch("CHILD", java.util.Set.of()));
                var matches = (java.util.Map<?, ?>) tree.getClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY);
                assertTrue(matches.keySet().stream().anyMatch(value -> value instanceof TreeNodeData data && data.getName().equals("CHILD")));
                assertFalse(com.segfault03.ideadb.service.DatabaseConnectionManager.getInstance().isConnected(config.getId()));
            }
            CachedMetadataTree.restore(source, null);
            assertFalse(sourceData.isLoaded(), "A new source without a cache must still connect on expansion");
        });
    }
}
