package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

import com.segfault03.ideadb.service.MetadataService;

final class HsqlDialect extends AbstractSqlDialect {
    protected String identifierQuote() { return "\""; }
    public String defaultInsert(String target) { return "INSERT INTO " + target + " DEFAULT VALUES"; }
    public void createDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        String sql = "CREATE SCHEMA " + quoteIdentifier(config,dbName) + " AUTHORIZATION DBA;";

        executeSql(conn, sql);
    }

    public void dropDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        String sql = "DROP SCHEMA " + quoteIdentifier(config,dbName) + " CASCADE;";

        executeSql(conn, sql);
    }

    public void dropTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        String sql = "DROP TABLE " + formatTable(config, dbName, tableName) + " RESTRICT;";
        executeSql(conn, sql);
    }

    public void truncateTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        String sql = "TRUNCATE TABLE " + formatTable(config, dbName, tableName) + " AND COMMIT;";

        executeSql(conn, sql);
    }

    public void alterTableAddColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, ColumnDefinition col) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(formatTable(config, dbName, tableName)).append(" ADD COLUMN ");
        appendColumn(sb, col);
        sb.append(";");

        executeSql(conn, sb.toString());
    }

    public void alterTableRenameColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       String oldColName, String newColName) throws Exception {
        String sql = "ALTER TABLE " + formatTable(config,dbName,tableName) + " ALTER COLUMN "
                + quoteIdentifier(config,oldColName) + " RENAME TO " + quoteIdentifier(config,newColName) + ";";
        executeSql(conn, sql);
    }

    public void alterTableModifyColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       ColumnDefinition col) throws Exception {
        modifyHsqlColumn(conn,config,dbName,tableName,col);

    }

    public TableMetadata alterTableRename(Connection conn, ConnectionConfig config, String dbName, String oldName, String newName) throws Exception {
        String sql = "ALTER TABLE " + formatTable(config,dbName,oldName) + " RENAME TO " + quoteIdentifier(config,newName) + ";";
        executeSql(conn, sql);
        TableMetadata renamed=MetadataService.getInstance().getTables(conn,config,dbName).stream()
                .filter(table -> table.getName().equals(newName)).findFirst().orElseThrow(() -> new java.sql.SQLException("Renamed table metadata unavailable"));
        renamed.setColumns(MetadataService.getInstance().getColumns(conn,config,dbName,renamed.getName()));
        return renamed;
    }

    public String getCreateTableStatement(Connection conn, ConnectionConfig config, String dbName, TableMetadata tableMetadata) throws Exception {
        String schema=dbName==null || dbName.isBlank() ? DatabaseProviders.get(config.getType()).metadata().currentSchema(conn) : dbName;
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

    private void modifyHsqlColumn(Connection conn, ConnectionConfig config, String db, String table, ColumnDefinition requested) throws Exception {
        ColumnMetadata original=MetadataService.getInstance().getColumns(conn,config,db,table).stream().filter(c -> c.getName().equals(requested.getName())).findFirst().orElseThrow();
        String target=formatTable(config,db,table), column=quoteIdentifier(config,requested.getName());
        if (!requested.isNullable()) {
            try(Statement statement=conn.createStatement(); ResultSet result=statement.executeQuery("SELECT 1 FROM " + target + " WHERE " + column + " IS NULL LIMIT 1")) {
                if(result.next()) throw new java.sql.SQLException("Cannot set NOT NULL while existing values are NULL");
            }
        }
        if (requested.isNullable() && original.isPrimaryKey()) throw new java.sql.SQLException("A primary-key column cannot allow NULL");
        String defaultValue=requested.getDefaultValue().isBlank() ? null : formatDefault(requested);
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

    private static String scriptIdentifier(String name) {
        String quoted="\"" + name.replace("\"","\"\"") + "\"";
        return name.matches("[A-Z_][A-Z_0-9]*") ? "(?:" + java.util.regex.Pattern.quote(name) + "|" + java.util.regex.Pattern.quote(quoted) + ")" : java.util.regex.Pattern.quote(quoted);
    }

    protected void appendColumn(StringBuilder sb, ColumnDefinition col) {
        sb.append(quoteIdentifier(col.getName())).append(" ");
        if (col.isAutoIncrement()) {
            sb.append(col.getType()).append(" GENERATED BY DEFAULT AS IDENTITY");
        } else {
            sb.append(col.getType());
            if (col.getSize() > 0 && needsSize(col.getType())) {
                sb.append("(").append(col.getSize()).append(")");
            }
            if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
                sb.append(" DEFAULT ").append(formatDefault(col));
            }
            if (!col.isNullable()) sb.append(" NOT NULL");
        }
    }

    protected void appendMetadataColumn(StringBuilder sb, ColumnMetadata col) {
                sb.append(quoteIdentifier(col.getName())).append(" ");
                if (col.isAutoIncrement()) {
                    sb.append(col.getTypeName()).append(" GENERATED BY DEFAULT AS IDENTITY");
                } else {
                    sb.append(ddlType(col));
                    if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) sb.append(" DEFAULT ").append(col.getDefaultValue().trim());
                    if (!col.isNullable()) sb.append(" NOT NULL");
                }
    }
}
