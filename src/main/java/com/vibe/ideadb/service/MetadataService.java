package com.vibe.ideadb.service;

import com.vibe.ideadb.model.ColumnMetadata;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.DatabaseType;
import com.vibe.ideadb.model.TableMetadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

public class MetadataService {
    private static final MetadataService INSTANCE = new MetadataService();

    private MetadataService() {
    }

    public static MetadataService getInstance() {
        return INSTANCE;
    }

    public List<String> getDatabases(Connection conn, ConnectionConfig config) throws Exception {
        List<String> databases = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();

        if (config.getType() == DatabaseType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES;")) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            } catch (Exception e) {
                // Fallback to catalogs
                try (ResultSet rs = meta.getCatalogs()) {
                    while (rs.next()) {
                        databases.add(rs.getString("TABLE_CAT"));
                    }
                }
            }
        } else { // HSQLDB
            try (ResultSet rs = meta.getSchemas()) {
                while (rs.next()) {
                    String s = rs.getString("TABLE_SCHEM");
                    if (!databases.contains(s)) {
                        databases.add(s);
                    }
                }
            }
            if (databases.isEmpty()) {
                databases.add("PUBLIC");
            }
        }

        Collections.sort(databases);
        return databases;
    }

    public List<TableMetadata> getTables(Connection conn, ConnectionConfig config, String database) throws Exception {
        List<TableMetadata> tables = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();

        String catalog = (config.getType() == DatabaseType.MYSQL) ? database : null;
        String schema = (config.getType() == DatabaseType.HSQLDB) ? database : null;

        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                TableMetadata tm = new TableMetadata(catalog, schema, tableName, tableType);
                tables.add(tm);
            }
        }

        tables.sort(Comparator.comparing(TableMetadata::getName));
        return tables;
    }

    public List<ColumnMetadata> getColumns(Connection conn, ConnectionConfig config, String database, String tableName) throws Exception {
        List<ColumnMetadata> columns = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();

        String catalog = (config.getType() == DatabaseType.MYSQL) ? database : null;
        String schema = (config.getType() == DatabaseType.HSQLDB) ? (database != null ? database.toUpperCase() : null) : null;
        String lookupTable = (config.getType() == DatabaseType.HSQLDB && tableName != null) ? tableName.toUpperCase() : tableName;

        // Fetch Primary Keys
        Set<String> pkNames = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, lookupTable)) {
            while (rs.next()) {
                String pkCol = rs.getString("COLUMN_NAME");
                if (pkCol != null) {
                    pkNames.add(pkCol.toLowerCase());
                }
            }
        } catch (Exception ignored) {
        }
        if (pkNames.isEmpty() && tableName != null && !lookupTable.equals(tableName)) {
            try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
                while (rs.next()) {
                    String pkCol = rs.getString("COLUMN_NAME");
                    if (pkCol != null) pkNames.add(pkCol.toLowerCase());
                }
            } catch (Exception ignored) {
            }
        }

        // Fetch Columns
        fetchColumnsInto(meta, catalog, schema, lookupTable, pkNames, columns);
        if (columns.isEmpty() && tableName != null && !lookupTable.equals(tableName)) {
            fetchColumnsInto(meta, catalog, schema, tableName, pkNames, columns);
        }

        return columns;
    }

    private void fetchColumnsInto(DatabaseMetaData meta, String catalog, String schema, String tablePattern,
                                  Set<String> pkNames, List<ColumnMetadata> columns) {
        try (ResultSet rs = meta.getColumns(catalog, schema, tablePattern, "%")) {
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int dataType = rs.getInt("DATA_TYPE");
                int colSize = rs.getInt("COLUMN_SIZE");
                int decDigits = rs.getInt("DECIMAL_DIGITS");
                String isNullableStr = rs.getString("IS_NULLABLE");
                boolean nullable = "YES".equalsIgnoreCase(isNullableStr);
                String defVal = rs.getString("COLUMN_DEF");

                boolean autoInc = false;
                try {
                    String isAutoInc = rs.getString("IS_AUTOINCREMENT");
                    autoInc = "YES".equalsIgnoreCase(isAutoInc);
                } catch (Exception ignored) {
                }

                if (!autoInc) {
                    try {
                        String isGen = rs.getString("IS_GENERATEDCOLUMN");
                        if ("YES".equalsIgnoreCase(isGen)) {
                            autoInc = true;
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (!autoInc && defVal != null) {
                    String defUpper = defVal.toUpperCase();
                    if (defUpper.contains("IDENTITY") || defUpper.contains("AUTO_INCREMENT") ||
                            defUpper.contains("NEXTVAL") || defUpper.contains("GENERATED ALWAYS") ||
                            defUpper.contains("GENERATED BY DEFAULT")) {
                        autoInc = true;
                    }
                }

                if (!autoInc && typeName != null) {
                    String typeUpper = typeName.toUpperCase();
                    if (typeUpper.equals("IDENTITY") || typeUpper.contains("SERIAL")) {
                        autoInc = true;
                    }
                }

                boolean isPk = pkNames.contains(colName.toLowerCase());

                ColumnMetadata col = new ColumnMetadata(colName, typeName, dataType, colSize, decDigits,
                        nullable, isPk, autoInc, defVal);
                columns.add(col);
            }
        } catch (Exception ignored) {
        }
    }
}
