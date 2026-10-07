package com.segfault03.ideadb.ui;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.*;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;

/** Production explorer widgets with a fixture tree instead of project services. */
public final class ExplorerPreview {
    public static JPanel create(ConnectionConfig config, boolean empty) {
        JPanel panel = new JPanel(new BorderLayout());
        DatabaseExplorerToolbar toolbar = new DatabaseExplorerToolbar(c -> {}, () -> {},
                () -> {}, () -> {}, () -> {}, () -> {});
        panel.add(toolbar, BorderLayout.NORTH);
        if (empty) {
            panel.add(new EmptyConnectionPanel(() -> {}), BorderLayout.CENTER);
            return panel;
        }
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(TreeNodeData.root());
        DefaultMutableTreeNode connection = node(root, TreeNodeData.connection(config, true));
        DefaultMutableTreeNode database = node(connection, TreeNodeData.database(config, "shop"));
        DefaultMutableTreeNode tables = node(database, TreeNodeData.tablesFolder(config, "shop", 3));
        TableMetadata customers = new TableMetadata("shop", null, "customers", "TABLE");
        DefaultMutableTreeNode selected = node(tables, TreeNodeData.table(config, "shop", customers));
        DefaultMutableTreeNode columns = node(selected, TreeNodeData.columnsFolder(config, "shop", customers, 5));
        node(columns, TreeNodeData.column(config, "shop", customers,
                new ColumnMetadata("id", "BIGINT", java.sql.Types.BIGINT, 0, 0, false, true, true, null)));
        for (String name : new String[]{"name", "email", "status", "balance"})
            node(columns, TreeNodeData.column(config, "shop", customers,
                    new ColumnMetadata(name, name.equals("balance") ? "DECIMAL" : "VARCHAR",
                            name.equals("balance") ? java.sql.Types.DECIMAL : java.sql.Types.VARCHAR,
                            name.equals("balance") ? 10 : name.equals("email") ? 180 : name.equals("status") ? 20 : 120,
                            name.equals("balance") ? 2 : 0, true, false, false, null)));
        for (String name : new String[]{"orders", "products"})
            node(tables, TreeNodeData.table(config, "shop", new TableMetadata("shop", null, name, "TABLE")));
        DefaultMutableTreeNode views = node(database, TreeNodeData.viewsFolder(config, "shop", 1));
        node(views, TreeNodeData.table(config, "shop", new TableMetadata("shop", null, "active_customers", "VIEW")));
        node(connection, TreeNodeData.database(config, "analytics"));
        ConnectionConfig scratch = new ConnectionConfig(DatabaseType.HSQLDB, "Scratch database");
        node(root, TreeNodeData.connection(scratch, false));
        // IntelliJ's Tree requires application services; JTree shares its Swing
        // layout contract and still uses Lattice's production node renderer.
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setCellRenderer(new DatabaseTreeCellRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setBorder(JBUI.Borders.empty(6, 8));
        for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
        tree.setSelectionPath(new TreePath(selected.getPath()));
        JBScrollPane scroll = new JBScrollPane(tree);
        scroll.setBorder(JBUI.Borders.empty());
        panel.add(scroll, BorderLayout.CENTER);
        TreeNodeData selection = (TreeNodeData)selected.getUserObject();
        toolbar.setHasConnections(true);
        toolbar.setSelection(selection);
        DatabaseExplorerHint hint = new DatabaseExplorerHint();
        hint.setSelection(selection);
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private static DefaultMutableTreeNode node(DefaultMutableTreeNode parent, TreeNodeData data) {
        DefaultMutableTreeNode child = new DefaultMutableTreeNode(data);
        parent.add(child);
        return child;
    }
}
