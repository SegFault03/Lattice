package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
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
import javax.swing.tree.DefaultTreeModel;
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

    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private Tree databaseTree;
    private DatabaseExplorerToolbar toolbar;
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

    @Override public void dispose() {
        disposed=true; tasks.cancelPending();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(tasks::close);
    }
    private void initUI() {
        toolbar = new DatabaseExplorerToolbar(this::showAddConnectionMenu,
                this::editSelectedConnection, this::removeSelectedConnection,
                this::refreshSelectedNode, this::openConsoleForSelected,
                () -> DatabaseEditorManager.getInstance(project).openWelcome());
        add(toolbar, BorderLayout.NORTH);
        setPreferredSize(JBUI.size(260, 400));

        // Database Tree
        rootNode = new DefaultMutableTreeNode(TreeNodeData.root());
        treeModel = new DefaultTreeModel(rootNode);
        databaseTree = new Tree(treeModel);
        databaseTree.setCellRenderer(new DatabaseTreeCellRenderer());
        databaseTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        databaseTree.setRootVisible(true);
        databaseTree.setShowsRootHandles(true);

        setupTreeListeners();

        explorerCards.add(new JBScrollPane(databaseTree), "tree");
        explorerCards.add(new EmptyConnectionPanel(
                () -> showAddConnectionDialog(DatabaseType.MYSQL)), "empty");
        add(explorerCards, BorderLayout.CENTER);

        // Subtle bottom hint bar
        JPanel hintBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        hintBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JBLabel hintLabel = new JBLabel("Double-click table to open in editor tab");
        hintLabel.setFont(hintLabel.getFont().deriveFont(10.5f));
        hintLabel.setForeground(new JBColor(new Color(130, 130, 130), new Color(150, 150, 150)));
        hintBar.add(hintLabel);
        add(hintBar, BorderLayout.SOUTH);
    }

    public void loadConnectionsFromState() {
        rootNode.removeAllChildren();
        List<ConnectionConfig> configs = DatabaseSettingsState.getInstance().getConnections();
        toolbar.setHasConnections(!configs.isEmpty());
        explorerLayout.show(explorerCards, configs.isEmpty() ? "empty" : "tree");
        if (!configs.isEmpty()) {
            for (ConnectionConfig cfg : configs) {
                boolean connected = DatabaseConnectionManager.getInstance().isConnected(cfg.getId());
                DefaultMutableTreeNode connNode = new DefaultMutableTreeNode(TreeNodeData.connection(cfg, connected));
                connNode.add(new DefaultMutableTreeNode(TreeNodeData.loading("Expand to load databases...")));
                rootNode.add(connNode);
            }
        }
        treeModel.reload();
        databaseTree.expandPath(new TreePath(rootNode.getPath()));
    }

    private void setupTreeListeners() {
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

    private void handleNodeExpansion(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        if (!(uo instanceof TreeNodeData)) return;
        TreeNodeData data = (TreeNodeData) uo;

        if (data.isLoaded()) return;

        if (data.getType() == TreeNodeData.NodeType.CONNECTION) {
            loadDatabasesForConnectionNode(node, data);
        } else if (data.getType() == TreeNodeData.NodeType.DATABASE) {
            loadTablesForDatabaseNode(node, data);
        } else if (data.getType() == TreeNodeData.NodeType.TABLE || data.getType() == TreeNodeData.NodeType.VIEW) {
            loadColumnsForTableNode(node, data);
        }
    }

    private void handleNodeDoubleClick(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        if (!(uo instanceof TreeNodeData)) return;
        TreeNodeData data = (TreeNodeData) uo;

        if (data.getType() == TreeNodeData.NodeType.TABLE || data.getType() == TreeNodeData.NodeType.VIEW) {
            DatabaseEditorManager.getInstance(project).openTableData(data.getConnectionConfig(), data.getDatabaseName(), data.getTableMetadata());
        }
    }

    private void showContextMenu(TreePath path, int x, int y) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object uo = node.getUserObject();
        if (!(uo instanceof TreeNodeData)) return;
        TreeNodeData data = (TreeNodeData) uo;

        JPopupMenu menu = new JPopupMenu();

        switch (data.getType()) {
            case CONNECTION: {
                ConnectionConfig cfg = data.getConnectionConfig();
                JMenuItem connectItem = new JMenuItem("Connect / Reload", AllIcons.Actions.Refresh);
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
                    node.add(new DefaultMutableTreeNode(TreeNodeData.loading("Expand to load databases...")));
                    treeModel.nodeStructureChanged(node);
                });
                menu.add(disconnectItem);

                menu.addSeparator();

                JMenuItem consoleItem = new JMenuItem("Open SQL Console", Icons.CONSOLE);
                consoleItem.addActionListener(e -> DatabaseEditorManager.getInstance(project).openConsole(cfg, null, null));
                menu.add(consoleItem);

                JMenuItem createDbItem = new JMenuItem("Create " + (cfg.getType() == DatabaseType.MYSQL ? "Database..." : "Schema..."));
                createDbItem.addActionListener(e -> doCreateDatabase(cfg, node, data));
                menu.add(createDbItem);

                menu.addSeparator();

                JMenuItem editItem = new JMenuItem("Edit Connection Properties...", AllIcons.Actions.Edit);
                editItem.addActionListener(e -> editConnection(cfg));
                menu.add(editItem);

                JMenuItem delItem = new JMenuItem("Remove Connection", AllIcons.General.Remove);
                delItem.addActionListener(e -> removeConnection(cfg));
                menu.add(delItem);
                break;
            }

            case DATABASE: {
                ConnectionConfig cfg = data.getConnectionConfig();
                String dbName = data.getDatabaseName();

                JMenuItem consoleItem = new JMenuItem("Open SQL Console", Icons.CONSOLE);
                consoleItem.addActionListener(e -> DatabaseEditorManager.getInstance(project).openConsole(cfg, dbName, null));
                menu.add(consoleItem);

                JMenuItem createTableItem = new JMenuItem("Create Table...", Icons.TABLE);
                createTableItem.addActionListener(e -> doCreateTable(cfg, dbName, node));
                menu.add(createTableItem);

                JMenuItem dropDbItem = new JMenuItem("Drop " + (cfg.getType() == DatabaseType.MYSQL ? "Database..." : "Schema..."));
                dropDbItem.addActionListener(e -> doDropDatabase(cfg, dbName, node));
                menu.add(dropDbItem);

                menu.addSeparator();
                JMenuItem refreshItem = new JMenuItem("Refresh Tables", AllIcons.Actions.Refresh);
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

                JMenuItem viewDataItem = new JMenuItem("View / Edit Data", AllIcons.Actions.Preview);
                viewDataItem.setFont(viewDataItem.getFont().deriveFont(Font.BOLD));
                viewDataItem.addActionListener(e -> DatabaseEditorManager.getInstance(project).openTableData(cfg, dbName, tm));
                menu.add(viewDataItem);

                JMenuItem consoleItem = new JMenuItem("Open in SQL Console", Icons.CONSOLE);
                consoleItem.addActionListener(e -> {
                    String query = "SELECT * FROM " + DdlService.formatTable(cfg,dbName,tm.getName()) + " LIMIT 100;";
                    DatabaseEditorManager.getInstance(project).openConsole(cfg, dbName, query);
                });
                menu.add(consoleItem);

                if (!tm.isView()) {
                    menu.addSeparator();

                    JMenuItem alterItem = new JMenuItem("Modify / Alter Table...", AllIcons.Actions.Edit);
                    alterItem.addActionListener(e -> {
                        AlterTableDialog dlg = new AlterTableDialog(project, cfg, dbName, tm);
                        if (dlg.showAndGet()) {
                            if(node.getParent() instanceof DefaultMutableTreeNode parent && parent.getUserObject() instanceof TreeNodeData parentData) {
                                parentData.setLoaded(false); loadTablesForDatabaseNode(parent,parentData);
                            }
                        }
                    });
                    menu.add(alterItem);

                    JMenuItem truncateItem = new JMenuItem("Truncate Table...");
                    truncateItem.addActionListener(e -> doTruncateTable(cfg, dbName, tm.getName()));
                    menu.add(truncateItem);

                    JMenuItem dropItem = new JMenuItem("Drop Table...", AllIcons.General.Remove);
                    dropItem.addActionListener(e -> doDropTable(cfg, dbName, tm.getName(), node));
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

    private void loadDatabasesForConnectionNode(DefaultMutableTreeNode connNode, TreeNodeData data) {
        ConnectionConfig cfg = data.getConnectionConfig();
        tasks.submit(() -> {
            try {
                Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
                List<String> dbs = MetadataService.getInstance().getDatabases(conn, cfg);

                SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
                    connNode.removeAllChildren();
                    data.setConnected(true);
                    data.setLoaded(true);

                    for (String db : dbs) {
                        DefaultMutableTreeNode dbNode = new DefaultMutableTreeNode(TreeNodeData.database(cfg, db));
                        dbNode.add(new DefaultMutableTreeNode(TreeNodeData.loading("Expand to load tables...")));
                        connNode.add(dbNode);
                    }

                    treeModel.nodeStructureChanged(connNode);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
                    connNode.removeAllChildren();
                    data.setConnected(false);
                    connNode.add(new DefaultMutableTreeNode(TreeNodeData.loading("Error: " + ex.getMessage())));
                    treeModel.nodeStructureChanged(connNode);
                    Messages.showErrorDialog(project, "Failed to connect: " + ex.getMessage(), "Connection Error");
                });
            }
        });
    }

    private void loadTablesForDatabaseNode(DefaultMutableTreeNode dbNode, TreeNodeData data) {
        ConnectionConfig cfg = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        tasks.submit(() -> {
            try {
                Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
                List<TableMetadata> tables = MetadataService.getInstance().getTables(conn, cfg, dbName);

                SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
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
                        tableNode.add(new DefaultMutableTreeNode(TreeNodeData.loading("Expand to load columns...")));
                        tablesFolder.add(tableNode);
                    }
                    dbNode.add(tablesFolder);

                    if (!views.isEmpty()) {
                        DefaultMutableTreeNode viewsFolder = new DefaultMutableTreeNode(TreeNodeData.viewsFolder(cfg, dbName, views.size()));
                        for (TableMetadata tm : views) {
                            DefaultMutableTreeNode viewNode = new DefaultMutableTreeNode(TreeNodeData.table(cfg, dbName, tm));
                            viewNode.add(new DefaultMutableTreeNode(TreeNodeData.loading("Expand to load columns...")));
                            viewsFolder.add(viewNode);
                        }
                        dbNode.add(viewsFolder);
                    }

                    treeModel.nodeStructureChanged(dbNode);
                    databaseTree.expandPath(new TreePath(tablesFolder.getPath()));
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
                    dbNode.removeAllChildren();
                    dbNode.add(new DefaultMutableTreeNode(TreeNodeData.loading("Error: " + ex.getMessage())));
                    treeModel.nodeStructureChanged(dbNode);
                });
            }
        });
    }

    private void loadColumnsForTableNode(DefaultMutableTreeNode tableNode, TreeNodeData data) {
        ConnectionConfig cfg = data.getConnectionConfig();
        String dbName = data.getDatabaseName();
        TableMetadata tm = data.getTableMetadata();

        tasks.submit(() -> {
            try {
                Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
                List<ColumnMetadata> cols = MetadataService.getInstance().getColumns(conn, cfg, dbName, tm.getName());
                tm.getColumns().clear();
                for (ColumnMetadata col : cols) {
                    tm.addColumn(col);
                }

                SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
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
                    if(disposed || project.isDisposed()) return;
                    tableNode.removeAllChildren();
                    tableNode.add(new DefaultMutableTreeNode(TreeNodeData.loading("Error: " + ex.getMessage())));
                    treeModel.nodeStructureChanged(tableNode);
                });
            }
        });
    }

    private void showAddConnectionMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem mysqlItem = new JMenuItem("MySQL Database...");
        mysqlItem.addActionListener(e -> showAddConnectionDialog(DatabaseType.MYSQL));
        menu.add(mysqlItem);

        JMenuItem hsqlItem = new JMenuItem("HSQLDB Database...");
        hsqlItem.addActionListener(e -> showAddConnectionDialog(DatabaseType.HSQLDB));
        menu.add(hsqlItem);

        menu.show(invoker, 0, invoker.getHeight());
    }

    private void showAddConnectionDialog(DatabaseType defaultType) {
        ConnectionConfig newCfg = new ConnectionConfig(defaultType, "New " + defaultType.getDisplayName());
        newCfg.setDatabaseName("");
        newCfg.setUser("");
        ConnectionDialog dlg = new ConnectionDialog(project, newCfg);
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
        int confirm = Messages.showYesNoDialog(project, "Remove connection '" + cfg.getName() + "'?", "Confirm Remove", Messages.getQuestionIcon());
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

    private void doCreateDatabase(ConnectionConfig cfg, DefaultMutableTreeNode connNode, TreeNodeData connData) {
        CreateDatabaseDialog dlg = new CreateDatabaseDialog(project, cfg);
        if (dlg.showAndGet()) {
            String dbName = dlg.getDatabaseName();
            tasks.submitMutation(() -> {
                try {
                    Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
                    DdlService.getInstance().createDatabase(conn, cfg, dbName);
                    SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
                        Messages.showInfoMessage(project, "Database/Schema '" + dbName + "' created successfully!", "Success");
                        connData.setLoaded(false);
                        loadDatabasesForConnectionNode(connNode, connData);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> Messages.showErrorDialog(project, "Failed to create database: " + ex.getMessage(), "Error"));
                }
            });
        }
    }

    private void doDropDatabase(ConnectionConfig cfg, String dbName, DefaultMutableTreeNode dbNode) {
        int confirm = Messages.showYesNoDialog(project, "Are you sure you want to drop database '" + dbName + "'?\nAll tables and data will be destroyed.",
                "Confirm Drop Database", Messages.getWarningIcon());
        if (confirm != Messages.YES) return;

        tasks.submitMutation(() -> {
            try {
                Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
                DdlService.getInstance().dropDatabase(conn, cfg, dbName);
                SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
                    Messages.showInfoMessage(project, "Database '" + dbName + "' dropped successfully.", "Success");
                    DefaultMutableTreeNode parent = (DefaultMutableTreeNode) dbNode.getParent();
                    if (parent != null) {
                        TreeNodeData parentData = (TreeNodeData) parent.getUserObject();
                        parentData.setLoaded(false);
                        loadDatabasesForConnectionNode(parent, parentData);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> Messages.showErrorDialog(project, "Failed to drop database: " + ex.getMessage(), "Error"));
            }
        });
    }

    private void doCreateTable(ConnectionConfig cfg, String dbName, DefaultMutableTreeNode dbNode) {
        CreateTableDialog dlg = new CreateTableDialog(project, cfg, dbName);
        if (dlg.showAndGet()) {
            String tableName = dlg.getTableName();
            List<ColumnDefinition> cols = dlg.getColumns();

            tasks.submitMutation(() -> {
                try {
                    Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
                    DdlService.getInstance().createTable(conn, cfg, dbName, tableName, cols);
                    SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
                        Messages.showInfoMessage(project, "Table '" + tableName + "' created successfully!", "Success");
                        TreeNodeData data = (TreeNodeData) dbNode.getUserObject();
                        data.setLoaded(false);
                        loadTablesForDatabaseNode(dbNode, data);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> Messages.showErrorDialog(project, "Failed to create table: " + ex.getMessage(), "Error"));
                }
            });
        }
    }

    private void doDropTable(ConnectionConfig cfg, String dbName, String tableName, DefaultMutableTreeNode tableNode) {
        int confirm = Messages.showYesNoDialog(project, "Are you sure you want to drop table '" + tableName + "'?",
                "Confirm Drop Table", Messages.getWarningIcon());
        if (confirm != Messages.YES) return;

        tasks.submitMutation(() -> {
            try {
                Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
                DdlService.getInstance().dropTable(conn, cfg, dbName, tableName);
                SwingUtilities.invokeLater(() -> {
                    if(disposed || project.isDisposed()) return;
                    Messages.showInfoMessage(project, "Table '" + tableName + "' dropped.", "Success");
                    DefaultMutableTreeNode parent = (DefaultMutableTreeNode) tableNode.getParent();
                    if (parent != null) {
                        treeModel.removeNodeFromParent(tableNode);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> Messages.showErrorDialog(project, "Failed to drop table: " + ex.getMessage(), "Error"));
            }
        });
    }

    private void doTruncateTable(ConnectionConfig cfg, String dbName, String tableName) {
        int confirm = Messages.showYesNoDialog(project, "Are you sure you want to truncate table '" + tableName + "'?\nAll records will be deleted.",
                "Confirm Truncate Table", Messages.getWarningIcon());
        if (confirm != Messages.YES) return;

        tasks.submitMutation(() -> {
            try {
                Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
                DdlService.getInstance().truncateTable(conn, cfg, dbName, tableName);
                SwingUtilities.invokeLater(() -> Messages.showInfoMessage(project, "Table '" + tableName + "' truncated successfully.", "Success"));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> Messages.showErrorDialog(project, "Failed to truncate table: " + ex.getMessage(), "Error"));
            }
        });
    }
}
