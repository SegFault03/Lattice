package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ColumnDefinition;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DdlService {
    private static final DdlService INSTANCE = new DdlService();

    private DdlService() {
    }

    public static DdlService getInstance() {
        return INSTANCE;
    }

    public void createDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        String sql;
        if (config.getType() == DatabaseType.MYSQL) {
            sql = "CREATE DATABASE " + quoteIdentifier(config,dbName) + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;";
        } else {
            sql = "CREATE SCHEMA " + quoteIdentifier(config,dbName) + " AUTHORIZATION DBA;";
        }
        executeSql(conn, sql);
    }

    public void dropDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        String sql;
        if (config.getType() == DatabaseType.MYSQL) {
            sql = "DROP DATABASE " + quoteIdentifier(config,dbName) + ";";
        } else {
            sql = "DROP SCHEMA " + quoteIdentifier(config,dbName) + " CASCADE;";
        }
        executeSql(conn, sql);
    }

    public void createTable(Connection conn, ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) throws Exception {
        String sql = buildCreateTableSql(config, dbName, tableName, columns);
        executeSql(conn, sql);
    }

    public void dropTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        String sql = (config.getType() == DatabaseType.MYSQL)
                ? "DROP TABLE " + formatTable(config,dbName,tableName) + ";"
                : "DROP TABLE " + formatTable(config, dbName, tableName) + " RESTRICT;";
        executeSql(conn, sql);
    }

    public void truncateTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        String sql;
        if (config.getType() == DatabaseType.MYSQL) {
            sql = "TRUNCATE TABLE " + formatTable(config,dbName,tableName) + ";";
        } else {
            sql = "TRUNCATE TABLE " + formatTable(config, dbName, tableName) + " AND COMMIT;";
        }
        executeSql(conn, sql);
    }

    public void alterTableAddColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, ColumnDefinition col) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (config.getType() == DatabaseType.MYSQL) {
            sb.append("ALTER TABLE ").append(formatTable(config,dbName,tableName)).append(" ADD COLUMN ");
            appendMysqlColumnDef(sb, col);
            sb.append(";");
        } else {
            sb.append("ALTER TABLE ").append(formatTable(config, dbName, tableName)).append(" ADD COLUMN ");
            appendHsqlColumnDef(sb, col);
            sb.append(";");
        }
        executeSql(conn, sb.toString());
    }

    public void alterTableDropColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, String colName) throws Exception {
        String sql = "ALTER TABLE " + formatTable(config,dbName,tableName) + " DROP COLUMN " + quoteIdentifier(config,colName) + ";";
        executeSql(conn, sql);
    }

    public void alterTableRenameColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       String oldColName, String newColName) throws Exception {
        String sql;
        if (config.getType() == DatabaseType.MYSQL && conn.getMetaData().getDatabaseMajorVersion() < 8) {
            String definition = mysqlColumnDefinition(conn, config, dbName, tableName, oldColName);
            String oldQuoted = quoteIdentifier(config, oldColName);
            sql = "ALTER TABLE " + formatTable(config, dbName, tableName) + " CHANGE COLUMN " + oldQuoted + " "
                    + quoteIdentifier(config, newColName) + definition.substring(oldQuoted.length()) + ";";
        } else {
            sql = "ALTER TABLE " + formatTable(config,dbName,tableName) + (config.getType()==DatabaseType.MYSQL ? " RENAME COLUMN " : " ALTER COLUMN ")
                    + quoteIdentifier(config,oldColName) + (config.getType()==DatabaseType.MYSQL ? " TO " : " RENAME TO ") + quoteIdentifier(config,newColName) + ";";
        }
        executeSql(conn, sql);
    }

    public void alterTableModifyColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       ColumnDefinition col) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (config.getType() == DatabaseType.MYSQL) {
            sb.append("ALTER TABLE ").append(formatTable(config,dbName,tableName)).append(" MODIFY COLUMN ");
            appendModifiedMysqlColumn(conn, config, dbName, tableName, sb, col);
            sb.append(";");
        } else {
            modifyHsqlColumn(conn,config,dbName,tableName,col);
            return;
        }
        executeSql(conn, sb.toString());
    }

    private void modifyHsqlColumn(Connection conn, ConnectionConfig config, String db, String table, ColumnDefinition requested) throws Exception {
        ColumnMetadata original=MetadataService.getInstance().getColumns(conn,config,db,table).stream().filter(c -> c.getName().equals(requested.getName())).findFirst().orElseThrow();
        String target=formatTable(config,db,table), column=quoteIdentifier(config,requested.getName());
        if (!requested.isNullable()) {
            try(Statement statement=conn.createStatement(); ResultSet result=statement.executeQuery("SELECT 1 FROM " + target + " WHERE " + column + " IS NULL LIMIT 1")) {
                if(result.next()) throw new java.sql.SQLException("Cannot set NOT NULL while existing values are NULL");
            }
        }
        if (requested.isNullable() && original.isPrimaryKey()) throw new java.sql.SQLException("A primary-key column cannot allow NULL");
        String defaultValue=requested.getDefaultValue().isBlank() ? null : formatDefault(DatabaseType.HSQLDB,requested);
        if (original.isAutoIncrement() && defaultValue!=null) throw new java.sql.SQLException("An identity column cannot have a default expression");
        String type=requested.getType();
        if (requested.getSize()>0 && needsSize(type) && !type.contains("(")) type += "(" + requested.getSize() + ")";
        if ((type.equalsIgnoreCase("DECIMAL") || type.equalsIgnoreCase("NUMERIC")) && requested.getSize()>0) type += "(" + requested.getSize() + "," + (requested.getDecimalDigits()<0 ? original.getDecimalDigits() : requested.getDecimalDigits()) + ")";
        // Validate the target type and default without touching the persistent table.
        String validation="SESSION." + quoteIdentifier(config,"LATTICE_VALIDATE_" + java.util.UUID.randomUUID().toString().replace("-",""));
        executeSql(conn,"DECLARE LOCAL TEMPORARY TABLE " + validation + " (VALUE " + type + (defaultValue==null ? "" : " DEFAULT " + defaultValue) + ") ON COMMIT PRESERVE ROWS");
        int expectedType;
        try {
            if(defaultValue!=null) executeSql(conn,"INSERT INTO " + validation + " DEFAULT VALUES");
            try(Statement statement=conn.createStatement(); ResultSet result=statement.executeQuery("SELECT * FROM " + validation)) { expectedType=result.getMetaData().getColumnType(1); }
        }
        finally { executeSql(conn,"DROP TABLE " + validation); }
        String prefix="ALTER TABLE " + target + " ALTER COLUMN " + column;
        try {
            executeSql(conn,prefix + " SET DATA TYPE " + type);
            if(!original.isAutoIncrement()) executeSql(conn,prefix + (defaultValue==null ? " DROP DEFAULT" : " SET DEFAULT " + defaultValue));
            if(requested.isNullable()!=original.isNullable()) executeSql(conn,prefix + (requested.isNullable() ? " SET NULL" : " SET NOT NULL"));
            ColumnMetadata actual=MetadataService.getInstance().getColumns(conn,config,db,table).stream().filter(c -> c.getName().equals(requested.getName())).findFirst().orElseThrow();
            boolean defaultMatches = original.isAutoIncrement() || java.util.Objects.equals(defaultValue,actual.getDefaultValue())
                    || (defaultValue!=null && !defaultValue.startsWith("'") && defaultValue.equalsIgnoreCase(actual.getDefaultValue()));
            if(actual.getDataType()!=expectedType || actual.isNullable()!=requested.isNullable() || !defaultMatches) throw new java.sql.SQLException("Requested column definition was not applied");
        } catch(Exception failure) {
            throw new java.sql.SQLException("Column alteration failed: " + failure.getMessage() + ". HSQLDB uses separate DDL statements; reload metadata before retrying.",failure);
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
            if (!requested.getDefaultValue().isBlank()) suffix = " DEFAULT " + formatDefault(DatabaseType.MYSQL,requested) + suffix;
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

    private static String formatDefault(DatabaseType dialect, ColumnDefinition column) {
        String raw=column.getDefaultValue(), value=raw.trim();
        ColumnDefinition.DefaultKind kind=column.getDefaultKind();
        if (kind==ColumnDefinition.DefaultKind.AUTO && value.startsWith("SQL:")) { kind=ColumnDefinition.DefaultKind.EXPRESSION; value=value.substring(4).trim(); }
        if (kind==ColumnDefinition.DefaultKind.AUTO && value.startsWith("TEXT:")) { kind=ColumnDefinition.DefaultKind.LITERAL; raw=value.substring(5); value=raw; }
        if (kind==ColumnDefinition.DefaultKind.EXPRESSION) {
            if (value.isEmpty() || value.contains(";") || value.contains("--") || value.contains("/*")) throw new IllegalArgumentException("Enter one SQL default expression");
            return value;
        }
        if (kind==ColumnDefinition.DefaultKind.AUTO) {
            String type=column.getType().toUpperCase(java.util.Locale.ROOT);
            if ((type.contains("TIME") || type.equals("DATE")) && value.matches("(?i)CURRENT_(TIMESTAMP|TIME|DATE)(\\(\\d*\\))?")) return value;
            if (value.equalsIgnoreCase("NULL") || value.equalsIgnoreCase("TRUE") || value.equalsIgnoreCase("FALSE") || value.matches("[+-]?\\d+(\\.\\d+)?")) return value;
            if (value.startsWith("'") && value.endsWith("'")) raw=value.substring(1,value.length()-1).replace("''","'");
        }
        // Hex expressions preserve backslashes under either MySQL SQL mode.
        if (dialect==DatabaseType.MYSQL && raw.indexOf('\\')>=0) {
            return "(CONVERT(X'" + java.util.HexFormat.of().formatHex(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "' USING utf8mb4))";
        }
        return "'" + raw.replace("'", "''") + "'";
    }

    public TableMetadata alterTableRename(Connection conn, ConnectionConfig config, String dbName, String oldName, String newName) throws Exception {
        String sql = config.getType()==DatabaseType.MYSQL
                ? "RENAME TABLE " + formatTable(config,dbName,oldName) + " TO " + formatTable(config,dbName,newName) + ";"
                : "ALTER TABLE " + formatTable(config,dbName,oldName) + " RENAME TO " + quoteIdentifier(config,newName) + ";";
        executeSql(conn, sql);
        TableMetadata renamed=MetadataService.getInstance().getTables(conn,config,dbName).stream()
                .filter(table -> config.getType()==DatabaseType.MYSQL ? table.getName().equalsIgnoreCase(newName) : table.getName().equals(newName)).findFirst().orElseThrow(() -> new java.sql.SQLException("Renamed table metadata unavailable"));
        renamed.setColumns(MetadataService.getInstance().getColumns(conn,config,dbName,renamed.getName()));
        return renamed;
    }

    public String buildCreateTableSql(ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) {
        StringBuilder sb = new StringBuilder();
        boolean isMysql = config.getType() == DatabaseType.MYSQL;

        sb.append("CREATE TABLE ").append(formatTable(config,dbName,tableName)).append(" (\n");

        List<String> pkCols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition col = columns.get(i);
            sb.append("  ");
            if (isMysql) {
                appendMysqlColumnDef(sb, col);
            } else {
                appendHsqlColumnDef(sb, col);
            }

            if (col.isPrimaryKey()) {
                pkCols.add(col.getName());
            }

            if (i < columns.size() - 1 || !pkCols.isEmpty()) {
                sb.append(",\n");
            }
        }

        if (!pkCols.isEmpty()) {
            sb.append("  PRIMARY KEY (");
            for (int i = 0; i < pkCols.size(); i++) {
                sb.append(quoteIdentifier(config,pkCols.get(i)));
                if (i < pkCols.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(")\n");
        }

        sb.append(")");
        if (isMysql) {
            sb.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
        } else {
            sb.append(";");
        }

        return sb.toString();
    }

    public String buildSelectSql(ConnectionConfig config, String dbName, String tableName, List<String> columns) {
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("SELECT ");
        if (columns == null || columns.isEmpty()) {
            sb.append("*");
        } else {
            for (int i = 0; i < columns.size(); i++) {
                sb.append(quoteIdentifier(config,columns.get(i)));
                if (i < columns.size() - 1) sb.append(", ");
            }
        }
        sb.append(" FROM ");
        sb.append(formatTable(config, dbName, tableName)).append(" LIMIT 100;");
        return sb.toString();
    }

    public String buildInsertSql(ConnectionConfig config, String dbName, String tableName, List<String> columns) {
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(formatTable(config, dbName, tableName)).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            sb.append(quoteIdentifier(config,columns.get(i)));
            if (i < columns.size() - 1) sb.append(", ");
        }
        sb.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            sb.append("?");
            if (i < columns.size() - 1) sb.append(", ");
        }
        sb.append(");");
        return sb.toString();
    }

    public String buildUpdateSql(ConnectionConfig config, String dbName, String tableName, List<String> columns, List<String> pkColumns) {
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("UPDATE ");
        sb.append(formatTable(config, dbName, tableName)).append(" SET ");

        for (int i = 0; i < columns.size(); i++) {
            sb.append(quoteIdentifier(config,columns.get(i))).append(" = ?");
            if (i < columns.size() - 1) sb.append(", ");
        }

        if (pkColumns != null && !pkColumns.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < pkColumns.size(); i++) {
                sb.append(quoteIdentifier(config,pkColumns.get(i))).append(" = ?");
                if (i < pkColumns.size() - 1) sb.append(" AND ");
            }
        }
        sb.append(";");
        return sb.toString();
    }

    public String buildDeleteSql(ConnectionConfig config, String dbName, String tableName, List<String> pkColumns) {
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("DELETE FROM ");
        sb.append(formatTable(config, dbName, tableName));

        if (pkColumns != null && !pkColumns.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < pkColumns.size(); i++) {
                sb.append(quoteIdentifier(config,pkColumns.get(i))).append(" = ?");
                if (i < pkColumns.size() - 1) sb.append(" AND ");
            }
        }
        sb.append(";");
        return sb.toString();
    }

    public static String quoteIdentifier(ConnectionConfig config, String name) { return quoteIdentifier(config.getType(),name); }
    public static String quoteIdentifier(DatabaseType type, String name) {
        if (name==null || name.isEmpty()) throw new IllegalArgumentException("Identifier cannot be empty");
        String quote=type==DatabaseType.MYSQL ? "`" : "\"";
        return quote + name.replace(quote,quote+quote) + quote;
    }
    public static String formatTable(ConnectionConfig config, String dbName, String tableName) {
        return (dbName==null || dbName.isEmpty() ? "" : quoteIdentifier(config,dbName)+".") + quoteIdentifier(config,tableName);
    }

    private void appendMysqlColumnDef(StringBuilder sb, ColumnDefinition col) {
        sb.append(quoteIdentifier(DatabaseType.MYSQL,col.getName())).append(" ").append(col.getType());
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
            sb.append(" DEFAULT ").append(formatDefault(DatabaseType.MYSQL,col));
        }
    }

    private void appendHsqlColumnDef(StringBuilder sb, ColumnDefinition col) {
        sb.append(quoteIdentifier(DatabaseType.HSQLDB,col.getName())).append(" ");
        if (col.isAutoIncrement()) {
            sb.append(col.getType()).append(" GENERATED BY DEFAULT AS IDENTITY");
        } else {
            sb.append(col.getType());
            if (col.getSize() > 0 && needsSize(col.getType())) {
                sb.append("(").append(col.getSize()).append(")");
            }
            if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
                sb.append(" DEFAULT ").append(formatDefault(DatabaseType.HSQLDB,col));
            }
            if (!col.isNullable()) sb.append(" NOT NULL");
        }
    }

    private boolean needsSize(String type) {
        String t = type.toUpperCase(java.util.Locale.ROOT);
        return t.contains("CHAR") || t.contains("VARCHAR") || t.contains("VARBINARY") || t.contains("BINARY");
    }

    private void executeSql(Connection conn, String sql) throws Exception {
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

    public String getCreateTableStatement(Connection conn, ConnectionConfig config, String dbName, TableMetadata tableMetadata) throws Exception {
        if (config.getType()==DatabaseType.MYSQL) {
            try (Statement statement=conn.createStatement(); ResultSet result=statement.executeQuery("SHOW CREATE TABLE " + formatTable(config,dbName,tableMetadata.getName()))) {
                if(result.next()) return result.getString(2) + ";";
                throw new java.sql.SQLException("CREATE statement unavailable");
            }
        }
        String schema=dbName==null || dbName.isBlank() ? JdbcSchema.current(conn) : dbName;
        String target=scriptIdentifier(schema) + "\\." + scriptIdentifier(tableMetadata.getName());
        String anyIdentifier="(?:\"(?:[^\"]|\"\")*\"|[A-Z_][A-Z_0-9]*)";
        var create=java.util.regex.Pattern.compile("^CREATE (?:MEMORY |CACHED |TEXT )?TABLE " + target + "(?=[ (])");
        var alter=java.util.regex.Pattern.compile("^ALTER TABLE " + target + "(?= )");
        var index=java.util.regex.Pattern.compile("^CREATE (?:UNIQUE )?INDEX " + anyIdentifier + " ON " + target + "(?=[ (])");
        var trigger=java.util.regex.Pattern.compile("^CREATE TRIGGER " + anyIdentifier + " .*? ON " + target + "(?= )");
        List<String> statements=new ArrayList<>();
        try(Statement statement=conn.createStatement()) {
            // SCRIPT returns data, but 2.2's parser rejects executeQuery's RETURN_RESULT hint.
            if (!statement.execute("SCRIPT")) throw new java.sql.SQLException("SCRIPT returned no result set");
            try (ResultSet result = statement.getResultSet()) {
                while(result.next()) {
                    String sql=result.getString(1);
                    if(create.matcher(sql).find() || alter.matcher(sql).find() || index.matcher(sql).find() || trigger.matcher(sql).find()) statements.add(sql + ";");
                }
            }
        } catch(java.sql.SQLException failure) {
            TableMetadata fallback=new TableMetadata(null,schema,tableMetadata.getName(),tableMetadata.getType());
            fallback.setColumns(MetadataService.getInstance().getColumns(conn,config,schema,tableMetadata.getName()));
            return "-- Partial reconstruction: SCRIPT unavailable (" + failure.getMessage().replace("\n", " ") + ").\n" + generateCreateTableSql(config,schema,fallback);
        }
        if(statements.isEmpty()) throw new java.sql.SQLException("No CREATE statement found for " + tableMetadata.getName());
        return "-- Referenced tables, sequences and user-defined types must already exist.\n" + String.join("\n",statements);
    }
    private static String scriptIdentifier(String name) {
        String quoted="\"" + name.replace("\"","\"\"") + "\"";
        return name.matches("[A-Z_][A-Z_0-9]*") ? "(?:" + java.util.regex.Pattern.quote(name) + "|" + java.util.regex.Pattern.quote(quoted) + ")" : java.util.regex.Pattern.quote(quoted);
    }
    private static String ddlType(ColumnMetadata column) {
        String type=column.getTypeName();
        if(type.contains("(")) return type;
        return switch(column.getDataType()) {
            case java.sql.Types.CHAR,java.sql.Types.VARCHAR,java.sql.Types.NCHAR,java.sql.Types.NVARCHAR,java.sql.Types.BINARY,java.sql.Types.VARBINARY -> type + "(" + Math.max(1,column.getColumnSize()) + ")";
            case java.sql.Types.NUMERIC,java.sql.Types.DECIMAL -> type + "(" + column.getColumnSize() + "," + column.getDecimalDigits() + ")";
            default -> type;
        };
    }

    public String generateCreateTableSql(ConnectionConfig config, String dbName, TableMetadata tableMetadata) {
        StringBuilder sb = new StringBuilder();
        boolean isMysql = config.getType() == DatabaseType.MYSQL;

        sb.append("-- Partial reconstruction: only columns and primary keys; other constraints, indexes and identity state are omitted.\nCREATE TABLE ");
        sb.append(formatTable(config,dbName,tableMetadata.getName())).append(" (\n");

        List<ColumnMetadata> cols = tableMetadata.getColumns();
        List<String> pkCols = new ArrayList<>();

        for (int i = 0; i < cols.size(); i++) {
            ColumnMetadata col = cols.get(i);
            sb.append("  ");
            if (isMysql) {
                sb.append(quoteIdentifier(DatabaseType.MYSQL,col.getName())).append(" ").append(ddlType(col));
                if (!col.isNullable()) {
                    sb.append(" NOT NULL");
                }
                if (col.isAutoIncrement()) {
                    sb.append(" AUTO_INCREMENT");
                }
                if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
                    sb.append(" DEFAULT ").append(col.getDefaultValue().trim());
                }
            } else {
                sb.append(quoteIdentifier(DatabaseType.HSQLDB,col.getName())).append(" ");
                if (col.isAutoIncrement()) {
                    sb.append(col.getTypeName()).append(" GENERATED BY DEFAULT AS IDENTITY");
                } else {
                    sb.append(ddlType(col));
                    if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) sb.append(" DEFAULT ").append(col.getDefaultValue().trim());
                    if (!col.isNullable()) sb.append(" NOT NULL");
                }
            }

            if (col.isPrimaryKey()) {
                pkCols.add(col.getName());
            }

            if (i < cols.size() - 1 || !pkCols.isEmpty()) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }

        if (!pkCols.isEmpty()) {
            sb.append("  PRIMARY KEY (");
            for (int i = 0; i < pkCols.size(); i++) {
                sb.append(quoteIdentifier(config,pkCols.get(i)));
                if (i < pkCols.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(")\n");
        }

        sb.append(")");
        if (isMysql) {
            sb.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
        } else {
            sb.append(";");
        }

        return sb.toString();
    }
}
