package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

import com.segfault03.ideadb.service.MetadataService;

final class MySqlDialect extends AbstractSqlDialect {
    protected String identifierQuote() { return "`"; }
    public String defaultInsert(String target) { return "INSERT INTO " + target + " () VALUES ()"; }
    protected String tableSuffix() { return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"; }
    protected String literalDefault(String raw) {
        if (raw.indexOf('\\') >= 0) return "(CONVERT(X'" + java.util.HexFormat.of().formatHex(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "' USING utf8mb4))";
        return super.literalDefault(raw);
    }
    public void createDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        String sql = "CREATE DATABASE " + quoteIdentifier(config,dbName) + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;";

        executeSql(conn, sql);
    }

    public void dropDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        String sql = "DROP DATABASE " + quoteIdentifier(config,dbName) + ";";

        executeSql(conn, sql);
    }

    public void dropTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        String sql = "DROP TABLE " + formatTable(config,dbName,tableName) + ";";
        executeSql(conn, sql);
    }

    public void truncateTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        String sql = "TRUNCATE TABLE " + formatTable(config,dbName,tableName) + ";";

        executeSql(conn, sql);
    }

    public void alterTableAddColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, ColumnDefinition col) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(formatTable(config,dbName,tableName)).append(" ADD COLUMN ");
        appendColumn(sb, col);
        sb.append(";");

        executeSql(conn, sb.toString());
    }

    public void alterTableRenameColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       String oldColName, String newColName) throws Exception {
        String sql;
        if (conn.getMetaData().getDatabaseMajorVersion() < 8) {
            String definition = mysqlColumnDefinition(conn, config, dbName, tableName, oldColName);
            String oldQuoted = quoteIdentifier(config, oldColName);
            sql = "ALTER TABLE " + formatTable(config, dbName, tableName) + " CHANGE COLUMN " + oldQuoted + " "
                    + quoteIdentifier(config, newColName) + definition.substring(oldQuoted.length()) + ";";
        } else {
            sql = "ALTER TABLE " + formatTable(config,dbName,tableName) + " RENAME COLUMN "
                    + quoteIdentifier(config,oldColName) + " TO " + quoteIdentifier(config,newColName) + ";";
        }
        executeSql(conn, sql);
    }

    public void alterTableModifyColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       ColumnDefinition col) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(formatTable(config,dbName,tableName)).append(" MODIFY COLUMN ");
        appendModifiedMysqlColumn(conn, config, dbName, tableName, sb, col);
        sb.append(";");

        executeSql(conn, sb.toString());
    }

    public TableMetadata alterTableRename(Connection conn, ConnectionConfig config, String dbName, String oldName, String newName) throws Exception {
        String sql = "RENAME TABLE " + formatTable(config,dbName,oldName) + " TO " + formatTable(config,dbName,newName) + ";";
        executeSql(conn, sql);
        TableMetadata renamed=MetadataService.getInstance().getTables(conn,config,dbName).stream()
                .filter(table -> table.getName().equalsIgnoreCase(newName)).findFirst().orElseThrow(() -> new java.sql.SQLException("Renamed table metadata unavailable"));
        renamed.setColumns(MetadataService.getInstance().getColumns(conn,config,dbName,renamed.getName()));
        return renamed;
    }

    public String getCreateTableStatement(Connection conn, ConnectionConfig config, String dbName, TableMetadata tableMetadata) throws Exception {
            try (Statement statement=conn.createStatement(); ResultSet result=statement.executeQuery("SHOW CREATE TABLE " + formatTable(config,dbName,tableMetadata.getName()))) {
                if(result.next()) return result.getString(2) + ";";
                throw new java.sql.SQLException("CREATE statement unavailable");
            }
    }

    private void appendModifiedMysqlColumn(Connection conn, ConnectionConfig config, String db, String table,
                                          StringBuilder sql, ColumnDefinition requested) throws Exception {
        ColumnMetadata original = MetadataService.getInstance().getColumns(conn, config, db, table).stream()
                .filter(column -> column.getName().equalsIgnoreCase(requested.getName())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Column no longer exists: " + requested.getName()));
        String name = quoteIdentifier(config, original.getName());
        String definition = mysqlColumnDefinition(conn, config, db, table, original.getName());
        int typeStart = name.length() + 1;
        int typeEnd = sqlTokenEnd(definition, typeStart);
        String oldType = definition.substring(typeStart, typeEnd);
        String suffix = definition.substring(typeEnd);
        StringBuilder typeAttributes = new StringBuilder();
        java.util.regex.Pattern attributes = java.util.regex.Pattern.compile("(?i)^\\s+(UNSIGNED|ZEROFILL|BINARY|CHARACTER SET\\s+\\S+|COLLATE\\s+\\S+)");
        java.util.regex.Matcher typeAttribute;
        while ((typeAttribute = attributes.matcher(suffix)).find()) {
            typeAttributes.append(typeAttribute.group());
            suffix = suffix.substring(typeAttribute.end());
        }
        if (suffix.contains("GENERATED ALWAYS")) throw new IllegalArgumentException("Generated column definitions must be changed in the SQL console");
        String type = requested.getType();
        if (typeAttributes.toString().toUpperCase(java.util.Locale.ROOT).contains("UNSIGNED") && type.toUpperCase(java.util.Locale.ROOT).endsWith(" UNSIGNED")) {
            type = type.substring(0, type.length() - 9);
        }
        if (!type.contains("(")) {
            if (type.toUpperCase(java.util.Locale.ROOT).startsWith("DECIMAL") || type.toUpperCase(java.util.Locale.ROOT).startsWith("NUMERIC")) {
                int precision = requested.getSize() > 0 ? requested.getSize() : original.getColumnSize();
                int scale = requested.getDecimalDigits() >= 0 ? requested.getDecimalDigits() : original.getDecimalDigits();
                type = type.replaceFirst("(?i)^(DECIMAL|NUMERIC)", "$1(" + precision + "," + scale + ")");
            } else if (requested.getSize() > 0 && needsSize(type)) type += "(" + requested.getSize() + ")";
        }
        if (requested.getType().equalsIgnoreCase(original.getTypeName()) && !type.contains("(") && oldType.contains("(")) type = oldType;
        if (requested.isNullable() != original.isNullable()) {
            int boundary = suffix.length();
            for (String attribute : new String[]{" DEFAULT ", " AUTO_INCREMENT", " COMMENT ", " ON UPDATE "}) {
                int position = suffix.indexOf(attribute);
                if (position >= 0) boundary = Math.min(boundary, position);
            }
            suffix = suffix.substring(0, boundary).replace(" NOT NULL", "").replace(" NULL", "") + suffix.substring(boundary);
            suffix = (requested.isNullable() ? " NULL" : " NOT NULL") + suffix;
        }
        if (!requested.isNullable() && original.getDefaultValue()==null && requested.getDefaultValue().isBlank()) suffix = suffix.replace(" DEFAULT NULL", "");
        String currentDefault = original.getDefaultValue() == null ? "" : original.getDefaultValue();
        if (!requested.getDefaultValue().equals(currentDefault)) {
            int start = suffix.indexOf(" DEFAULT ");
            if (start >= 0) {
                int end = sqlTokenEnd(suffix, start + 9);
                suffix = suffix.substring(0, start) + suffix.substring(end);
            }
            if (!requested.getDefaultValue().isBlank()) suffix = " DEFAULT " + formatDefault(requested) + suffix;
        }
        // AUTO_INCREMENT, collation, ON UPDATE and comments remain in the original suffix.
        sql.append(name).append(' ').append(type).append(typeAttributes).append(suffix);
    }

    private String mysqlColumnDefinition(Connection conn, ConnectionConfig config, String db, String table, String column) throws Exception {
        String create = getCreateTableStatement(conn, config, db, new TableMetadata(db, null, table, "TABLE"));
        String name = quoteIdentifier(config, column);
        String definition = create.lines().map(String::trim).filter(line -> line.startsWith(name + " ")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Cannot preserve original column definition"));
        return definition.endsWith(",") ? definition.substring(0, definition.length() - 1) : definition;
    }

    private static int sqlTokenEnd(String sql, int start) {
        int depth = 0; char quote = 0;
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i++;
                    else quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && Character.isWhitespace(c)) return i;
        }
        return sql.length();
    }

    protected void executeSql(Connection conn, String sql) throws Exception {
        // Expression defaults arrived in MySQL 8.0.13; older servers need a literal
        // escaped according to this connection's actual SQL mode.
        if (sql.contains("(CONVERT(X'") && conn.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")) {
            boolean modern = conn.getMetaData().getDatabaseMajorVersion() >= 8;
            if (modern && conn.getMetaData().getDatabaseMajorVersion() == 8 && conn.getMetaData().getDatabaseMinorVersion() == 0) {
                String[] parts = conn.getMetaData().getDatabaseProductVersion().split("[.-]");
                modern = parts.length > 2 && Integer.parseInt(parts[2]) >= 13;
            }
            if (!modern) {
                boolean backslashEscapes;
                try (Statement mode = conn.createStatement(); ResultSet result = mode.executeQuery("SELECT @@SESSION.sql_mode")) {
                    result.next(); backslashEscapes = !result.getString(1).contains("NO_BACKSLASH_ESCAPES");
                }
                var pattern = java.util.regex.Pattern.compile("\\(CONVERT\\(X'([0-9a-fA-F]+)' USING utf8mb4\\)\\)");
                var match = pattern.matcher(sql); StringBuffer replaced = new StringBuffer();
                while (match.find()) {
                    String value = new String(java.util.HexFormat.of().parseHex(match.group(1)), java.nio.charset.StandardCharsets.UTF_8);
                    if (backslashEscapes) value = value.replace("\\", "\\\\");
                    match.appendReplacement(replaced, java.util.regex.Matcher.quoteReplacement("'" + value.replace("'", "''") + "'"));
                }
                match.appendTail(replaced); sql = replaced.toString();
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(60);
            stmt.execute(sql);
        }
    }

    protected void appendColumn(StringBuilder sb, ColumnDefinition col) {
        sb.append(quoteIdentifier(col.getName())).append(" ").append(col.getType());
        if (col.getSize() > 0 && needsSize(col.getType())) {
            sb.append("(").append(col.getSize()).append(")");
        }
        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (col.isAutoIncrement()) {
            sb.append(" AUTO_INCREMENT");
        }
        if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
            sb.append(" DEFAULT ").append(formatDefault(col));
        }
    }

    protected void appendMetadataColumn(StringBuilder sb, ColumnMetadata col) {
                sb.append(quoteIdentifier(col.getName())).append(" ").append(ddlType(col));
                if (!col.isNullable()) {
                    sb.append(" NOT NULL");
                }
                if (col.isAutoIncrement()) {
                    sb.append(" AUTO_INCREMENT");
                }
                if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
                    sb.append(" DEFAULT ").append(col.getDefaultValue().trim());
                }
    }
}
