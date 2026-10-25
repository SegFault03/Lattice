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
                () -> {}, () -> {}, () -> {}, c -> {}, () -> {
                    DatabaseExplorerSearchBar bar = (DatabaseExplorerSearchBar) panel.getClientProperty("lattice.preview.explorer.search");
                    bar.setVisible(!bar.isVisible());
                    panel.revalidate();
                }, () -> {});
        panel.putClientProperty("lattice.preview.explorer.actions", toolbar);
        DatabaseExplorerSearchBar bar = new DatabaseExplorerSearchBar(query -> updateSearch(panel, query), () -> {}, () -> {}, () -> {});
        panel.putClientProperty("lattice.preview.explorer.search", bar);
        panel.add(new DatabaseExplorerHeader(toolbar.createComponent(panel), bar), BorderLayout.NORTH);
        if (empty) {
            panel.add(new EmptyConnectionPanel(c -> {}), BorderLayout.CENTER);
            return panel;
        }
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(TreeNodeData.root());
        java.util.Set<DatabaseType> sourceTypes = new java.util.TreeSet<>(
                java.util.Comparator.comparing(DatabaseType::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        sourceTypes.addAll(java.util.Arrays.asList(DatabaseType.values()));
        java.util.Map<DatabaseType, DefaultMutableTreeNode> sourceGroups = new java.util.EnumMap<>(DatabaseType.class);
        for (DatabaseType type : sourceTypes) {
            DefaultMutableTreeNode group = node(root, TreeNodeData.sourceType(type));
            sourceGroups.put(type, group);
        }
        DefaultMutableTreeNode sourceGroup = sourceGroups.get(config.getType());
        DefaultMutableTreeNode connection = node(sourceGroup, TreeNodeData.connection(config, true));
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
        for (DatabaseType type : sourceTypes) {
            if (type == config.getType()) continue;
            String name = switch (type) {
                case MYSQL -> "Analytics server";
                case HSQLDB -> "Scratch database";
                case POSTGRESQL -> "Reporting cluster";
                case H2 -> "Local H2 database";
            };
            ConnectionConfig other = new ConnectionConfig(type, name);
            node(sourceGroups.get(type), TreeNodeData.connection(other, false))
                    .add(new DefaultMutableTreeNode(TreeNodeData.placeholder("")));
        }
        // IntelliJ's Tree requires application services; JTree shares its Swing
        // layout contract and still uses Lattice's production node renderer.
        JTree tree = new JTree(new DatabaseSourceTreeModel(root));
        panel.putClientProperty("lattice.preview.explorer.tree", tree);
        tree.setCellRenderer(new DatabaseTreeCellRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setRootVisible(true);
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.setShowsRootHandles(true);
        tree.setBorder(JBUI.Borders.empty(6, 8));
        tree.expandPath(new TreePath(root.getPath()));
        for (DefaultMutableTreeNode group : sourceGroups.values()) tree.expandPath(new TreePath(group.getPath()));
        tree.expandPath(new TreePath(connection.getPath()));
        tree.expandPath(new TreePath(database.getPath()));
        tree.expandPath(new TreePath(tables.getPath()));
        tree.expandPath(new TreePath(selected.getPath()));
        tree.expandPath(new TreePath(columns.getPath()));
        tree.expandPath(new TreePath(views.getPath()));
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

    private static void updateSearch(JPanel panel, DatabaseExplorerSearch query) {
        JTree tree = (JTree) panel.getClientProperty("lattice.preview.explorer.tree");
        if (tree == null) return;
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        java.util.Map<TreeNodeData, java.util.List<DatabaseExplorerSearch.Range>> matches = new java.util.IdentityHashMap<>();
        var nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            TreeNodeData data = (TreeNodeData) node.getUserObject();
            if (query.matches(data)) {
                matches.put(data, query.ranges(query.name(data)));
                if (node.getParent() != null) tree.expandPath(new TreePath(((DefaultMutableTreeNode) node.getParent()).getPath()));
            }
        }
        tree.putClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY, matches);
        ((DatabaseExplorerSearchBar) panel.getClientProperty("lattice.preview.explorer.search")).setResults(matches.size(), false, null);
        tree.repaint();
    }

    public static JComponent search(ConnectionConfig config, boolean focused) {
        JPanel panel = create(config, false);
        JTree tree = (JTree) panel.getClientProperty("lattice.preview.explorer.tree");
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            TreeNodeData data = (TreeNodeData) node.getUserObject();
            if (data.getType() == TreeNodeData.NodeType.TABLE && data.getName().equals("orders")) {
                DefaultMutableTreeNode columns = node(node, TreeNodeData.columnsFolder(config, "shop", data.getTableMetadata(), 2));
                node(columns, TreeNodeData.column(config, "shop", data.getTableMetadata(),
                        new ColumnMetadata("customer_id", "BIGINT", java.sql.Types.BIGINT, 0, 0, false, false, false, null).withForeignKey(true)));
                break;
            }
        }
        for (int row = tree.getRowCount() - 1; row >= 1; row--) tree.collapseRow(row);
        tree.clearSelection();
        DatabaseExplorerSearchBar bar = (DatabaseExplorerSearchBar) panel.getClientProperty("lattice.preview.explorer.search");
        bar.setVisible(true);
        if (focused) bar.setFilters(java.util.EnumSet.of(DatabaseExplorerSearch.Category.TABLE, DatabaseExplorerSearch.Category.KEY));
        bar.setText("customer");
        ((DatabaseExplorerToolbar) panel.getClientProperty("lattice.preview.explorer.actions")).setSearchVisible(true);
        return panel;
    }

    public static JComponent viewOnly(ConnectionConfig config, boolean showPicker) {
        JPanel panel = create(config, false);
        JTree tree = (JTree) panel.getClientProperty("lattice.preview.explorer.tree");
        DatabaseSourceTreeModel model = (DatabaseSourceTreeModel) tree.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        DefaultMutableTreeNode postgres = node(root, TreeNodeData.sourceType(DatabaseType.POSTGRESQL));
        ConnectionConfig reporting = new ConnectionConfig(DatabaseType.POSTGRESQL, "Reporting cluster");
        node(postgres, TreeNodeData.connection(reporting, false)).add(new DefaultMutableTreeNode(TreeNodeData.placeholder("")));
        java.util.Set<DatabaseType> visible = showPicker ? java.util.EnumSet.allOf(DatabaseType.class)
                : java.util.EnumSet.of(DatabaseType.MYSQL);
        model.setVisibleTypes(visible);
        tree.expandPath(new TreePath(root.getPath()));
        for (int i = 0; i < model.getChildCount(root); i++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) model.getChild(root, i);
            tree.expandPath(new TreePath(group.getPath()));
        }
        DatabaseExplorerToolbar toolbar = (DatabaseExplorerToolbar) panel.getClientProperty("lattice.preview.explorer.actions");
        toolbar.setSourceFilter(showPicker ? "All data sources" : "MySQL", !showPicker);
        if (!showPicker) return panel;
        JPanel picker = new JPanel(new BorderLayout(0, 0));
        JLabel title = new JLabel("View only");
        title.setBorder(JBUI.Borders.empty(8, 12));
        picker.add(title, BorderLayout.NORTH);
        picker.add(new DatabaseSourceFilterPopup(java.util.List.of(DatabaseType.HSQLDB, DatabaseType.MYSQL, DatabaseType.POSTGRESQL),
                java.util.EnumSet.of(DatabaseType.HSQLDB, DatabaseType.MYSQL), selected -> {}, () -> {}));
        picker.setBorder(JBUI.Borders.customLine(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR, 1));
        JLayeredPane layer = new JLayeredPane() {
            @Override public void doLayout() {
                panel.setBounds(0, 0, getWidth(), getHeight());
                picker.setBounds(JBUI.scale(20), JBUI.scale(48), picker.getPreferredSize().width, picker.getPreferredSize().height);
            }
        };
        layer.add(panel, JLayeredPane.DEFAULT_LAYER);
        layer.add(picker, JLayeredPane.POPUP_LAYER);
        return layer;
    }

    public static JPanel error(ConnectionConfig config) {
        JPanel panel = create(config, false);
        JTree tree = (JTree)((JScrollPane)((BorderLayout)panel.getLayout()).getLayoutComponent(BorderLayout.CENTER)).getViewport().getView();
        DefaultTreeModel model = (DefaultTreeModel)tree.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
        root.removeAllChildren();
        DefaultMutableTreeNode sourceGroup = node(root, TreeNodeData.sourceType(config.getType()));
        DefaultMutableTreeNode connection = node(sourceGroup, TreeNodeData.connection(config, false));
        node(connection, TreeNodeData.error("Connection failed · Refresh to retry", "Connection refused at localhost:3306. Check that the server is running."));
        model.reload();
        tree.expandPath(new TreePath(root.getPath()));
        tree.expandPath(new TreePath(sourceGroup.getPath()));
        tree.expandPath(new TreePath(connection.getPath()));
        TreeNodeData selection = (TreeNodeData)connection.getUserObject();
        tree.setSelectionPath(new TreePath(connection.getPath()));
        ((DatabaseExplorerToolbar)panel.getClientProperty("lattice.preview.explorer.actions")).setSelection(selection);
        for (Component component : panel.getComponents()) {
            if (component instanceof DatabaseExplorerHint hint) hint.setSelection(selection);
        }
        DefaultMutableTreeNode error = (DefaultMutableTreeNode)connection.getFirstChild();
        DatabaseTreeCellRenderer renderer = (DatabaseTreeCellRenderer)tree.getCellRenderer();
        renderer.getTreeCellRendererComponent(tree, error, false, false, true, 3, false);
        if (renderer.getToolTipText() == null || renderer.getIcon() == null)
            throw new AssertionError("Explorer error must expose an icon and diagnostic tooltip");
        renderer.getTreeCellRendererComponent(tree, root, false, true, false, 0, false);
        if (renderer.getToolTipText() != null) throw new AssertionError("Explorer diagnostic tooltip must reset");
        return panel;
    }

    private static DefaultMutableTreeNode node(DefaultMutableTreeNode parent, TreeNodeData data) {
        DefaultMutableTreeNode child = new DefaultMutableTreeNode(data);
        parent.add(child);
        return child;
    }
}
