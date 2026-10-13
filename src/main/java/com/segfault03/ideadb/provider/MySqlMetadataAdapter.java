package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class MySqlMetadataAdapter extends JdbcMetadataAdapter {
    protected DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(selected, null); }
    public List<String> getDatabases(Connection conn, ConnectionConfig config) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = conn.createStatement(); ResultSet rows = statement.executeQuery("SHOW DATABASES;")) {
            while (rows.next()) names.add(rows.getString(1));
        } catch (SQLException failure) {
            names.clear();
            try (ResultSet rows = conn.getMetaData().getCatalogs()) { while (rows.next()) names.add(rows.getString("TABLE_CAT")); }
        }
        Collections.sort(names); return names;
    }
    protected void enrichColumns(Connection conn, ConnectionConfig config, String database, String schema, String table, List<ColumnMetadata> columns) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        if (meta.getDatabaseMajorVersion() > 5 || meta.getDatabaseMajorVersion() == 5 && meta.getDatabaseMinorVersion() >= 6)
            enrichTemporalPrecision(conn, database == null ? conn.getCatalog() : database, table, columns);
    }
}
