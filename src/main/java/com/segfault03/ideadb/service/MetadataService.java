package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.state.MetadataCacheState;
import java.sql.Connection;
import java.util.List;

public final class MetadataService {
    private static final MetadataService INSTANCE = new MetadataService();
    private MetadataService() {}
    public static MetadataService getInstance() { return INSTANCE; }
    public List<String> getDatabases(Connection conn, ConnectionConfig config) throws Exception {
        var databases = DatabaseProviders.get(config.getType()).metadata().getDatabases(conn, config);
        MetadataCacheState.getInstance().databasesRefreshed(config, databases);
        return databases;
    }
    public List<TableMetadata> getTables(Connection conn, ConnectionConfig config, String selected) throws Exception {
        var tables = DatabaseProviders.get(config.getType()).metadata().getTables(conn, config, selected);
        MetadataCacheState.getInstance().tablesRefreshed(config, selected, tables);
        return tables;
    }
    public List<ColumnMetadata> getColumns(Connection conn, ConnectionConfig config, String selected, String table) throws Exception {
        var columns = DatabaseProviders.get(config.getType()).metadata().getColumns(conn, config, selected, table);
        MetadataCacheState.getInstance().columnsRefreshed(config, selected, table, columns);
        return columns;
    }
}
