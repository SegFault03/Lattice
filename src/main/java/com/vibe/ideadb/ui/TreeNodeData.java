package com.vibe.ideadb.ui;

import com.vibe.ideadb.model.ColumnMetadata;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.TableMetadata;

public class TreeNodeData {
    public enum NodeType {
        ROOT,
        CONNECTION,
        DATABASE,
        TABLES_FOLDER,
        TABLE,
        VIEWS_FOLDER,
        VIEW,
        COLUMNS_FOLDER,
        COLUMN,
        LOADING
    }

    private final NodeType type;
    private String name;
    private ConnectionConfig connectionConfig;
    private String databaseName;
    private TableMetadata tableMetadata;
    private ColumnMetadata columnMetadata;
    private boolean connected;
    private boolean loaded;

    public TreeNodeData(NodeType type, String name) {
        this.type = type;
        this.name = name;
    }

    public static TreeNodeData root() {
        return new TreeNodeData(NodeType.ROOT, "Data Sources");
    }

    public static TreeNodeData connection(ConnectionConfig config, boolean connected) {
        TreeNodeData data = new TreeNodeData(NodeType.CONNECTION, config.getName());
        data.connectionConfig = config;
        data.connected = connected;
        return data;
    }

    public static TreeNodeData database(ConnectionConfig config, String dbName) {
        TreeNodeData data = new TreeNodeData(NodeType.DATABASE, dbName);
        data.connectionConfig = config;
        data.databaseName = dbName;
        return data;
    }

    public static TreeNodeData tablesFolder(ConnectionConfig config, String dbName, int count) {
        TreeNodeData data = new TreeNodeData(NodeType.TABLES_FOLDER, "Tables (" + count + ")");
        data.connectionConfig = config;
        data.databaseName = dbName;
        return data;
    }

    public static TreeNodeData viewsFolder(ConnectionConfig config, String dbName, int count) {
        TreeNodeData data = new TreeNodeData(NodeType.VIEWS_FOLDER, "Views (" + count + ")");
        data.connectionConfig = config;
        data.databaseName = dbName;
        return data;
    }

    public static TreeNodeData table(ConnectionConfig config, String dbName, TableMetadata table) {
        TreeNodeData data = new TreeNodeData(table.isView() ? NodeType.VIEW : NodeType.TABLE, table.getName());
        data.connectionConfig = config;
        data.databaseName = dbName;
        data.tableMetadata = table;
        return data;
    }

    public static TreeNodeData columnsFolder(ConnectionConfig config, String dbName, TableMetadata table, int count) {
        TreeNodeData data = new TreeNodeData(NodeType.COLUMNS_FOLDER, "Columns (" + count + ")");
        data.connectionConfig = config;
        data.databaseName = dbName;
        data.tableMetadata = table;
        return data;
    }

    public static TreeNodeData column(ConnectionConfig config, String dbName, TableMetadata table, ColumnMetadata col) {
        TreeNodeData data = new TreeNodeData(NodeType.COLUMN, col.toString());
        data.connectionConfig = config;
        data.databaseName = dbName;
        data.tableMetadata = table;
        data.columnMetadata = col;
        return data;
    }

    public static TreeNodeData loading(String text) {
        return new TreeNodeData(NodeType.LOADING, text);
    }

    public NodeType getType() { return type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ConnectionConfig getConnectionConfig() { return connectionConfig; }
    public void setConnectionConfig(ConnectionConfig config) { this.connectionConfig = config; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public TableMetadata getTableMetadata() { return tableMetadata; }
    public ColumnMetadata getColumnMetadata() { return columnMetadata; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public boolean isLoaded() { return loaded; }
    public void setLoaded(boolean loaded) { this.loaded = loaded; }

    @Override
    public String toString() {
        return name;
    }
}
