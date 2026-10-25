package com.segfault03.ideadb.ui;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Logical tree locations survive new node instances, renamed sources and folder counts. */
final class ExplorerTreeViewState {
    private static final String RESTORING = "lattice.explorer.restoringView";
    private record Key(TreeNodeData.NodeType type, String identity) {}
    private final List<List<Key>> expanded;
    private final List<Key> selection, top;
    private final Point position;
    private final int topOffset;

    private ExplorerTreeViewState(List<List<Key>> expanded, List<Key> selection, List<Key> top, Point position, int topOffset) {
        this.expanded = expanded; this.selection = selection; this.top = top;
        this.position = position; this.topOffset = topOffset;
    }
    static boolean isRestoring(JTree tree) { return Boolean.TRUE.equals(tree.getClientProperty(RESTORING)); }
    static ExplorerTreeViewState capture(JTree tree) {
        var root = new TreePath(((DefaultMutableTreeNode)tree.getModel().getRoot()).getPath());
        var paths = tree.getExpandedDescendants(root);
        List<List<Key>> expanded = paths == null ? List.of() : Collections.list(paths).stream()
                .sorted(Comparator.comparingInt(TreePath::getPathCount)).map(ExplorerTreeViewState::identity).toList();
        Point position = tree.getParent() instanceof JViewport viewport ? new Point(viewport.getViewPosition()) : tree.getVisibleRect().getLocation();
        TreePath top = tree.getClosestPathForLocation(0, position.y);
        Rectangle bounds = top == null ? null : tree.getPathBounds(top);
        return new ExplorerTreeViewState(expanded, identity(tree.getSelectionPath()), identity(top), position,
                bounds == null ? 0 : position.y - bounds.y);
    }
    static void preserving(JTree tree, Runnable update) {
        var state = capture(tree);
        Object previous = tree.getClientProperty(RESTORING); tree.putClientProperty(RESTORING, true);
        try { update.run(); state.restore(tree); }
        finally { tree.putClientProperty(RESTORING, previous); }
    }
    void restore(JTree tree) {
        Object previous = tree.getClientProperty(RESTORING); tree.putClientProperty(RESTORING, true);
        boolean expandsSelection = tree.getExpandsSelectedPaths(), scrollsOnExpand = tree.getScrollsOnExpand();
        tree.setExpandsSelectedPaths(false); tree.setScrollsOnExpand(false);
        try {
            for (int row = tree.getRowCount() - 1; row >= 0; row--) tree.collapseRow(row);
            for (List<Key> path : expanded) {
                TreePath restored = resolve(tree, path, false);
                if (restored != null) tree.expandPath(restored);
            }
            TreePath restoredSelection = resolve(tree, selection, true);
            if (restoredSelection == null) tree.clearSelection(); else tree.setSelectionPath(restoredSelection);
            if (tree.getParent() instanceof JViewport viewport) {
                TreePath anchor = resolve(tree, top, true);
                Rectangle bounds = anchor == null ? null : tree.getPathBounds(anchor);
                int y = bounds == null ? position.y : bounds.y + topOffset;
                Dimension preferred = tree.getPreferredSize(), extent = viewport.getExtentSize();
                viewport.setViewPosition(new Point(Math.max(0, Math.min(position.x, preferred.width - extent.width)),
                        Math.max(0, Math.min(y, preferred.height - extent.height))));
            }
        } finally {
            tree.setExpandsSelectedPaths(expandsSelection); tree.setScrollsOnExpand(scrollsOnExpand);
            tree.putClientProperty(RESTORING, previous);
        }
    }
    private static List<Key> identity(TreePath path) {
        if (path == null) return List.of();
        return Arrays.stream(path.getPath()).map(node -> key((DefaultMutableTreeNode)node)).toList();
    }
    private static Key key(DefaultMutableTreeNode node) {
        var data = (TreeNodeData)node.getUserObject();
        String identity = switch (data.getType()) {
            case ROOT, TABLES_FOLDER, VIEWS_FOLDER, COLUMNS_FOLDER -> "";
            case SOURCE_TYPE -> data.getDatabaseType().name();
            case CONNECTION -> data.getConnectionConfig().getId();
            case DATABASE -> data.getDatabaseName();
            case TABLE, VIEW -> data.getTableMetadata().getName();
            case COLUMN -> data.getColumnMetadata().getName();
            default -> data.getName();
        };
        return new Key(data.getType(), identity);
    }
    private static TreePath resolve(JTree tree, List<Key> keys, boolean nearest) {
        if (keys.isEmpty()) return null;
        Object current = tree.getModel().getRoot();
        if (!key((DefaultMutableTreeNode)current).equals(keys.get(0))) return null;
        TreePath path = new TreePath(current);
        for (int depth = 1; depth < keys.size(); depth++) {
            Object match = null;
            for (int i = 0; i < tree.getModel().getChildCount(current); i++) {
                Object child = tree.getModel().getChild(current, i);
                if (key((DefaultMutableTreeNode)child).equals(keys.get(depth))) { match = child; break; }
            }
            if (match == null) return nearest ? path : null;
            path = path.pathByAddingChild(match); current = match;
        }
        return path;
    }
}
