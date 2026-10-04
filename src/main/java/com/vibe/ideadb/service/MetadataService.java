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
        String schema = (config.getType() == DatabaseType.HSQLDB) ? resolveSchema(meta, database) : null;

        try (ResultSet rs = meta.getTables(catalog, literalPattern(meta, schema), "%", new String[]{"TABLE", "VIEW"})) {
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
        String schema = config.getType() == DatabaseType.HSQLDB ? resolveSchema(meta, database) : null;
        String lookupTable = tableName;
        if (config.getType() == DatabaseType.HSQLDB) {
            lookupTable = resolveTable(meta, schema, tableName);
        }
        Set<String> pkNames = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, lookupTable)) {
            while (rs.next()) pkNames.add(rs.getString("COLUMN_NAME"));
        }
        fetchColumnsInto(meta, catalog, schema, lookupTable, pkNames, columns);
        boolean temporalPrecisionAvailable = config.getType() != DatabaseType.MYSQL || meta.getDatabaseMajorVersion() > 5 || meta.getDatabaseMajorVersion() == 5 && meta.getDatabaseMinorVersion() >= 6;
        if(temporalPrecisionAvailable && columns.stream().anyMatch(c -> c.getDataType()==java.sql.Types.TIME || c.getDataType()==java.sql.Types.TIMESTAMP || c.getDataType()==java.sql.Types.TIME_WITH_TIMEZONE || c.getDataType()==java.sql.Types.TIMESTAMP_WITH_TIMEZONE)) {
            Map<String,Integer> temporalPrecision=new HashMap<>();
            try(java.sql.PreparedStatement statement=conn.prepareStatement("SELECT COLUMN_NAME,DATETIME_PRECISION FROM information_schema.columns WHERE table_schema=? AND table_name=? AND DATETIME_PRECISION IS NOT NULL")) {
                statement.setString(1,config.getType()==DatabaseType.MYSQL ? (database==null ? conn.getCatalog() : database) : (schema==null ? JdbcSchema.current(conn) : schema)); statement.setString(2,lookupTable);
                try(ResultSet result=statement.executeQuery()) { while(result.next()) temporalPrecision.put(result.getString(1),result.getInt(2)); }
            }
            columns.replaceAll(c -> temporalPrecision.containsKey(c.getName()) ? new ColumnMetadata(c.getName(),c.getTypeName(),c.getDataType(),c.getColumnSize(),temporalPrecision.get(c.getName()),c.isNullable(),c.isPrimaryKey(),c.isAutoIncrement(),c.getDefaultValue()) : c);
        }

        return columns;
    }

    private void fetchColumnsInto(DatabaseMetaData meta, String catalog, String schema, String tablePattern,
                                  Set<String> pkNames, List<ColumnMetadata> columns) throws java.sql.SQLException {
        try (ResultSet rs = meta.getColumns(catalog, literalPattern(meta, schema), literalPattern(meta, tablePattern), "%")) {
            while (rs.next()) {
                if (!tablePattern.equals(rs.getString("TABLE_NAME")) && !(meta.storesLowerCaseIdentifiers() && tablePattern.equalsIgnoreCase(rs.getString("TABLE_NAME")))) continue;
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
                    String defUpper = defVal.toUpperCase(java.util.Locale.ROOT);
                    if (defUpper.contains("IDENTITY") || defUpper.contains("AUTO_INCREMENT") ||
                            defUpper.contains("NEXTVAL") || defUpper.contains("GENERATED ALWAYS") ||
                            defUpper.contains("GENERATED BY DEFAULT")) {
                        autoInc = true;
                    }
                }

                if (!autoInc && typeName != null) {
                    String typeUpper = typeName.toUpperCase(java.util.Locale.ROOT);
                    if (typeUpper.equals("IDENTITY") || typeUpper.contains("SERIAL")) {
                        autoInc = true;
                    }
                }

                boolean isPk = pkNames.contains(colName);

                ColumnMetadata col = new ColumnMetadata(colName, typeName, dataType, colSize, decDigits,
                        nullable, isPk, autoInc, defVal);
                columns.add(col);
            }
        }
    }
    private static String literalPattern(DatabaseMetaData meta, String value) throws java.sql.SQLException {
        if (value == null) return null;
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
    private static String resolveSchema(DatabaseMetaData meta, String requested) throws java.sql.SQLException {
        if (requested == null) return null;
        String folded = requested.toUpperCase(Locale.ROOT), fallback = null;
        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next()) {
                String name = rs.getString("TABLE_SCHEM");
                if (requested.equals(name)) return name;
                if (folded.equals(name)) fallback = name;
            }
        }
        return fallback == null ? requested : fallback;
    }
    private static String resolveTable(DatabaseMetaData meta, String schema, String requested) throws java.sql.SQLException {
        String folded = requested.toUpperCase(Locale.ROOT), fallback = null;
        try (ResultSet rs = meta.getTables(null, literalPattern(meta,schema), "%", null)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (requested.equals(name)) return name;
                if (folded.equals(name)) fallback = name;
            }
        }
        return fallback == null ? requested : fallback;
    }
}
