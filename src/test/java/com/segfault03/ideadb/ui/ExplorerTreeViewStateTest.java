package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.sql.Types;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExplorerTreeViewStateTest {
    static final class Fixture {
        final ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "Local");
        final DefaultMutableTreeNode root = node(TreeNodeData.root()), group = node(TreeNodeData.sourceType(DatabaseType.HSQLDB));
        final DefaultMutableTreeNode connection = node(TreeNodeData.connection(config, true));
        final DatabaseSourceTreeModel model = new DatabaseSourceTreeModel(root);
        final JTree tree = new JTree(model);
        final JScrollPane scroll = new JScrollPane(tree);
        DefaultMutableTreeNode schema, tables, table, columns, column;
        Fixture() {
            root.add(group); group.add(connection); rebuild(1);
            tree.setRowHeight(20); scroll.setSize(260, 150); layout();
            expand(root, group, connection, schema, tables, table, columns);
        }
        void rebuild(int count) {
            connection.removeAllChildren();
            schema = add(connection, TreeNodeData.database(config, "PUBLIC"));
            tables = add(schema, TreeNodeData.tablesFolder(config, "PUBLIC", count));
            var metadata = new TableMetadata(null, "PUBLIC", "customers", "TABLE");
            table = add(tables, TreeNodeData.table(config, "PUBLIC", metadata));
            columns = add(table, TreeNodeData.columnsFolder(config, "PUBLIC", metadata, count));
            column = add(columns, TreeNodeData.column(config, "PUBLIC", metadata,
                    new ColumnMetadata("email", "VARCHAR", Types.VARCHAR, count * 100, 0, true, false, false, null)));
            model.nodeStructureChanged(connection);
        }
        void expand(DefaultMutableTreeNode... nodes) { for (var node : nodes) tree.expandPath(path(node)); layout(); }
        void layout() { scroll.doLayout(); tree.setSize(Math.max(tree.getPreferredSize().width, scroll.getViewport().getWidth()), tree.getPreferredSize().height); scroll.getViewport().doLayout(); }
        void refresh(int count) { ExplorerTreeViewState.preserving(tree, () -> rebuild(count)); layout(); }
    }
    static DefaultMutableTreeNode node(TreeNodeData data) { return new DefaultMutableTreeNode(data); }
    static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, TreeNodeData data) { var node = node(data); parent.add(node); return node; }
    static TreePath path(DefaultMutableTreeNode node) { return new TreePath(node.getPath()); }

    @Test void startupRefreshDoesNotOpenACollapsedCachedConnection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture(); ((TreeNodeData)f.connection.getUserObject()).setCached(true);
            f.tree.collapsePath(path(f.connection)); f.tree.setSelectionPath(path(f.connection));
            f.refresh(2);
            assertFalse(f.tree.isExpanded(path(f.connection)));
            assertEquals(path(f.connection), f.tree.getSelectionPath());
        });
    }
    @Test void recreatedFoldersAndChangedCountsRetainExpansionAndColumnSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture(); f.tree.setSelectionPath(path(f.column));
            f.refresh(7);
            for (var node : List.of(f.schema, f.tables, f.table, f.columns)) assertTrue(f.tree.isExpanded(path(node)));
            assertEquals(path(f.column), f.tree.getSelectionPath());
        });
    }
    @Test void schemaRefreshDoesNotOpenTablesOrColumnsThatWereCollapsed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture(); f.tree.collapsePath(path(f.columns)); f.tree.collapsePath(path(f.tables));
            f.tree.setSelectionPath(path(f.schema)); f.refresh(9);
            assertTrue(f.tree.isExpanded(path(f.schema)));
            assertFalse(f.tree.isExpanded(path(f.tables))); assertFalse(f.tree.isExpanded(path(f.columns)));
            assertEquals(path(f.schema), f.tree.getSelectionPath());
        });
    }
    @Test void aUserCollapseAndSelectionChangeDuringAReadWinOverTheOldView() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture(); f.tree.setSelectionPath(path(f.column));
            // A background request starts here; its result is applied only after these user actions.
            f.tree.collapsePath(path(f.schema)); f.tree.setSelectionPath(path(f.group));
            f.refresh(3);
            assertFalse(f.tree.isExpanded(path(f.schema)));
            assertEquals(path(f.group), f.tree.getSelectionPath());
        });
    }
    @Test void deletedSelectionFallsBackToTheNearestSurvivingFolder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture(); f.tree.setSelectionPath(path(f.column));
            ExplorerTreeViewState.preserving(f.tree, () -> { f.columns.removeAllChildren(); f.model.nodeStructureChanged(f.columns); });
            assertEquals(path(f.columns), f.tree.getSelectionPath());
        });
    }
    @Test void rootReloadKeepsRenamedConnections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture(); f.tree.setSelectionPath(path(f.column));
            ExplorerTreeViewState.preserving(f.tree, () -> {
                f.config.setName("Renamed");
                f.connection.setUserObject(TreeNodeData.connection(f.config, true));
                f.rebuild(4); f.model.reload();
            });
            assertEquals(path(f.column), f.tree.getSelectionPath());
            assertTrue(f.tree.isExpanded(path(f.columns)));
        });
    }
    @Test void insertingRowsAboveTheViewportPreservesTheLogicalTopRowAndOffset() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture();
            var metadata = new TableMetadata(null, "PUBLIC", "inserted", "TABLE");
            for (int i = 0; i < 35; i++) add(f.tables, TreeNodeData.table(f.config, "PUBLIC",
                    new TableMetadata(null, "PUBLIC", "record_" + i, "TABLE")));
            f.model.nodeStructureChanged(f.tables); f.expand(f.tables); f.layout();
            var anchor = (DefaultMutableTreeNode)f.tables.getChildAt(15);
            int oldY = f.tree.getPathBounds(path(anchor)).y;
            f.scroll.getViewport().setViewPosition(new Point(0, oldY + 3));
            ExplorerTreeViewState.preserving(f.tree, () -> {
                f.tables.insert(node(TreeNodeData.table(f.config, "PUBLIC", metadata)), 0);
                f.model.nodeStructureChanged(f.tables);
            }); f.layout();
            int y = f.scroll.getViewport().getViewPosition().y;
            assertEquals(f.tree.getPathBounds(path(anchor)).y + 3, y);
        });
    }
    @Test void filteredSourcesAndCollapsedRootAreNotRevealedByRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture();
            var other = add(f.root, TreeNodeData.sourceType(DatabaseType.MYSQL));
            var config = new ConnectionConfig(DatabaseType.MYSQL, "Local");
            add(other, TreeNodeData.connection(config, true));
            f.model.setVisibleTypes(java.util.Set.of(DatabaseType.HSQLDB));
            f.tree.collapsePath(path(f.root)); f.refresh(2);
            assertFalse(f.tree.isExpanded(path(f.root)));
            assertEquals(1, f.tree.getRowCount());
            assertEquals(java.util.Set.of(DatabaseType.HSQLDB), f.model.getVisibleTypes());
        });
    }
    @Test void cacheFallbackAndCascadedRefreshesKeepTheSameViewWithoutLazyLoadSideEffects() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var f = new Fixture(); f.tree.setSelectionPath(path(f.column));
            var accidentalLoads = new java.util.concurrent.atomic.AtomicInteger();
            f.tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
                public void treeExpanded(javax.swing.event.TreeExpansionEvent e) { if (!ExplorerTreeViewState.isRestoring(f.tree)) accidentalLoads.incrementAndGet(); }
                public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) {}
            });
            var metadata = new TableMetadata(null, "PUBLIC", "customers", "TABLE");
            var col = new ColumnMetadata("email", "VARCHAR", Types.VARCHAR, 200, 0, true, false, false, null);
            var snapshot = new ExplorerMetadataSnapshot(List.of(new ExplorerMetadataSnapshot.Namespace("PUBLIC",
                    List.of(new ExplorerMetadataSnapshot.Table(metadata, List.of(col), true)), true)), List.of(), 3);
            ExplorerTreeViewState.preserving(f.tree, () -> { CachedMetadataTree.restore(f.connection, snapshot); f.model.nodeStructureChanged(f.connection); });
            assertEquals("email", ((TreeNodeData)((DefaultMutableTreeNode)f.tree.getSelectionPath().getLastPathComponent()).getUserObject()).getColumnMetadata().getName());
            f.refresh(2); f.refresh(3);
            assertEquals(path(f.column), f.tree.getSelectionPath()); assertEquals(0, accidentalLoads.get());
        });
    }
}
