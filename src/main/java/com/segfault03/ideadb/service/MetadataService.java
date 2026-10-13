package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.provider.DatabaseProviders;
import java.sql.Connection;
import java.util.List;

public final class MetadataService {
    private static final MetadataService INSTANCE = new MetadataService();
    private MetadataService() {}
    public static MetadataService getInstance() { return INSTANCE; }
    public List<String> getDatabases(Connection conn, ConnectionConfig config) throws Exception {
        return DatabaseProviders.get(config.getType()).metadata().getDatabases(conn, config);
    }
    public List<TableMetadata> getTables(Connection conn, ConnectionConfig config, String selected) throws Exception {
        return DatabaseProviders.get(config.getType()).metadata().getTables(conn, config, selected);
    }
    public List<ColumnMetadata> getColumns(Connection conn, ConnectionConfig config, String selected, String table) throws Exception {
        return DatabaseProviders.get(config.getType()).metadata().getColumns(conn, config, selected, table);
    }
}
