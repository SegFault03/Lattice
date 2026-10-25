package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseExplorerSearchControllerTest {
    @Test void backgroundRefreshDoesNotRevealSearchMatchesAgain() throws Exception {
        var config = new ConnectionConfig(DatabaseType.HSQLDB, "Search refresh");
        config.setDatabaseName("search_refresh_" + System.nanoTime());
        config.setHsqlMode(com.segfault03.ideadb.model.HsqlMode.MEM);
        var manager = DatabaseConnectionManager.getInstance();
        manager.getConnection(config);
        var result = new java.util.concurrent.CompletableFuture<Void>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var root = new DefaultMutableTreeNode(TreeNodeData.root());
                var group = new DefaultMutableTreeNode(TreeNodeData.sourceType(DatabaseType.HSQLDB));
                var source = new DefaultMutableTreeNode(TreeNodeData.connection(config, true));
                root.add(group); group.add(source);
                var model = new DatabaseSourceTreeModel(root);
                var tree = new JTree(model);
                var bar = new DatabaseExplorerSearchBar(query -> {}, () -> {}, () -> {}, () -> {});
                var controller = new DatabaseExplorerSearchController(tree, model, bar);
                controller.changed(new DatabaseExplorerSearch("search", Set.of(DatabaseExplorerSearch.Category.DATA_SOURCE)));
                var groupPath = new javax.swing.tree.TreePath(group.getPath());
                assertTrue(tree.isExpanded(groupPath));
                tree.collapsePath(groupPath);
                ExplorerTreeViewState.preserving(tree, () -> model.nodeStructureChanged(source));
                controller.reset();
                var timer = new Timer(150, event -> {
                    try {
                        assertFalse(tree.isExpanded(groupPath), "A delayed search refresh must respect the collapsed group");
                        assertFalse(((Map<?, ?>)tree.getClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY)).isEmpty());
                        controller.changed(new DatabaseExplorerSearch("", Set.of()));
                        result.complete(null);
                    } catch (Throwable failure) { result.completeExceptionally(failure); }
                    finally { controller.close(); }
                });
                timer.setRepeats(false); timer.start();
            });
            result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { manager.closeConnection(config.getId()); }
    }
    @Test void hsqlSearchRespectsConnectionVisibilityAndRestoresExpansion() throws Exception { verifySearch(DatabaseType.HSQLDB); }
    @Test void h2SearchRespectsConnectionVisibilityAndRestoresExpansion() throws Exception { verifySearch(DatabaseType.H2); }
    private void verifySearch(DatabaseType type) throws Exception {
        ConnectionConfig connected = new ConnectionConfig(type, "Search connected");
        connected.setDatabaseName("search_controller_" + System.nanoTime());
        connected.setHsqlMode(com.segfault03.ideadb.model.HsqlMode.MEM);
        ConnectionConfig disconnected = new ConnectionConfig(DatabaseType.MYSQL, "Search disconnected");
        var manager = DatabaseConnectionManager.getInstance();
        manager.getConnection(connected);
        try {
            SwingUtilities.invokeAndWait(() -> {
                DefaultMutableTreeNode root = new DefaultMutableTreeNode(TreeNodeData.root());
                DefaultMutableTreeNode hsql = new DefaultMutableTreeNode(TreeNodeData.sourceType(type));
                DefaultMutableTreeNode mysql = new DefaultMutableTreeNode(TreeNodeData.sourceType(DatabaseType.MYSQL));
                root.add(hsql); root.add(mysql);
                TreeNodeData online = TreeNodeData.connection(connected, true), offline = TreeNodeData.connection(disconnected, false);
                hsql.add(new DefaultMutableTreeNode(online)); mysql.add(new DefaultMutableTreeNode(offline));
                DatabaseSourceTreeModel model = new DatabaseSourceTreeModel(root);
                JTree tree = new JTree(model);
                tree.expandRow(0);
                tree.collapseRow(1);
                DatabaseExplorerSearchBar bar = new DatabaseExplorerSearchBar(query -> {}, () -> {}, () -> {}, () -> {});
                try (DatabaseExplorerSearchController controller = new DatabaseExplorerSearchController(tree, model, bar)) {
                    var query = new DatabaseExplorerSearch("search", Set.of(DatabaseExplorerSearch.Category.DATA_SOURCE));
                    controller.changed(query);
                    Map<?, ?> highlights = (Map<?, ?>) tree.getClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY);
                    assertTrue(highlights.containsKey(online));
                    assertFalse(highlights.containsKey(offline));
                    assertFalse(manager.isConnected(disconnected.getId()), "Name search must not connect a saved offline source");
                    assertTrue(tree.isExpanded(1));
                    controller.changed(new DatabaseExplorerSearch("", Set.of()));
                    assertFalse(tree.isExpanded(1), "Clearing the search restores the original collapsed source group");
                    model.setVisibleTypes(Set.of(DatabaseType.MYSQL));
                    controller.changed(query);
                    assertTrue(((Map<?, ?>) tree.getClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY)).isEmpty());
                }
            });
        } finally { manager.closeConnection(connected.getId()); }
    }
}
