package com.segfault03.ideadb.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.DatabaseTaskScope;
import com.segfault03.ideadb.service.DatabaseTaskService;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;

import javax.swing.*;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Coordinates live highlights, reveal/navigation, and cancellable metadata reads. */
final class DatabaseExplorerSearchController implements AutoCloseable {
    private final JTree tree;
    private final DatabaseSourceTreeModel model;
    private final DefaultMutableTreeNode root;
    private final DatabaseExplorerSearchBar bar;
    private final AtomicInteger generation = new AtomicInteger();
    private final Map<DefaultMutableTreeNode, Integer> indexed = new IdentityHashMap<>();
    private final Map<DefaultMutableTreeNode, List<String>> errors = new IdentityHashMap<>();
    private final Timer debounce, repaint;
    private DatabaseExplorerSearch query = new DatabaseExplorerSearch("", Set.of());
    private List<TreePath> matches = List.of();
    private ExplorerTreeViewState originalView;
    private boolean revealMetadataMatches;
    private DatabaseTaskScope work;
    private boolean indexing, merging, revealing, closed;
    private int current = -1;

    private final TreeModelListener listener = new TreeModelListener() {
        private void changed(TreeModelEvent event) {
            if (merging || closed) return;
            revealMetadataMatches = false;
            if (indexing) cancel();
            if (event.getTreePath().getPathCount() >= 3) indexed.remove(event.getTreePath().getPathComponent(2));
            if (query.isActive()) { repaint.restart(); debounce.restart(); }
        }
        @Override public void treeNodesChanged(TreeModelEvent event) { changed(event); }
        @Override public void treeNodesInserted(TreeModelEvent event) { changed(event); }
        @Override public void treeNodesRemoved(TreeModelEvent event) { changed(event); }
        @Override public void treeStructureChanged(TreeModelEvent event) { changed(event); }
    };

    DatabaseExplorerSearchController(JTree tree, DatabaseSourceTreeModel model, DatabaseExplorerSearchBar bar) {
        this.tree = tree;
        this.model = model;
        this.root = (DefaultMutableTreeNode) model.getRoot();
        this.bar = bar;
        debounce = new Timer(250, event -> ensureMetadata());
        debounce.setRepeats(false);
        repaint = new Timer(60, event -> refreshMatches(revealMetadataMatches));
        repaint.setRepeats(false);
        model.addTreeModelListener(listener);
    }

    boolean isRevealing() { return revealing; }

    void changed(DatabaseExplorerSearch query) {
        if (closed) return;
        this.query = query;
        if (!query.isActive()) {
            cancel();
            matches = List.of();
            tree.putClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY, Map.of());
            restoreExpansion();
            bar.setResults(0, false, null);
            tree.repaint();
            return;
        }
        if (originalView == null) originalView = ExplorerTreeViewState.capture(tree);
        if (query.metadataDepth() == 0 && indexing) cancel();
        revealMetadataMatches = true;
        refreshMatches(true);
        debounce.restart();
    }

    void reset() {
        cancel();
        indexed.clear();
        errors.clear();
        revealMetadataMatches = false;
        if (query.isActive()) { refreshMatches(false); debounce.restart(); }
    }

    private void restoreExpansion() {
        if (originalView == null) return;
        revealing = true;
        try {
            originalView.restore(tree);
        } finally { revealing = false; }
        originalView = null;
    }

    private List<DefaultMutableTreeNode> searchableSources(boolean includeCached) {
        List<DefaultMutableTreeNode> sources = new ArrayList<>();
        var manager = DatabaseConnectionManager.getInstance();
        for (int groupIndex = 0; groupIndex < model.getChildCount(root); groupIndex++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) model.getChild(root, groupIndex);
            for (int i = 0; i < group.getChildCount(); i++) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) group.getChildAt(i);
                TreeNodeData data = (TreeNodeData) node.getUserObject();
                if (data.getConnectionConfig() != null && (data.isConnected() && manager.isConnected(data.getConnectionConfig().getId())
                        || includeCached && data.isLoaded())) sources.add(node);
            }
        }
        return sources;
    }

    private void refreshMatches(boolean reveal) {
        if (closed || !query.isActive()) return;
        Map<TreeNodeData, List<DatabaseExplorerSearch.Range>> highlights = new IdentityHashMap<>();
        List<TreePath> paths = new ArrayList<>();
        Set<DefaultMutableTreeNode> groups = Collections.newSetFromMap(new IdentityHashMap<>());
        List<DefaultMutableTreeNode> sources = searchableSources(true);
        for (DefaultMutableTreeNode source : sources) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) source.getParent();
            if (groups.add(group)) addMatch(group, highlights, paths);
            var nodes = source.preorderEnumeration();
            while (nodes.hasMoreElements()) addMatch((DefaultMutableTreeNode) nodes.nextElement(), highlights, paths);
        }
        matches = paths;
        current = -1;
        tree.putClientProperty(DatabaseExplorerSearch.MATCHES_PROPERTY, highlights);
        revealing = true;
        try { if (reveal) for (TreePath path : paths) if (path.getParentPath() != null) tree.expandPath(path.getParentPath()); }
        finally { revealing = false; }
        List<String> failures = query.metadataDepth() == 0 ? List.of()
                : sources.stream().flatMap(source -> errors.getOrDefault(source, List.of()).stream()).toList();
        bar.setResults(paths.size(), indexing, failures.isEmpty() ? null : String.join("\n", failures));
        tree.repaint();
    }

    private void addMatch(DefaultMutableTreeNode node, Map<TreeNodeData, List<DatabaseExplorerSearch.Range>> highlights,
                          List<TreePath> paths) {
        if (node.getUserObject() instanceof TreeNodeData data && query.matches(data)) {
            highlights.put(data, query.ranges(query.name(data)));
            paths.add(new TreePath(node.getPath()));
        }
    }

    void navigate(int direction) {
        if (matches.isEmpty()) return;
        current = current < 0 ? direction > 0 ? 0 : matches.size() - 1 : Math.floorMod(current + direction, matches.size());
        TreePath path = matches.get(current);
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    private void ensureMetadata() {
        if (closed || indexing || !query.isActive() || query.metadataDepth() == 0) return;
        int depth = query.metadataDepth(), token = generation.get();
        List<DefaultMutableTreeNode> sources = searchableSources(false).stream().filter(node -> indexed.getOrDefault(node, 0) < depth).toList();
        if (sources.isEmpty()) return;
        List<ConnectionConfig> configs = sources.stream().map(node -> ((TreeNodeData) node.getUserObject()).getConnectionConfig().copy()).toList();
        work = DatabaseTaskService.getInstance().newScope();
        DatabaseTaskScope scope = work;
        indexing = true;
        sources.forEach(errors::remove);
        refreshMatches(false);
        scope.submit(() -> {
            try {
                for (int i = 0; i < sources.size(); i++) {
                    if (generation.get() != token || Thread.currentThread().isInterrupted()) break;
                    DefaultMutableTreeNode source = sources.get(i);
                    ConnectionConfig config = configs.get(i);
                    if (!DatabaseConnectionManager.getInstance().isConnected(config.getId())) continue;
                    try (var read = scope.openRead(config)) {
                        ExplorerMetadataSnapshot snapshot = ExplorerMetadataSnapshot.read(read.connection(), config, depth,
                                () -> generation.get() != token);
                        SwingUtilities.invokeLater(() -> {
                            if (closed || generation.get() != token || source.getRoot() != root
                                    || !DatabaseConnectionManager.getInstance().isConnected(config.getId())) return;
                            merging = true;
                            try {
                                revealing = true;
                                ExplorerTreeViewState.preserving(tree, () -> {
                                    merge(source, snapshot);
                                    model.nodeStructureChanged(source);
                                });
                            } finally { revealing = false; merging = false; }
                            indexed.put(source, depth);
                            errors.put(source, snapshot.errors().stream().map(message -> config.getName() + ": " + message).toList());
                            repaint.restart();
                        });
                    } catch (Exception failure) {
                        SwingUtilities.invokeLater(() -> {
                            if (closed || generation.get() != token) return;
                            indexed.put(source, depth); // Retry on refresh, rather than retrying a failed source forever.
                            errors.put(source, List.of(config.getName() + ": " + failure.getMessage()));
                        });
                    }
                }
            } finally {
                scope.close();
                SwingUtilities.invokeLater(() -> {
                    if (closed || generation.get() != token) return;
                    work = null;
                    indexing = false;
                    refreshMatches(revealMetadataMatches);
                    revealMetadataMatches = false;
                    ensureMetadata();
                });
            }
        });
    }

    private static DefaultMutableTreeNode child(DefaultMutableTreeNode parent, TreeNodeData.NodeType type, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) parent.getChildAt(i);
            TreeNodeData data = (TreeNodeData) node.getUserObject();
            if (data.getType() == type && (name == null || data.getName().equals(name))) return node;
        }
        return null;
    }

    private static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, TreeNodeData data) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(data);
        parent.add(node);
        return node;
    }

    private static void removePlaceholders(DefaultMutableTreeNode node) {
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            TreeNodeData data = (TreeNodeData) ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject();
            if (data.getType() == TreeNodeData.NodeType.LOADING || data.getType() == TreeNodeData.NodeType.ERROR) node.remove(i);
        }
    }

    private static void merge(DefaultMutableTreeNode source, ExplorerMetadataSnapshot snapshot) {
        TreeNodeData sourceData = (TreeNodeData) source.getUserObject();
        ConnectionConfig config = sourceData.getConnectionConfig();
        removePlaceholders(source);
        sourceData.setLoaded(true);
        sourceData.setConnected(true);
        for (var namespace : snapshot.namespaces()) {
            DefaultMutableTreeNode database = child(source, TreeNodeData.NodeType.DATABASE, namespace.name());
            if (database == null) {
                database = add(source, TreeNodeData.database(config, namespace.name()));
                add(database, TreeNodeData.placeholder(""));
            }
            if (!namespace.tablesLoaded()) continue;
            removePlaceholders(database);
            ((TreeNodeData) database.getUserObject()).setLoaded(true);
            if (child(database, TreeNodeData.NodeType.TABLES_FOLDER, null) == null)
                add(database, TreeNodeData.tablesFolder(config, namespace.name(), 0));
            for (var table : namespace.tables()) {
                var metadata = table.metadata();
                TreeNodeData.NodeType folderType = metadata.isView() ? TreeNodeData.NodeType.VIEWS_FOLDER : TreeNodeData.NodeType.TABLES_FOLDER;
                DefaultMutableTreeNode folder = child(database, folderType, null);
                if (folder == null) folder = add(database, metadata.isView() ? TreeNodeData.viewsFolder(config, namespace.name(), 0)
                        : TreeNodeData.tablesFolder(config, namespace.name(), 0));
                DefaultMutableTreeNode tableNode = child(folder, metadata.isView() ? TreeNodeData.NodeType.VIEW : TreeNodeData.NodeType.TABLE, metadata.getName());
                if (tableNode == null) {
                    tableNode = add(folder, TreeNodeData.table(config, namespace.name(), metadata));
                    add(tableNode, TreeNodeData.placeholder(""));
                }
                TreeNodeData tableData = (TreeNodeData) tableNode.getUserObject();
                if (table.columnsLoaded() && !tableData.isLoaded()) {
                    tableData.getTableMetadata().setColumns(table.columns());
                    tableNode.removeAllChildren();
                    DefaultMutableTreeNode columns = add(tableNode, TreeNodeData.columnsFolder(config, namespace.name(), tableData.getTableMetadata(), table.columns().size()));
                    for (var column : table.columns()) add(columns, TreeNodeData.column(config, namespace.name(), tableData.getTableMetadata(), column));
                    tableData.setLoaded(true);
                }
                ((TreeNodeData) folder.getUserObject()).setName((metadata.isView() ? "Views" : "Tables") + " (" + folder.getChildCount() + ")");
            }
        }
    }

    private void cancel() {
        generation.incrementAndGet();
        debounce.stop();
        repaint.stop();
        if (work != null) {
            DatabaseTaskScope scope = work;
            work = null;
            scope.cancelPending();
            ApplicationManager.getApplication().executeOnPooledThread(scope::close);
        }
        indexing = false;
    }

    @Override public void close() {
        closed = true;
        cancel();
        model.removeTreeModelListener(listener);
    }
}
