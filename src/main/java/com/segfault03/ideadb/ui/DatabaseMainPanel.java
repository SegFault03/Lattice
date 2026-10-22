package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.provider.DatabaseProviders;

import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.segfault03.ideadb.dialog.AlterTableDialog;
import com.segfault03.ideadb.dialog.ConnectionDialog;
import com.segfault03.ideadb.dialog.CreateDatabaseDialog;
import com.segfault03.ideadb.dialog.CreateTableDialog;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.DdlService;
import com.segfault03.ideadb.service.MetadataService;
import com.segfault03.ideadb.service.DatabaseChange;
import com.segfault03.ideadb.service.DatabaseChangeListener;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.state.MetadataCacheState;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

public class DatabaseMainPanel extends JPanel implements com.intellij.openapi.Disposable {
    private final com.segfault03.ideadb.service.DatabaseTaskScope tasks=com.segfault03.ideadb.service.DatabaseTaskService.getInstance().newScope();
    private volatile boolean disposed;
    private final Project project;

    private DatabaseSourceTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private Tree databaseTree;
    private DatabaseExplorerToolbar toolbar;
    private DatabaseExplorerSearchBar searchBar;
    private DatabaseExplorerHeader explorerHeader;
    private DatabaseExplorerSearchController searchController;
    private final JLabel operationStatus = new WrappingLabel("Ready");
    private final java.util.Map<TreeNodeData, String> loadingNodes = new java.util.LinkedHashMap<>();
    private boolean mutationRunning;
    private final java.util.Set<DatabaseChange> pendingRefreshes = new java.util.LinkedHashSet<>();
    private final DatabaseExplorerHint selectionHint = new DatabaseExplorerHint();
    private final CardLayout explorerLayout = new CardLayout();
    private final JPanel explorerCards = new JPanel(explorerLayout);

    public DatabaseMainPanel(Project project) {
        super(new BorderLayout(0, 0));
        this.project = project;

        initUI();
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(DatabaseChangeListener.TOPIC, change -> {
                    pendingRefreshes.add(change);
                    drainRefreshes();
                });
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(com.segfault03.ideadb.state.DatabaseSettingsListener.TOPIC, () -> SwingUtilities.invokeLater(() -> {
                    if (!disposed && !project.isDisposed()) loadConnectionsFromState();
                }));
        loadConnectionsFromState();
    }

    public boolean isDisposed() { return disposed; }

    @Override public void dispose() {
        if (searchController != null) searchController.close();
        disposed=true; tasks.cancelPending();
        ToolTipManager.sharedInstance().unregisterComponent(databaseTree);
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(tasks::close);
    }
    private void initUI() {
        toolbar = new DatabaseExplorerToolbar(this::showAddConnectionMenu,
                this::editSelectedConnection, this::removeSelectedConnection,
                this::refreshSelectedNode, this::openConsoleForSelected,
                this::showSourceFilter,
                this::toggleSearch,
                () -> DatabaseEditorManager.getInstance(project).openWelcome());
        searchBar = new DatabaseExplorerSearchBar(query -> {
            if (searchController != null) searchController.changed(query);
        }, () -> setSearchVisible(false), () -> searchController.navigate(-1), () -> searchController.navigate(1));
        explorerHeader = new DatabaseExplorerHeader(toolbar.createComponent(this), searchBar);
        add(explorerHeader, BorderLayout.NORTH);
        setPreferredSize(JBUI.size(260, 400));

        // Database Tree
        rootNode = new DefaultMutableTreeNode(TreeNodeData.root());
        treeModel = new DatabaseSourceTreeModel(rootNode);
        databaseTree = new Tree(treeModel);
        databaseTree.setCellRenderer(new DatabaseTreeCellRenderer());
        databaseTree.putClientProperty(com.intellij.ui.AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, Boolean.TRUE);
        databaseTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        databaseTree.setRootVisible(true);
        ToolTipManager.sharedInstance().registerComponent(databaseTree);
        databaseTree.setShowsRootHandles(true);
        databaseTree.setBorder(JBUI.Borders.empty(6, 8));

        setupTreeListeners();
        searchController = new DatabaseExplorerSearchController(databaseTree, treeModel, searchBar);
        String searchAction = "lattice.explorer.search";
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F,
                com.intellij.openapi.util.SystemInfo.isMac ? java.awt.event.InputEvent.META_DOWN_MASK : java.awt.event.InputEvent.CTRL_DOWN_MASK), searchAction);
        getActionMap().put(searchAction, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { setSearchVisible(true); }
        });

        JBScrollPane treeScrollPane = new JBScrollPane(databaseTree);
        treeScrollPane.setBorder(JBUI.Borders.empty());
        explorerCards.add(treeScrollPane, "tree");
        explorerCards.add(new EmptyConnectionPanel(this::showAddConnectionMenu), "empty");
        JPanel filteredEmpty = new JPanel(new GridBagLayout());
        GridBagConstraints prompt = new GridBagConstraints();
        prompt.gridx = 0;
        prompt.gridy = 0;
        filteredEmpty.add(new com.intellij.ui.components.JBLabel("No connections match this view"), prompt);
        prompt.gridy = 1;
        prompt.insets = JBUI.insetsTop(8);
        com.intellij.ui.components.ActionLink showAll = new com.intellij.ui.components.ActionLink("Show all data sources");
        showAll.addActionListener(event -> applySourceFilter(java.util.EnumSet.allOf(DatabaseType.class)));
        filteredEmpty.add(showAll, prompt);
        explorerCards.add(filteredEmpty, "filtered-empty");
        add(explorerCards, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        operationStatus.getAccessibleContext().setAccessibleName("Database explorer status");
        operationStatus.setBorder(JBUI.Borders.empty(4, 8));
        footer.add(operationStatus, BorderLayout.NORTH);
        footer.add(selectionHint, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    public void loadConnectionsFromState() {
        java.util.Set<DatabaseType> collapsedTypes = java.util.EnumSet.noneOf(DatabaseType.class);
        var oldChildren = rootNode.children();
        while (oldChildren.hasMoreElements()) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) oldChildren.nextElement();
            if (child.getUserObject() instanceof TreeNodeData data
                    && data.getType() == TreeNodeData.NodeType.SOURCE_TYPE
                    && data.getDatabaseType() != null
                    && !databaseTree.isExpanded(new TreePath(child.getPath()))) {
                collapsedTypes.add(data.getDatabaseType());
            }
        }
        rootNode.removeAllChildren();
        List<ConnectionConfig> configs = DatabaseSettingsState.getInstance().getConnections();
        toolbar.setHasConnections(!configs.isEmpty());
        if (!configs.isEmpty()) {
            java.util.Map<DatabaseType, List<ConnectionConfig>> byType = new java.util.EnumMap<>(DatabaseType.class);
            for (ConnectionConfig cfg : configs) {
                byType.computeIfAbsent(cfg.getType(), ignored -> new ArrayList<>()).add(cfg);
            }
            List<DatabaseType> types = new ArrayList<>(byType.keySet());
            types.sort(java.util.Comparator.comparing(DatabaseType::getDisplayName, String.CASE_INSENSITIVE_ORDER));
            for (DatabaseType type : types) {
                DefaultMutableTreeNode typeNode = new DefaultMutableTreeNode(TreeNodeData.sourceType(type));
                for (ConnectionConfig cfg : byType.get(type)) {
                    boolean connected = DatabaseConnectionManager.getInstance().isConnected(cfg.getId());
                    DefaultMutableTreeNode connNode = new DefaultMutableTreeNode(TreeNodeData.connection(cfg, connected));
                    if (connected) connNode.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("")));
                    else CachedMetadataTree.restore(connNode, MetadataCacheState.getInstance().snapshot(cfg));
                    typeNode.add(connNode);
                }
                rootNode.add(typeNode);
            }
        }
        treeModel.reload();
        updateSourceFilterAction();
        databaseTree.expandPath(new TreePath(rootNode.getPath()));
        for (int i = 0; i < treeModel.getChildCount(rootNode); i++) {
            DefaultMutableTreeNode typeNode = (DefaultMutableTreeNode) treeModel.getChild(rootNode, i);
            TreeNodeData data = (TreeNodeData) typeNode.getUserObject();
            if (!collapsedTypes.contains(data.getDatabaseType())) {
                databaseTree.expandPath(new TreePath(typeNode.getPath()));
            }
        }
        updateSelectionActions();
        if (searchController != null) searchController.reset();
    }

    private void toggleSearch() { setSearchVisible(!searchBar.isVisible()); }

    private void setSearchVisible(boolean visible) {
        searchBar.setVisible(visible);
        toolbar.setSearchVisible(visible);
        if (visible) SwingUtilities.invokeLater(searchBar::focusInput);
        else {
            searchBar.setText("");
            searchController.changed(searchBar.query());
            databaseTree.requestFocusInWindow();
        }
        explorerHeader.revalidate();
        explorerHeader.repaint();
    }

    private void showSourceFilter(Component anchor) {
        List<DatabaseType> available = new ArrayList<>(java.util.Arrays.asList(DatabaseType.values()));
        available.sort(java.util.Comparator.comparing(DatabaseType::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        DatabaseSourceFilterPopup.show(anchor, available, treeModel.getVisibleTypes(), this::applySourceFilter, this);
    }

    private void applySourceFilter(java.util.Set<DatabaseType> types) {
        if (disposed || project.isDisposed()) return;
        TreePath rootPath = new TreePath(rootNode.getPath());
        var expanded = databaseTree.getExpandedDescendants(rootPath);
        List<TreePath> expandedPaths = expanded == null ? List.of() : java.util.Collections.list(expanded);
        TreePath selected = databaseTree.getSelectionPath();
        java.util.Set<DatabaseType> previousTypes = treeModel.getVisibleTypes();
        treeModel.setVisibleTypes(types);
        databaseTree.expandPath(rootPath);
        for (TreePath path : expandedPaths) {
            if (isVisibleSourcePath(path)) databaseTree.expandPath(path);
        }
        for (int i = 0; i < treeModel.getChildCount(rootNode); i++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) treeModel.getChild(rootNode, i);
            TreeNodeData data = (TreeNodeData) group.getUserObject();
            if (!previousTypes.contains(data.getDatabaseType())) databaseTree.expandPath(new TreePath(group.getPath()));
        }
        if (selected != null && isVisibleSourcePath(selected)) databaseTree.setSelectionPath(selected);
        updateSourceFilterAction();
        updateSelectionActions();
    }

    private boolean isVisibleSourcePath(TreePath path) {
        if (path.getPathCount() < 2) return true;
        TreeNodeData source = (TreeNodeData) ((DefaultMutableTreeNode) path.getPathComponent(1)).getUserObject();
        return treeModel.getVisibleTypes().contains(source.getDatabaseType());
    }

    private void updateSourceFilterAction() {
        java.util.Set<DatabaseType> types = treeModel.getVisibleTypes();
        boolean all = types.size() == DatabaseType.values().length;
        String summary = all ? "All data sources" : types.stream().map(DatabaseType::getDisplayName)
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(java.util.stream.Collectors.joining(", "));
        toolbar.setSourceFilter(summary, !all);
        explorerLayout.show(explorerCards, rootNode.getChildCount() == 0 ? "empty"
                : treeModel.getChildCount(rootNode) == 0 ? "filtered-empty" : "tree");
    }

    private void setupTreeListeners() {
        databaseTree.addTreeSelectionListener(event -> updateSelectionActions());
        databaseTree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                handleNodeExpansion(node);
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
            }
        });

        databaseTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = databaseTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        handleNodeDoubleClick(node);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath path = databaseTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        databaseTree.setSelectionPath(path);
                        showContextMenu(path, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private void updateSelectionActions() {
        TreeNodeData selection = null;
        DefaultMutableTreeNode selectedNode = null;
        TreePath path = databaseTree.getSelectionPath();
        if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof TreeNodeData data) {
            selection = data;
            selectedNode = node;
        }
        var source = selectedNode == null ? null : connectionAncestor(selectedNode);
        toolbar.setSelection(selection, source == null ? firstConnectedSource() != null : connectionAvailable(selectedNode));
        selectionHint.setSelection(selection);
    }

    private DefaultMutableTreeNode firstConnectedSource() {
        for (int i = 0; i < treeModel.getChildCount(rootNode); i++) {
            var group = (DefaultMutableTreeNode) treeModel.getChild(rootNode, i);
            for (int j = 0; j < group.getChildCount(); j++) {
                var node = (DefaultMutableTreeNode) group.getChildAt(j);
                if (connectionAvailable(node)) return node;
            }
        }
        return null;
    }

    private void handleNodeExpansion(DefaultMutableTreeNode node) {
        if (searchController != null && searchController.isRevealing()) return;
        Object uo = node.getUserObject();
        if (!(uo instanceof TreeNodeData)) return;
        TreeNodeData data = (TreeNodeData) uo;

        if (data.isLoaded() || data.isLoading() || mutationRunning) return;

        if (data.getType() == TreeNodeData.NodeType.CONNECTION) {
            loadDatabasesForConnectionNode(node, data);
        } else if (data.getType() == TreeNodeData.NodeType.DATABASE) {
            loadTablesForDatabaseNode(node, data);
        } else if (data.getType() == TreeNodeData.NodeType.TABLE || data.getType() == TreeNodeData.NodeType.VIEW) {
            loadColumnsForTableNode(node, data);
        }
    }

    private void handleNodeDoubleClick(DefaultMutableTreeNode node) {
        if (mutationRunning) return;
        Object uo = node.getUserObject();
        if (!(uo instanceof TreeNodeData)) return;
        TreeNodeData data = (TreeNodeData) uo;

        if (data.getType() == TreeNodeData.NodeType.TABLE || data.getType() == TreeNodeData.NodeType.VIEW) {
            openTableData(node, data);
        }
    }

    private void openTableData(DefaultMutableTreeNode node, TreeNodeData data) {
        if (!data.isCached()) {
            DatabaseEditorManager.getInstance(project).openTableData(data.getConnectionConfig(), data.getDatabaseName(), data.getTableMetadata());
            return;
        }
        if (data.isLoading()) return;
        data.setLoading(true);
        loadingNodes.put(data, "Connecting to database…");
        updateExplorerStatus("Connecting to database…", DatabaseUi.Tone.BUSY);
        tasks.submit(() -> {
            try (var read = tasks.openRead(data.getConnectionConfig())) {
                // Verify a live connection even when an editor for this table is already open.
                var columns = MetadataService.getInstance().getColumns(read.connection(), data.getConnectionConfig(),
                        data.getDatabaseName(), data.getTableMetadata().getName());
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(node, data, "Connected", DatabaseUi.Tone.NORMAL)) return;
                    data.getTableMetadata().setColumns(columns);
                    DatabaseEditorManager.getInstance(project).openTableData(data.getConnectionConfig(), data.getDatabaseName(), data.getTableMetadata());
                    var source = connectionAncestor(node);
                    if (source != null) loadDatabasesForConnectionNode(source, (TreeNodeData) source.getUserObject());
                });
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(node, data, "Could not connect · Showing cached metadata", DatabaseUi.Tone.ERROR)) return;
                    restoreCachedConnection(node);
                    Messages.showErrorDialog(project, "Failed to connect: " + failure.getMessage(), "Connection Error");
                });
            }
        });
    }

    private static DefaultMutableTreeNode connectionAncestor(DefaultMutableTreeNode node) {
        for (var current = node; current != null; current = (DefaultMutableTreeNode) current.getParent()) {
            if (current.getUserObject() instanceof TreeNodeData data && data.getType() == TreeNodeData.NodeType.CONNECTION) return current;
        }
        return null;
    }

    private void restoreCachedConnection(DefaultMutableTreeNode node) {
        restoreCachedConnection(node, null);
    }

    private void restoreCachedConnection(DefaultMutableTreeNode node, TreeViewState previous) {
        var source = connectionAncestor(node);
        if (source == null) return;
        var data = (TreeNodeData) source.getUserObject();
        var view = captureTreeView(source);
        var expanded = java.util.stream.Stream.concat(view.expanded().stream(), previous == null ? java.util.stream.Stream.empty() : previous.expanded().stream())
                .map(DatabaseMainPanel::pathIdentity).collect(java.util.stream.Collectors.toSet());
        var selection = previous == null ? view.selected() : previous.selected();
        var selected = selection == null ? null : pathIdentity(selection);
        DatabaseConnectionManager.getInstance().closeBrowserConnections(data.getConnectionConfig().getId());
        CachedMetadataTree.restore(source, MetadataCacheState.getInstance().snapshot(data.getConnectionConfig()));
        treeModel.nodeStructureChanged(source);
        databaseTree.expandPath(new TreePath(source.getPath()));
        var nodes = source.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var restored = (DefaultMutableTreeNode) nodes.nextElement();
            var path = new TreePath(restored.getPath());
            var identity = pathIdentity(path);
            if (expanded.contains(identity)) databaseTree.expandPath(path);
            if (identity.equals(selected)) databaseTree.setSelectionPath(path);
        }
        updateSelectionActions();
    }

    private static List<String> pathIdentity(TreePath path) {
        return java.util.Arrays.stream(path.getPath()).map(value -> {
            var data = (TreeNodeData) ((DefaultMutableTreeNode) value).getUserObject();
            return data.getType() + ":" + data.getName();
        }).toList();
    }

    private void showContextMenu(TreePath path, int x, int y) {
        if (mutationRunning || !loadingNodes.isEmpty()) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object uo = node.getUserObject();
        if (!(uo instanceof TreeNodeData)) return;
        TreeNodeData data = (TreeNodeData) uo;

        var menu = DatabaseExplorerContextMenu.create(data, connectionAvailable(node), action -> performContextAction(node, data, action));
        if (menu.getComponentCount() > 0) menu.show(databaseTree, x, y);
    }

    private boolean connectionAvailable(DefaultMutableTreeNode node) {
        var source = connectionAncestor(node);
        return source != null && ((TreeNodeData) source.getUserObject()).isConnected()
                && node.getUserObject() instanceof TreeNodeData data && !data.isCached();
    }

    private void performContextAction(DefaultMutableTreeNode node, TreeNodeData data, DatabaseExplorerContextMenu.Action action) {
        if (action.requiresConnection() && !connectionAvailable(node)) return;
        var cfg = data.getConnectionConfig();
        var db = data.getDatabaseName();
        var table = data.getTableMetadata();
        switch (action) {
            case CONNECT -> { data.setLoaded(false); loadDatabasesForConnectionNode(node, data); }
            case DISCONNECT -> { DatabaseConnectionManager.getInstance().closeConnection(cfg.getId()); restoreCachedConnection(node); }
            case CONSOLE -> DatabaseEditorManager.getInstance(project).openConsole(cfg, db,
                    table == null ? null : DdlService.getInstance().buildSelectSql(cfg, db, table.getName(), List.of()));
            case CREATE_NAMESPACE -> doCreateDatabase(cfg);
            case EDIT_CONNECTION -> editConnection(cfg);
            case REMOVE_CONNECTION -> removeConnection(cfg);
            case CREATE_TABLE -> doCreateTable(cfg, db);
            case DROP_NAMESPACE -> doDropDatabase(cfg, db);
            case REFRESH_TABLES -> {
                if (data.isCached()) {
                    var source = connectionAncestor(node);
                    loadDatabasesForConnectionNode(source, (TreeNodeData) source.getUserObject());
                } else { data.setLoaded(false); loadTablesForDatabaseNode(node, data); }
            }
            case OPEN_TABLE -> openTableData(node, data);
            case ALTER_TABLE -> new AlterTableDialog(project, cfg, db, table).showAndGet();
            case TRUNCATE_TABLE -> doTruncateTable(cfg, db, table.getName());
            case DROP_TABLE -> doDropTable(cfg, db, table.getName());
            case GENERATE_SELECT, GENERATE_INSERT -> {
                var columns = table.getColumns().stream().map(ColumnMetadata::getName).toList();
                var sql = action == DatabaseExplorerContextMenu.Action.GENERATE_SELECT
                        ? DdlService.getInstance().buildSelectSql(cfg, db, table.getName(), columns)
                        : DdlService.getInstance().buildInsertSql(cfg, db, table.getName(), columns);
                if (connectionAvailable(node)) DatabaseEditorManager.getInstance(project).openConsole(cfg, db, sql);
                else new GeneratedSqlDialog(project, sql).show();
            }
        }
    }

    private boolean beginNodeLoad(DefaultMutableTreeNode node, TreeNodeData data, String message) {
        if (disposed || project.isDisposed() || mutationRunning || data.isLoading() || node.getRoot() != rootNode) return false;
        data.setLoading(true);
        loadingNodes.put(data, message);
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(TreeNodeData.loading(message)));
        treeModel.nodeStructureChanged(node);
        databaseTree.expandPath(new TreePath(node.getPath()));
        updateExplorerStatus(message, DatabaseUi.Tone.BUSY);
        return true;
    }

    private boolean finishNodeLoad(DefaultMutableTreeNode node, TreeNodeData data, String message, DatabaseUi.Tone tone) {
        data.setLoading(false);
        loadingNodes.remove(data);
        if (disposed || project.isDisposed()) return false;
        updateExplorerStatus(message, tone);
        SwingUtilities.invokeLater(() -> {
            if (!disposed && !project.isDisposed()) updateSelectionActions();
        });
        SwingUtilities.invokeLater(this::drainRefreshes);
        return node.getRoot() == rootNode;
    }

    /** Never drop an invalidation just because a previous metadata read is still running. */
    private void drainRefreshes() {
        if (disposed || project.isDisposed() || mutationRunning || !loadingNodes.isEmpty() || pendingRefreshes.isEmpty()) return;
        var changes = List.copyOf(pendingRefreshes);
        pendingRefreshes.clear();
        var targets = new java.util.LinkedHashSet<DefaultMutableTreeNode>();
        var nodes = rootNode.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (!(node.getUserObject() instanceof TreeNodeData data) || data.getConnectionConfig() == null) continue;
            for (var change : changes) {
                if (!change.connectionId().equals(data.getConnectionConfig().getId())) continue;
                if ((change.namespacesChanged() && data.getType() == TreeNodeData.NodeType.CONNECTION)
                        || (!change.namespacesChanged() && data.getType() == TreeNodeData.NodeType.DATABASE
                        && (change.namespace() == null || change.namespace().equals(data.getDatabaseName())))) targets.add(node);
            }
        }
        // A connection refresh includes its previously loaded namespaces.
        targets.removeIf(node -> targets.stream().anyMatch(parent -> parent != node && parent.isNodeDescendant(node)));
        for (var node : targets) {
            var data = (TreeNodeData) node.getUserObject();
            data.setLoaded(false);
            if (data.getType() == TreeNodeData.NodeType.CONNECTION) loadDatabasesForConnectionNode(node, data);
            else loadTablesForDatabaseNode(node, data);
        }
    }

    private void updateExplorerStatus(String message, DatabaseUi.Tone tone) {
        toolbar.setBusy(mutationRunning || !loadingNodes.isEmpty());
        if (!loadingNodes.isEmpty()) {
            message = loadingNodes.values().iterator().next()
                    + (loadingNodes.size() > 1 ? " · " + loadingNodes.size() + " operations" : "");
            tone = DatabaseUi.Tone.BUSY;
        }
        DatabaseUi.status(operationStatus, message, tone);
    }

    private java.util.Map<String, DefaultMutableTreeNode> existingNodes(DefaultMutableTreeNode parent, TreeNodeData.NodeType... types) {
        var result = new java.util.LinkedHashMap<String, DefaultMutableTreeNode>();
        var wanted = java.util.Set.of(types);
        var nodes = parent.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof TreeNodeData data && wanted.contains(data.getType()))
                result.put(data.getName(), node);
        }
        return result;
    }

    private record TreeViewState(List<TreePath> expanded, TreePath selected) { }

    private TreeViewState captureTreeView(DefaultMutableTreeNode parent) {
        var expanded = databaseTree.getExpandedDescendants(new TreePath(parent.getPath()));
        return new TreeViewState(expanded == null ? List.of() : java.util.Collections.list(expanded), databaseTree.getSelectionPath());
    }

    private void restoreTreeView(TreeViewState state) {
        for (var path : state.expanded()) {
            var node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getRoot() == rootNode) databaseTree.expandPath(new TreePath(node.getPath()));
        }
        if (state.selected() != null) {
            var node = (DefaultMutableTreeNode) state.selected().getLastPathComponent();
            if (node.getRoot() == rootNode) databaseTree.setSelectionPath(new TreePath(node.getPath()));
        }
    }

    private void loadDatabasesForConnectionNode(DefaultMutableTreeNode connNode, TreeNodeData data) {
        ConnectionConfig cfg = data.getConnectionConfig();
        var existing = existingNodes(connNode, TreeNodeData.NodeType.DATABASE);
        var view = captureTreeView(connNode);
        if (!beginNodeLoad(connNode, data, data.isConnected() ? "Loading databases…" : "Connecting to database…")) return;
        tasks.submit(() -> {
            try {
                List<String> dbs = DatabaseConnectionManager.getInstance().executeBrowser(cfg, conn -> MetadataService.getInstance().getDatabases(conn, cfg));

                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(connNode, data, "Loaded databases", DatabaseUi.Tone.NORMAL)) return;
                    connNode.removeAllChildren();
                    data.setConnected(true);
                    data.setLoaded(true);

                    for (String db : dbs) {
                        DefaultMutableTreeNode dbNode = existing.get(db);
                        if (dbNode == null) {
                            dbNode = new DefaultMutableTreeNode(TreeNodeData.database(cfg, db));
                            dbNode.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("Expand to load tables...")));
                        }
                        ((TreeNodeData) dbNode.getUserObject()).setCached(false);
                        connNode.add(dbNode);
                    }

                    treeModel.nodeStructureChanged(connNode);
                    restoreTreeView(view);
                    for (int i = 0; i < connNode.getChildCount(); i++) {
                        var node = (DefaultMutableTreeNode) connNode.getChildAt(i);
                        var namespace = (TreeNodeData) node.getUserObject();
                        if (namespace.isLoaded()) loadTablesForDatabaseNode(node, namespace);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(connNode, data, "Could not load databases · Refresh to retry", DatabaseUi.Tone.ERROR)) return;
                    restoreCachedConnection(connNode, view);
                    if (MetadataCacheState.getInstance().snapshot(cfg) == null) {
                        connNode.removeAllChildren();
                        connNode.add(new DefaultMutableTreeNode(TreeNodeData.error("Connection failed · Refresh to retry", ex.getMessage())));
                        treeModel.nodeStructureChanged(connNode);
                    }
                    Messages.showErrorDialog(project, "Failed to connect: " + ex.getMessage(), "Connection Error");
                });
            }
        });
    }

    static DefaultMutableTreeNode databaseAncestor(DefaultMutableTreeNode node) {
        for (DefaultMutableTreeNode current = node; current != null; current = (DefaultMutableTreeNode)current.getParent())
            if (current.getUserObject() instanceof TreeNodeData data && data.getType() == TreeNodeData.NodeType.DATABASE) return current;
        return null;
    }

    private void loadTablesForDatabaseNode(DefaultMutableTreeNode dbNode, TreeNodeData data) {
        ConnectionConfig cfg = data.getConnectionConfig();
        String dbName = data.getDatabaseName();
        var existing = existingNodes(dbNode, TreeNodeData.NodeType.TABLE, TreeNodeData.NodeType.VIEW);
        var view = captureTreeView(dbNode);
        if (!beginNodeLoad(dbNode, data, "Loading tables…")) return;

        tasks.submit(() -> {
            try {
                List<TableMetadata> tables = DatabaseConnectionManager.getInstance().executeBrowser(cfg, conn -> MetadataService.getInstance().getTables(conn, cfg, dbName));

                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(dbNode, data, "Loaded tables", DatabaseUi.Tone.NORMAL)) return;
                    dbNode.removeAllChildren();
                    data.setLoaded(true);
                    data.setCached(false);
                    var source = connectionAncestor(dbNode);
                    if (source != null) ((TreeNodeData) source.getUserObject()).setConnected(true);

                    List<TableMetadata> regularTables = new ArrayList<>();
                    List<TableMetadata> views = new ArrayList<>();
                    for (TableMetadata tm : tables) {
                        if (tm.isView()) views.add(tm);
                        else regularTables.add(tm);
                    }

                    DefaultMutableTreeNode tablesFolder = new DefaultMutableTreeNode(TreeNodeData.tablesFolder(cfg, dbName, regularTables.size()));
                    for (TableMetadata tm : regularTables) {
                        DefaultMutableTreeNode tableNode = reuseTableNode(existing.get(tm.getName()), cfg, dbName, tm);
                        tablesFolder.add(tableNode);
                    }
                    dbNode.add(tablesFolder);

                    if (!views.isEmpty()) {
                        DefaultMutableTreeNode viewsFolder = new DefaultMutableTreeNode(TreeNodeData.viewsFolder(cfg, dbName, views.size()));
                        for (TableMetadata tm : views) {
                            DefaultMutableTreeNode viewNode = reuseTableNode(existing.get(tm.getName()), cfg, dbName, tm);
                            viewsFolder.add(viewNode);
                        }
                        dbNode.add(viewsFolder);
                    }

                    treeModel.nodeStructureChanged(dbNode);
                    databaseTree.expandPath(new TreePath(tablesFolder.getPath()));
                    restoreTreeView(view);
                    var nodes = existingNodes(dbNode, TreeNodeData.NodeType.TABLE, TreeNodeData.NodeType.VIEW);
                    for (var node : nodes.values()) {
                        var table = (TreeNodeData) node.getUserObject();
                        if (table.isLoaded()) loadColumnsForTableNode(node, table);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(dbNode, data, "Could not load tables · Refresh to retry", DatabaseUi.Tone.ERROR)) return;
                    restoreCachedConnection(dbNode, view);
                });
            }
        });
    }

    private DefaultMutableTreeNode reuseTableNode(DefaultMutableTreeNode node, ConnectionConfig config, String database, TableMetadata metadata) {
        var data = TreeNodeData.table(config, database, metadata);
        if (node == null) {
            node = new DefaultMutableTreeNode(data);
            node.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("Expand to load columns...")));
        } else {
            data.setLoaded(((TreeNodeData) node.getUserObject()).isLoaded());
            node.setUserObject(data);
        }
        return node;
    }

    private void loadColumnsForTableNode(DefaultMutableTreeNode tableNode, TreeNodeData data) {
        ConnectionConfig cfg = data.getConnectionConfig();
        String dbName = data.getDatabaseName();
        TableMetadata tm = data.getTableMetadata();
        var view = captureTreeView(tableNode);
        boolean expanded = databaseTree.isExpanded(new TreePath(tableNode.getPath()));
        if (!beginNodeLoad(tableNode, data, "Loading columns…")) return;
        if (!expanded) databaseTree.collapsePath(new TreePath(tableNode.getPath()));

        tasks.submit(() -> {
            try {
                List<ColumnMetadata> cols = DatabaseConnectionManager.getInstance().executeBrowser(cfg, conn -> MetadataService.getInstance().getColumns(conn, cfg, dbName, tm.getName()));
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(tableNode, data, "Loaded columns", DatabaseUi.Tone.NORMAL)) return;
                    tm.setColumns(cols);
                    tableNode.removeAllChildren();
                    data.setLoaded(true);
                    data.setCached(false);

                    DefaultMutableTreeNode colsFolder = new DefaultMutableTreeNode(TreeNodeData.columnsFolder(cfg, dbName, tm, cols.size()));
                    for (ColumnMetadata col : cols) {
                        colsFolder.add(new DefaultMutableTreeNode(TreeNodeData.column(cfg, dbName, tm, col)));
                    }
                    tableNode.add(colsFolder);

                    treeModel.nodeStructureChanged(tableNode);
                    if (expanded) databaseTree.expandPath(new TreePath(colsFolder.getPath()));
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(tableNode, data, "Could not load columns · Refresh to retry", DatabaseUi.Tone.ERROR)) return;
                    restoreCachedConnection(tableNode, view);
                });
            }
        });
    }

    private void showAddConnectionMenu(Component invoker) {
        DatabaseUi.connectionMenu(this::showAddConnectionDialog).show(invoker, 0, invoker.getHeight());
    }

    private void showAddConnectionDialog(DatabaseType defaultType) {
        ConnectionConfig newCfg = new ConnectionConfig(defaultType, "New " + defaultType.getDisplayName());
        newCfg.setDatabaseName("");
        newCfg.setUser("");
        ConnectionDialog dlg = new ConnectionDialog(project, newCfg, true);
        if (dlg.showAndGet()) {
            ConnectionConfig result = dlg.getResultConfig();
            DatabaseSettingsState.getInstance().addConnection(result);
        }
    }

    private void editSelectedConnection() {
        TreePath path = databaseTree.getSelectionPath();
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof TreeNodeData) {
            TreeNodeData data = (TreeNodeData) node.getUserObject();
            if (data.getConnectionConfig() != null) {
                editConnection(data.getConnectionConfig());
            }
        }
    }

    private void editConnection(ConnectionConfig cfg) {
        ConnectionDialog dlg = new ConnectionDialog(project, cfg);
        if (dlg.showAndGet()) {
            ConnectionConfig updated = dlg.getResultConfig();
            DatabaseSettingsState.getInstance().updateConnection(updated);
        }
    }

    private void removeSelectedConnection() {
        TreePath path = databaseTree.getSelectionPath();
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof TreeNodeData) {
            TreeNodeData data = (TreeNodeData) node.getUserObject();
            if (data.getType() == TreeNodeData.NodeType.CONNECTION) {
                removeConnection(data.getConnectionConfig());
            }
        }
    }

    private void removeConnection(ConnectionConfig cfg) {
        int confirm = Messages.showYesNoDialog(project, "Remove connection '" + cfg.getName() + "'?\nOnly the saved connection settings will be removed. Your database and data will be kept.", "Remove connection", "Remove connection", "Cancel", Messages.getQuestionIcon());
        if (confirm == Messages.YES) {
            DatabaseSettingsState.getInstance().removeConnection(cfg.getId());
        }
    }

    private void refreshSelectedNode() {
        TreePath path = databaseTree.getSelectionPath();
        if (path == null) {
            loadConnectionsFromState();
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof TreeNodeData) {
            TreeNodeData data = (TreeNodeData) node.getUserObject();
            if (data.getType() == TreeNodeData.NodeType.ROOT) {
                loadConnectionsFromState();
                return;
            }
            if (data.isCached()) {
                var source = connectionAncestor(node);
                loadDatabasesForConnectionNode(source, (TreeNodeData) source.getUserObject());
                return;
            }
            data.setLoaded(false);
            handleNodeExpansion(node);
        }
    }

    private void openConsoleForSelected() {
        if (mutationRunning || !loadingNodes.isEmpty()) return;
        var path = databaseTree.getSelectionPath();
        var selected = path == null ? null : (DefaultMutableTreeNode) path.getLastPathComponent();
        var source = selected == null ? null : connectionAncestor(selected);
        if (source == null) { source = firstConnectedSource(); selected = source; }
        if (source == null || !connectionAvailable(selected)) return;
        var data = (TreeNodeData) selected.getUserObject();
        DatabaseEditorManager.getInstance(project).openConsole(data.getConnectionConfig(), data.getDatabaseName(), null);
    }

    @FunctionalInterface private interface DdlOperation { void execute(Connection connection) throws Exception; }

    private void runMutation(ConnectionConfig config, String message, String failure, DdlOperation operation,
                             DatabaseChange change, Runnable onSuccess) {
        if (disposed || project.isDisposed() || mutationRunning || !loadingNodes.isEmpty()) return;
        mutationRunning = true;
        databaseTree.setEnabled(false);
        updateExplorerStatus(message, DatabaseUi.Tone.BUSY);
        tasks.submitMutation(() -> {
            try {
                com.segfault03.ideadb.service.DataService.getInstance().withMutationConnection(
                        DatabaseConnectionManager.getInstance().openConnection(config), conn -> { operation.execute(conn); return null; });
                DatabaseChangeListener.publish(change);
                SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    mutationRunning = false;
                    databaseTree.setEnabled(true);
                    updateExplorerStatus("Database operation completed", DatabaseUi.Tone.SUCCESS);
                    onSuccess.run();
                    drainRefreshes();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    mutationRunning = false;
                    databaseTree.setEnabled(true);
                    updateExplorerStatus(failure + " · See error details", DatabaseUi.Tone.ERROR);
                    Messages.showErrorDialog(project, error.getMessage(), failure);
                    drainRefreshes();
                });
            }
        });
    }

    private void doCreateDatabase(ConnectionConfig cfg) {
        CreateDatabaseDialog dlg = new CreateDatabaseDialog(project, cfg);
        if (!dlg.showAndGet()) return;
        String dbName = dlg.getDatabaseName();
        String object = DatabaseProviders.get(cfg.getType()).namespaceLabel();
        runMutation(cfg, "Creating " + object + "…", "Create " + object + " failed",
                conn -> DdlService.getInstance().createDatabase(conn, cfg, dbName), DatabaseChange.connection(cfg.getId()), () -> {
                    Messages.showInfoMessage(project, "Created " + object + " '" + dbName + "'.", "Created");
                });
    }

    private void doDropDatabase(ConnectionConfig cfg, String dbName) {
        String object = DatabaseProviders.get(cfg.getType()).namespaceLabel();
        if (!DatabaseUi.confirmDestructive(project, "Drop " + object,
                "Drop " + object + " '" + dbName + "'?\nAll tables and their data will be permanently deleted.", "Drop " + object)) return;
        runMutation(cfg, "Dropping " + object + "…", "Drop " + object + " failed",
                conn -> DdlService.getInstance().dropDatabase(conn, cfg, dbName), DatabaseChange.connection(cfg.getId()), () -> {
                    Messages.showInfoMessage(project, "Dropped " + object + " '" + dbName + "'.", "Dropped");
                });
    }

    private void doCreateTable(ConnectionConfig cfg, String dbName) {
        CreateTableDialog dlg = new CreateTableDialog(project, cfg, dbName);
        if (!dlg.showAndGet()) return;
        String tableName = dlg.getTableName();
        Messages.showInfoMessage(project, "Table '" + tableName + "' created.", "Table created");
    }

    private void doDropTable(ConnectionConfig cfg, String dbName, String tableName) {
        if (!DatabaseUi.confirmDestructive(project, "Drop table",
                "Drop table '" + tableName + "'?\nThe table structure and all its data will be permanently deleted.", "Drop table")) return;
        runMutation(cfg, "Dropping table…", "Drop table failed",
                conn -> DdlService.getInstance().dropTable(conn, cfg, dbName, tableName), DatabaseChange.table(cfg.getId(), dbName, tableName), () -> {
                    Messages.showInfoMessage(project, "Table '" + tableName + "' dropped.", "Table dropped");
                });
    }

    private void doTruncateTable(ConnectionConfig cfg, String dbName, String tableName) {
        if (!DatabaseUi.confirmDestructive(project, "Truncate table",
                "Truncate table '" + tableName + "'?\nAll rows will be permanently deleted. The table structure will be kept.", "Truncate table")) return;
        runMutation(cfg, "Truncating table…", "Truncate table failed",
                conn -> DdlService.getInstance().truncateTable(conn, cfg, dbName, tableName),
                DatabaseChange.table(cfg.getId(), dbName, tableName),
                () -> Messages.showInfoMessage(project, "All rows removed from '" + tableName + "'.", "Table truncated"));
    }
}
