package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.provider.DatabaseFeature;
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
import com.segfault03.ideadb.state.DatabaseSettingsState;

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
    private final DatabaseExplorerHint selectionHint = new DatabaseExplorerHint();
    private final CardLayout explorerLayout = new CardLayout();
    private final JPanel explorerCards = new JPanel(explorerLayout);

    public DatabaseMainPanel(Project project) {
        super(new BorderLayout(0, 0));
        this.project = project;

        initUI();
        project.getMessageBus().connect(this).subscribe(com.segfault03.ideadb.editor.TableRenameListener.TOPIC,(config,database,oldName,renamed) -> {
            var nodes=rootNode.depthFirstEnumeration();
            while(nodes.hasMoreElements()) {
                DefaultMutableTreeNode node=(DefaultMutableTreeNode)nodes.nextElement();
                if(node.getUserObject() instanceof TreeNodeData data && data.getType()==TreeNodeData.NodeType.DATABASE && config.getId().equals(data.getConnectionConfig().getId()) && database.equals(data.getDatabaseName())) {
                    data.setLoaded(false); loadTablesForDatabaseNode(node,data); break;
                }
            }
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
                    connNode.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("")));
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
        TreePath path = databaseTree.getSelectionPath();
        if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof TreeNodeData data) {
            selection = data;
        }
        toolbar.setSelection(selection);
        selectionHint.setSelection(selection);
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
            DatabaseEditorManager.getInstance(project).openTableData(data.getConnectionConfig(), data.getDatabaseName(), data.getTableMetadata());
        }
    }

    private void showContextMenu(TreePath path, int x, int y) {
        if (mutationRunning || !loadingNodes.isEmpty()) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object uo = node.getUserObject();
        if (!(uo instanceof TreeNodeData)) return;
        TreeNodeData data = (TreeNodeData) uo;

        JPopupMenu menu = DatabaseUi.popupMenu();

        switch (data.getType()) {
            case CONNECTION: {
                ConnectionConfig cfg = data.getConnectionConfig();
                JMenuItem connectItem = new JMenuItem(data.isConnected() ? "Refresh connection" : "Connect", AllIcons.Actions.Refresh);
                connectItem.addActionListener(e -> {
                    data.setLoaded(false);
                    loadDatabasesForConnectionNode(node, data);
                });
                menu.add(connectItem);

                JMenuItem disconnectItem = new JMenuItem("Disconnect");
                disconnectItem.addActionListener(e -> {
                    DatabaseConnectionManager.getInstance().closeConnection(cfg.getId());
                    data.setConnected(false);
                    data.setLoaded(false);
                    node.removeAllChildren();
                    node.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("")));
                    treeModel.nodeStructureChanged(node);
                });
                menu.add(disconnectItem);

                menu.addSeparator();

                JMenuItem consoleItem = new JMenuItem("Open SQL console", Icons.CONSOLE);
                consoleItem.addActionListener(e -> DatabaseEditorManager.getInstance(project).openConsole(cfg, null, null));
                menu.add(consoleItem);

                JMenuItem createDbItem = new JMenuItem("Create " + DatabaseProviders.get(cfg.getType()).namespaceLabel() + "…");
                createDbItem.addActionListener(e -> doCreateDatabase(cfg, node, data));
                createDbItem.setEnabled(DatabaseProviders.get(cfg.getType()).features().contains(DatabaseFeature.CREATE_NAMESPACE));
                menu.add(createDbItem);

                menu.addSeparator();

                JMenuItem editItem = new JMenuItem("Edit connection…", AllIcons.Actions.Edit);
                editItem.addActionListener(e -> editConnection(cfg));
                menu.add(editItem);

                JMenuItem delItem = new JMenuItem("Remove connection", AllIcons.General.Remove);
                delItem.addActionListener(e -> removeConnection(cfg));
                menu.add(delItem);
                break;
            }

            case DATABASE: {
                ConnectionConfig cfg = data.getConnectionConfig();
                String dbName = data.getDatabaseName();

                JMenuItem consoleItem = new JMenuItem("Open SQL console", Icons.CONSOLE);
                consoleItem.addActionListener(e -> DatabaseEditorManager.getInstance(project).openConsole(cfg, dbName, null));
                menu.add(consoleItem);

                JMenuItem createTableItem = new JMenuItem("Create table…", Icons.TABLE);
                createTableItem.addActionListener(e -> doCreateTable(cfg, dbName, node));
                createTableItem.setEnabled(DatabaseProviders.get(cfg.getType()).features().contains(DatabaseFeature.CREATE_TABLE));
                menu.add(createTableItem);

                JMenuItem dropDbItem = new JMenuItem("Drop " + DatabaseProviders.get(cfg.getType()).namespaceLabel() + "…");
                dropDbItem.addActionListener(e -> doDropDatabase(cfg, dbName, node));
                dropDbItem.setEnabled(DatabaseProviders.get(cfg.getType()).features().contains(DatabaseFeature.DROP_NAMESPACE));
                menu.add(dropDbItem);

                menu.addSeparator();
                JMenuItem refreshItem = new JMenuItem("Refresh tables", AllIcons.Actions.Refresh);
                refreshItem.addActionListener(e -> {
                    data.setLoaded(false);
                    loadTablesForDatabaseNode(node, data);
                });
                menu.add(refreshItem);
                break;
            }

            case TABLE:
            case VIEW: {
                ConnectionConfig cfg = data.getConnectionConfig();
                String dbName = data.getDatabaseName();
                TableMetadata tm = data.getTableMetadata();

                JMenuItem viewDataItem = new JMenuItem("Open data editor", AllIcons.Actions.Preview);
                viewDataItem.setFont(viewDataItem.getFont().deriveFont(Font.BOLD));
                viewDataItem.addActionListener(e -> DatabaseEditorManager.getInstance(project).openTableData(cfg, dbName, tm));
                menu.add(viewDataItem);

                JMenuItem consoleItem = new JMenuItem("Open in SQL console", Icons.CONSOLE);
                consoleItem.addActionListener(e -> {
                    String query = DdlService.getInstance().buildSelectSql(cfg, dbName, tm.getName(), List.of());
                    DatabaseEditorManager.getInstance(project).openConsole(cfg, dbName, query);
                });
                menu.add(consoleItem);

                if (!tm.isView()) {
                    menu.addSeparator();

                    JMenuItem alterItem = new JMenuItem("Alter table…", AllIcons.Actions.Edit);
                    alterItem.addActionListener(e -> {
                        AlterTableDialog dlg = new AlterTableDialog(project, cfg, dbName, tm);
                        if (dlg.showAndGet()) {
                            DefaultMutableTreeNode databaseNode = databaseAncestor(node);
                            if (databaseNode != null && databaseNode.getUserObject() instanceof TreeNodeData databaseData) {
                                databaseData.setLoaded(false); loadTablesForDatabaseNode(databaseNode, databaseData);
                            }
                        }
                    });
                    alterItem.setEnabled(DatabaseProviders.get(cfg.getType()).features().contains(DatabaseFeature.MODIFY_COLUMN));
                    menu.add(alterItem);

                    JMenuItem truncateItem = new JMenuItem("Truncate table…", AllIcons.General.Remove);
                    truncateItem.addActionListener(e -> doTruncateTable(cfg, dbName, tm.getName()));
                    truncateItem.setEnabled(DatabaseProviders.get(cfg.getType()).features().contains(DatabaseFeature.TRUNCATE_TABLE));
                    menu.add(truncateItem);

                    JMenuItem dropItem = new JMenuItem("Drop table…", AllIcons.General.Remove);
                    dropItem.addActionListener(e -> doDropTable(cfg, dbName, tm.getName(), node));
                    dropItem.setEnabled(DatabaseProviders.get(cfg.getType()).features().contains(DatabaseFeature.DROP_TABLE));
                    menu.add(dropItem);
                }

                menu.addSeparator();
                JMenu genSqlMenu = new JMenu("Generate SQL");
                JMenuItem genSelect = new JMenuItem("SELECT Statement");
                genSelect.addActionListener(e -> {
                    List<String> cols = tm.getColumns().stream().map(ColumnMetadata::getName).toList();
                    String sql = DdlService.getInstance().buildSelectSql(cfg, dbName, tm.getName(), cols);
                    DatabaseEditorManager.getInstance(project).openConsole(cfg, dbName, sql);
                });
                genSqlMenu.add(genSelect);

                JMenuItem genInsert = new JMenuItem("INSERT Statement");
                genInsert.addActionListener(e -> {
                    List<String> cols = tm.getColumns().stream().map(ColumnMetadata::getName).toList();
                    String sql = DdlService.getInstance().buildInsertSql(cfg, dbName, tm.getName(), cols);
                    DatabaseEditorManager.getInstance(project).openConsole(cfg, dbName, sql);
                });
                genSqlMenu.add(genInsert);
                menu.add(genSqlMenu);
                break;
            }

            default:
                break;
        }

        if (menu.getComponentCount() > 0) {
            menu.show(databaseTree, x, y);
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
        return node.getRoot() == rootNode;
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

    private void loadDatabasesForConnectionNode(DefaultMutableTreeNode connNode, TreeNodeData data) {
        ConnectionConfig cfg = data.getConnectionConfig();
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
                        DefaultMutableTreeNode dbNode = new DefaultMutableTreeNode(TreeNodeData.database(cfg, db));
                        dbNode.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("Expand to load tables...")));
                        connNode.add(dbNode);
                    }

                    treeModel.nodeStructureChanged(connNode);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(connNode, data, "Could not load databases · Refresh to retry", DatabaseUi.Tone.ERROR)) return;
                    connNode.removeAllChildren();
                    data.setConnected(false);
                    connNode.add(new DefaultMutableTreeNode(TreeNodeData.error("Connection failed · Refresh to retry", ex.getMessage())));
                    treeModel.nodeStructureChanged(connNode);
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
        if (!beginNodeLoad(dbNode, data, "Loading tables…")) return;

        tasks.submit(() -> {
            try {
                List<TableMetadata> tables = DatabaseConnectionManager.getInstance().executeBrowser(cfg, conn -> MetadataService.getInstance().getTables(conn, cfg, dbName));

                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(dbNode, data, "Loaded tables", DatabaseUi.Tone.NORMAL)) return;
                    dbNode.removeAllChildren();
                    data.setLoaded(true);

                    List<TableMetadata> regularTables = new ArrayList<>();
                    List<TableMetadata> views = new ArrayList<>();
                    for (TableMetadata tm : tables) {
                        if (tm.isView()) views.add(tm);
                        else regularTables.add(tm);
                    }

                    DefaultMutableTreeNode tablesFolder = new DefaultMutableTreeNode(TreeNodeData.tablesFolder(cfg, dbName, regularTables.size()));
                    for (TableMetadata tm : regularTables) {
                        DefaultMutableTreeNode tableNode = new DefaultMutableTreeNode(TreeNodeData.table(cfg, dbName, tm));
                        tableNode.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("Expand to load columns...")));
                        tablesFolder.add(tableNode);
                    }
                    dbNode.add(tablesFolder);

                    if (!views.isEmpty()) {
                        DefaultMutableTreeNode viewsFolder = new DefaultMutableTreeNode(TreeNodeData.viewsFolder(cfg, dbName, views.size()));
                        for (TableMetadata tm : views) {
                            DefaultMutableTreeNode viewNode = new DefaultMutableTreeNode(TreeNodeData.table(cfg, dbName, tm));
                            viewNode.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("Expand to load columns...")));
                            viewsFolder.add(viewNode);
                        }
                        dbNode.add(viewsFolder);
                    }

                    treeModel.nodeStructureChanged(dbNode);
                    databaseTree.expandPath(new TreePath(tablesFolder.getPath()));
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(dbNode, data, "Could not load tables · Refresh to retry", DatabaseUi.Tone.ERROR)) return;
                    dbNode.removeAllChildren();
                    dbNode.add(new DefaultMutableTreeNode(TreeNodeData.error("Could not load tables", ex.getMessage())));
                    treeModel.nodeStructureChanged(dbNode);
                });
            }
        });
    }

    private void loadColumnsForTableNode(DefaultMutableTreeNode tableNode, TreeNodeData data) {
        ConnectionConfig cfg = data.getConnectionConfig();
        String dbName = data.getDatabaseName();
        TableMetadata tm = data.getTableMetadata();
        if (!beginNodeLoad(tableNode, data, "Loading columns…")) return;

        tasks.submit(() -> {
            try {
                List<ColumnMetadata> cols = DatabaseConnectionManager.getInstance().executeBrowser(cfg, conn -> MetadataService.getInstance().getColumns(conn, cfg, dbName, tm.getName()));
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(tableNode, data, "Loaded columns", DatabaseUi.Tone.NORMAL)) return;
                    tm.setColumns(cols);
                    tableNode.removeAllChildren();
                    data.setLoaded(true);

                    DefaultMutableTreeNode colsFolder = new DefaultMutableTreeNode(TreeNodeData.columnsFolder(cfg, dbName, tm, cols.size()));
                    for (ColumnMetadata col : cols) {
                        colsFolder.add(new DefaultMutableTreeNode(TreeNodeData.column(cfg, dbName, tm, col)));
                    }
                    tableNode.add(colsFolder);

                    treeModel.nodeStructureChanged(tableNode);
                    databaseTree.expandPath(new TreePath(colsFolder.getPath()));
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!finishNodeLoad(tableNode, data, "Could not load columns · Refresh to retry", DatabaseUi.Tone.ERROR)) return;
                    tableNode.removeAllChildren();
                    tableNode.add(new DefaultMutableTreeNode(TreeNodeData.error("Could not load columns", ex.getMessage())));
                    treeModel.nodeStructureChanged(tableNode);
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
            data.setLoaded(false);
            handleNodeExpansion(node);
        }
    }

    private void openConsoleForSelected() {
        TreePath path = databaseTree.getSelectionPath();
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof TreeNodeData) {
                TreeNodeData data = (TreeNodeData) node.getUserObject();
                if (data.getConnectionConfig() != null) {
                    DatabaseEditorManager.getInstance(project).openConsole(data.getConnectionConfig(), data.getDatabaseName(), null);
                    return;
                }
            }
        }
        // Fallback to first available connection
        List<ConnectionConfig> configs = DatabaseSettingsState.getInstance().getConnections();
        if (!configs.isEmpty()) {
            DatabaseEditorManager.getInstance(project).openConsole(configs.get(0), null, null);
        } else {
            showAddConnectionDialog(DatabaseType.HSQLDB);
        }
    }

    @FunctionalInterface private interface DdlOperation { void execute(Connection connection) throws Exception; }

    private void runMutation(ConnectionConfig config, String message, String failure, DdlOperation operation, Runnable onSuccess) {
        if (disposed || project.isDisposed() || mutationRunning || !loadingNodes.isEmpty()) return;
        mutationRunning = true;
        databaseTree.setEnabled(false);
        updateExplorerStatus(message, DatabaseUi.Tone.BUSY);
        tasks.submitMutation(() -> {
            try {
                DatabaseConnectionManager.getInstance().executeBrowser(config, conn -> { operation.execute(conn); return null; });
                SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    mutationRunning = false;
                    databaseTree.setEnabled(true);
                    updateExplorerStatus("Database operation completed", DatabaseUi.Tone.SUCCESS);
                    onSuccess.run();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    mutationRunning = false;
                    databaseTree.setEnabled(true);
                    updateExplorerStatus(failure + " · See error details", DatabaseUi.Tone.ERROR);
                    Messages.showErrorDialog(project, error.getMessage(), failure);
                });
            }
        });
    }

    private void doCreateDatabase(ConnectionConfig cfg, DefaultMutableTreeNode connNode, TreeNodeData connData) {
        CreateDatabaseDialog dlg = new CreateDatabaseDialog(project, cfg);
        if (!dlg.showAndGet()) return;
        String dbName = dlg.getDatabaseName();
        String object = DatabaseProviders.get(cfg.getType()).namespaceLabel();
        runMutation(cfg, "Creating " + object + "…", "Create " + object + " failed",
                conn -> DdlService.getInstance().createDatabase(conn, cfg, dbName), () -> {
                    Messages.showInfoMessage(project, "Created " + object + " '" + dbName + "'.", "Created");
                    connData.setLoaded(false);
                    loadDatabasesForConnectionNode(connNode, connData);
                });
    }

    private void doDropDatabase(ConnectionConfig cfg, String dbName, DefaultMutableTreeNode dbNode) {
        String object = DatabaseProviders.get(cfg.getType()).namespaceLabel();
        if (!DatabaseUi.confirmDestructive(project, "Drop " + object,
                "Drop " + object + " '" + dbName + "'?\nAll tables and their data will be permanently deleted.", "Drop " + object)) return;
        runMutation(cfg, "Dropping " + object + "…", "Drop " + object + " failed",
                conn -> DdlService.getInstance().dropDatabase(conn, cfg, dbName), () -> {
                    Messages.showInfoMessage(project, "Dropped " + object + " '" + dbName + "'.", "Dropped");
                    if (dbNode.getParent() instanceof DefaultMutableTreeNode parent) {
                        TreeNodeData data = (TreeNodeData) parent.getUserObject();
                        data.setLoaded(false);
                        loadDatabasesForConnectionNode(parent, data);
                    }
                });
    }

    private void doCreateTable(ConnectionConfig cfg, String dbName, DefaultMutableTreeNode dbNode) {
        CreateTableDialog dlg = new CreateTableDialog(project, cfg, dbName);
        if (!dlg.showAndGet()) return;
        String tableName = dlg.getTableName();
        Messages.showInfoMessage(project, "Table '" + tableName + "' created.", "Table created");
        TreeNodeData data = (TreeNodeData) dbNode.getUserObject();
        data.setLoaded(false);
        loadTablesForDatabaseNode(dbNode, data);
    }

    private void doDropTable(ConnectionConfig cfg, String dbName, String tableName, DefaultMutableTreeNode tableNode) {
        if (!DatabaseUi.confirmDestructive(project, "Drop table",
                "Drop table '" + tableName + "'?\nThe table structure and all its data will be permanently deleted.", "Drop table")) return;
        runMutation(cfg, "Dropping table…", "Drop table failed",
                conn -> DdlService.getInstance().dropTable(conn, cfg, dbName, tableName), () -> {
                    Messages.showInfoMessage(project, "Table '" + tableName + "' dropped.", "Table dropped");
                    if (tableNode.getParent() != null) treeModel.removeNodeFromParent(tableNode);
                });
    }

    private void doTruncateTable(ConnectionConfig cfg, String dbName, String tableName) {
        if (!DatabaseUi.confirmDestructive(project, "Truncate table",
                "Truncate table '" + tableName + "'?\nAll rows will be permanently deleted. The table structure will be kept.", "Truncate table")) return;
        runMutation(cfg, "Truncating table…", "Truncate table failed",
                conn -> DdlService.getInstance().truncateTable(conn, cfg, dbName, tableName),
                () -> Messages.showInfoMessage(project, "All rows removed from '" + tableName + "'.", "Table truncated"));
    }
}
