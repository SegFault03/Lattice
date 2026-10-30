package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ColumnReference;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.TableMetadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

abstract class JdbcMetadataAdapter implements MetadataAdapter {
    protected abstract DatabaseNamespace namespace(String selected);
    protected abstract void enrichColumns(Connection conn, ConnectionConfig config, String database, String schema, String table, List<ColumnMetadata> columns) throws Exception;
    public String currentSchema(Connection conn) throws java.sql.SQLException { return conn.getSchema(); }
    public List<TableMetadata> getTables(Connection conn, ConnectionConfig config, String database) throws Exception {
        List<TableMetadata> tables = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();

        String catalog = namespace(database).catalog();
        String schema = resolveSchema(meta, namespace(database).schema());

        try (ResultSet rs = meta.getTables(catalog, literalPattern(meta, schema), "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                TableMetadata tm = new TableMetadata(catalog, schema, tableName, tableType, blankToNull(rs.getString("REMARKS")));
                tables.add(tm);
            }
        }

        tables.sort(Comparator.comparing(TableMetadata::getName));
        return tables;
    }

    public List<ColumnMetadata> getColumns(Connection conn, ConnectionConfig config, String database, String tableName) throws Exception {
        List<ColumnMetadata> columns = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();

        String catalog = namespace(database).catalog();
        String schema = resolveSchema(meta, namespace(database).schema());
        String lookupTable = schema == null ? tableName : resolveTable(meta, schema, tableName);
        Set<String> pkNames = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, lookupTable)) {
            while (rs.next()) pkNames.add(rs.getString("COLUMN_NAME"));
        }
        Map<String, String> comments = new HashMap<>();
        fetchColumnsInto(meta, catalog, schema, lookupTable, pkNames, columns, comments);
        enrichColumns(conn, config, database, schema, lookupTable, columns);
        // The enrichers rebuild columns from the basics, so comments and relationships are applied last.
        Map<String, ColumnReference> foreignKeys = new HashMap<>();
        boolean namespaceIsCatalog = namespace("probe").catalog() != null;
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, lookupTable)) {
            if (rs != null) while (rs.next()) {
                String target = namespaceIsCatalog ? rs.getString("PKTABLE_CAT") : rs.getString("PKTABLE_SCHEM");
                foreignKeys.putIfAbsent(rs.getString("FKCOLUMN_NAME"),
                        new ColumnReference(target, rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")));
            }
        } catch (java.sql.SQLException unavailable) {
            // Foreign-key badges are optional when a driver cannot provide relationship metadata.
        }
        columns.replaceAll(column -> column.withForeignKey(foreignKeys.containsKey(column.getName()))
                .withReference(foreignKeys.get(column.getName())).withComment(comments.get(column.getName())));

        return columns;
    }

    private void fetchColumnsInto(DatabaseMetaData meta, String catalog, String schema, String tablePattern,
                                  Set<String> pkNames, List<ColumnMetadata> columns, Map<String, String> comments) throws java.sql.SQLException {
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
                String remarks = blankToNull(rs.getString("REMARKS"));
                if (remarks != null) comments.put(colName, remarks);
            }
        }
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String literalPattern(DatabaseMetaData meta, String value) throws java.sql.SQLException {
        if (value == null) return null;
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
    private static String resolveSchema(DatabaseMetaData meta, String requested) throws java.sql.SQLException {
        if (requested == null) return null;
        String folded = meta.storesLowerCaseIdentifiers() ? requested.toLowerCase(Locale.ROOT) : requested.toUpperCase(Locale.ROOT), fallback = null;
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
        String folded = meta.storesLowerCaseIdentifiers() ? requested.toLowerCase(Locale.ROOT) : requested.toUpperCase(Locale.ROOT), fallback = null;
        try (ResultSet rs = meta.getTables(null, literalPattern(meta,schema), "%", null)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (requested.equals(name)) return name;
                if (folded.equals(name)) fallback = name;
            }
        }
        return fallback == null ? requested : fallback;
    }
    protected void enrichTemporalPrecision(Connection conn, String schema, String table, List<ColumnMetadata> columns) throws Exception {
        if (columns.stream().noneMatch(c -> c.getDataType() == java.sql.Types.TIME || c.getDataType() == java.sql.Types.TIMESTAMP || c.getDataType() == java.sql.Types.TIME_WITH_TIMEZONE || c.getDataType() == java.sql.Types.TIMESTAMP_WITH_TIMEZONE)) return;
        Map<String,Integer> precision = new HashMap<>();
        try (var statement = conn.prepareStatement("SELECT COLUMN_NAME,DATETIME_PRECISION FROM information_schema.columns WHERE table_schema=? AND table_name=? AND DATETIME_PRECISION IS NOT NULL")) {
            statement.setString(1, schema); statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) { while (result.next()) precision.put(result.getString(1), result.getInt(2)); }
        }
        columns.replaceAll(c -> precision.containsKey(c.getName()) ? new ColumnMetadata(c.getName(), c.getTypeName(), c.getDataType(), c.getColumnSize(), precision.get(c.getName()), c.isNullable(), c.isPrimaryKey(), c.isAutoIncrement(), c.getDefaultValue()) : c);
    }
}
