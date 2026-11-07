package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Metadata-only connections reject every write, including accidental preview execution. */
class AlterSqlPreviewTest {
    @Test void mysqlCharacterAttributesDoNotFollowAConversionToNumeric() throws Exception {
        var config = new ConnectionConfig(DatabaseType.MYSQL, "preview");
        var requested = new ColumnDefinition("email", "BIGINT", 0, true, false, false, "");
        String sql = new MySqlDialect().buildModifyColumnSql(metadataOnly(false, 8), config, "shop", "customers", requested).getFirst();
        assertEquals("ALTER TABLE `shop`.`customers` MODIFY COLUMN `email` BIGINT COMMENT 'Keep me';", sql);
    }
    @Test void mysqlModificationPreservesCollationCommentAndTheExistingDefault() throws Exception {
        var config=new ConnectionConfig(DatabaseType.MYSQL,"preview");
        var requested=new ColumnDefinition("email","VARCHAR",255,true,false,false,"old");
        String sql=new MySqlDialect().buildModifyColumnSql(metadataOnly(false,8),config,"shop","customers",requested).getFirst();
        assertEquals("ALTER TABLE `shop`.`customers` MODIFY COLUMN `email` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'old' COMMENT 'Keep me';",sql);
    }
    @Test void mysqlRenamePreviewUsesTheServerVersionAndRetainsTheFullLegacyDefinition() throws Exception {
        var config=new ConnectionConfig(DatabaseType.MYSQL,"preview");var dialect=new MySqlDialect();
        String modern=dialect.buildRenameColumnSql(metadataOnly(false,8),config,"shop","customers","email","email_address");
        assertEquals("ALTER TABLE `shop`.`customers` RENAME COLUMN `email` TO `email_address`;",modern);
        String legacy=dialect.buildRenameColumnSql(metadataOnly(false,5),config,"shop","customers","email","email_address");
        assertTrue(legacy.startsWith("ALTER TABLE `shop`.`customers` CHANGE COLUMN `email` `email_address` varchar(180)"));
        assertTrue(legacy.endsWith("COLLATE utf8mb4_bin DEFAULT 'old' COMMENT 'Keep me';"));
    }
    @Test void legacyMysqlCombinedRenameAndModifyUsesTheUpdatedDefinition() throws Exception {
        var config = new ConnectionConfig(DatabaseType.MYSQL,"batch preview");
        var modified = new ColumnDefinition("email","VARCHAR",255,true,false,false,"old");
        String sql = new MySqlDialect().buildRenameColumnSql(metadataOnly(false,5),config,"shop","customers","email","email_address",modified);
        assertEquals("ALTER TABLE `shop`.`customers` CHANGE COLUMN `email` `email_address` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'old' COMMENT 'Keep me';",sql);
    }
    @Test void mysqlAdditionIncludesDecimalPrecisionAndScale() {
        var config = new ConnectionConfig(DatabaseType.MYSQL,"batch preview");
        var added = new ColumnDefinition("balance","DECIMAL",12,true,false,false,""); added.setDecimalDigits(3);
        assertEquals("ALTER TABLE `shop`.`customers` ADD COLUMN `balance` DECIMAL(12,3);",new MySqlDialect().buildAddColumnSql(config,"shop","customers",added));
    }
    @Test void postgresBatchRollsBackEarlierOperationsAndDoesNotReportThemAsCompleted() throws Exception {
        var config = new ConnectionConfig(DatabaseType.POSTGRESQL,"transactional batch");
        var delegate = metadataOnly(true,16);
        List<String> events = new ArrayList<>();
        boolean[] autoCommit = {true};
        Connection conn = (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a) -> {
            switch (m.getName()) {
                case "getAutoCommit": return autoCommit[0];
                case "setAutoCommit": autoCommit[0]=(boolean)a[0]; events.add("auto="+a[0]); return null;
                case "commit": events.add("commit"); return null;
                case "rollback": events.add("rollback"); return null;
                case "isClosed": return false;
                case "createStatement": return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{Statement.class},(sp,sm,sa) -> {
                    switch (sm.getName()) {
                        case "close", "setQueryTimeout": return null;
                        case "execute":
                            events.add((String)sa[0]);
                            if (((String)sa[0]).contains("\"bad\"")) throw new SQLException("Invalid default");
                            return false;
                        default: throw new AssertionError("Unexpected write method "+sm.getName());
                    }
                });
                default:
                    try { return m.invoke(delegate,a); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
            }
        });
        var baseline = com.segfault03.ideadb.service.MetadataService.getInstance().getColumns(conn,config,"public","customers");
        var first=TableAlteration.column(TableAlteration.Kind.ADD,null,"first","INT","",true,"");
        var bad=TableAlteration.column(TableAlteration.Kind.ADD,null,"bad","INT","",true,"");
        var later=TableAlteration.column(TableAlteration.Kind.ADD,null,"later","INT","",true,"");
        var batch = new TableAlterationBatch("customers",baseline,List.of(first,bad,later));
        var service = com.segfault03.ideadb.service.DdlService.getInstance();
        var sql = service.previewAlterBatch(conn,config,"public",batch); assertTrue(events.isEmpty());
        var failure = assertThrows(com.segfault03.ideadb.service.DdlService.BatchFailure.class,()->service.applyAlterBatch(conn,config,"public",batch,sql));
        assertTrue(failure.completed().isEmpty());
        assertEquals(List.of("auto=false",sql.get(0),sql.get(1),"rollback","auto=true"),events);
        assertTrue(autoCommit[0]);
    }
    @Test void postgresPreviewIncludesTheCastDefaultAndNullabilityStatementsInExecutionOrder() throws Exception {
        var config=new ConnectionConfig(DatabaseType.POSTGRESQL,"preview");
        var requested=new ColumnDefinition("email","VARCHAR",255,true,false,false,"'old'::character varying");
        var sql=new PostgresDialect().buildModifyColumnSql(metadataOnly(true,16),config,"public","customers",requested);
        String prefix="ALTER TABLE \"public\".\"customers\" ALTER COLUMN \"email\"";
        assertEquals(List.of(prefix+" DROP DEFAULT;",prefix+" TYPE VARCHAR(255) USING \"email\"::VARCHAR(255);",
                prefix+" SET DEFAULT 'old'::character varying;"),sql);
    }
    @Test void postgresNullabilityOnlyEditEmitsOnlyTheNullabilityClause() throws Exception {
        var config=new ConnectionConfig(DatabaseType.POSTGRESQL,"preview");
        var dialect=new PostgresDialect(); String prefix="ALTER TABLE \"public\".\"customers\" ALTER COLUMN \"email\"";
        var notNull=new ColumnDefinition("email","VARCHAR",180,false,false,false,"'old'::character varying");
        assertEquals(List.of(prefix+" SET NOT NULL;"),dialect.buildModifyColumnSql(metadataOnly(true,16),config,"public","customers",notNull));
        var droppedDefault=new ColumnDefinition("email","VARCHAR",180,true,false,false,"");
        assertEquals(List.of(prefix+" DROP DEFAULT;"),dialect.buildModifyColumnSql(metadataOnly(true,16),config,"public","customers",droppedDefault));
        var unchanged=new ColumnDefinition("email","VARCHAR",180,true,false,false,"'old'::character varying");
        assertEquals(List.of(),dialect.buildModifyColumnSql(metadataOnly(true,16),config,"public","customers",unchanged));
    }
    private static Connection metadataOnly(boolean postgres,int major) {
        Map<Object,Object> column=new HashMap<>();
        column.put("TABLE_NAME","customers");column.put("COLUMN_NAME","email");column.put("TYPE_NAME",postgres?"varchar":"VARCHAR");
        column.put("DATA_TYPE",Types.VARCHAR);column.put("COLUMN_SIZE",180);column.put("DECIMAL_DIGITS",0);
        column.put("IS_NULLABLE","YES");column.put("IS_AUTOINCREMENT","NO");column.put("IS_GENERATEDCOLUMN","NO");
        column.put("COLUMN_DEF",postgres?"'old'::character varying":"old");
        DatabaseMetaData meta=(DatabaseMetaData)Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),new Class[]{DatabaseMetaData.class},(p,m,a)->switch(m.getName()) {
            case "getDatabaseMajorVersion" -> major;
            case "getDatabaseMinorVersion" -> 7;
            case "getSearchStringEscape" -> "\\";
            case "storesLowerCaseIdentifiers" -> postgres;
            case "getSchemas" -> rows(List.of(Map.of("TABLE_SCHEM","public")));
            case "getTables" -> rows(List.of(Map.of("TABLE_NAME","customers")));
            case "getColumns" -> rows(List.of(column));
            case "getPrimaryKeys","getImportedKeys" -> rows(List.of());
            default -> throw new AssertionError("Unexpected metadata method: "+m.getName());
        });
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()) {
            case "getMetaData" -> meta;
            case "createStatement" -> Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{Statement.class},(sp,sm,sa)->switch(sm.getName()) {
                case "close" -> null;
                case "executeQuery" -> {
                    assertTrue(((String)sa[0]).startsWith("SHOW CREATE TABLE"));
                    yield rows(List.of(Map.of(2,"CREATE TABLE `customers` (\n  `email` varchar(180) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'old' COMMENT 'Keep me'\n)")));
                }
                default -> throw new AssertionError("Preview attempted a write: "+sm.getName());
            });
            case "prepareStatement" -> Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(sp,sm,sa)->switch(sm.getName()) {
                case "setString","close" -> null;
                case "executeQuery" -> rows(List.of(Map.of(1,"email",2,"character varying(180)",3,"")));
                default -> throw new AssertionError("Preview attempted a write: "+sm.getName());
            });
            default -> throw new AssertionError("Unexpected preview connection method: "+m.getName());
        });
    }
    private static ResultSet rows(List<? extends Map<?,?>> data) {
        AtomicInteger index=new AtomicInteger(-1);
        return (ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()) {
            case "next" -> index.incrementAndGet()<data.size();
            case "close" -> null;
            case "getString" -> {Object value=data.get(index.get()).get(a[0]);yield value==null?null:value.toString();}
            case "getInt" -> {Object value=data.get(index.get()).get(a[0]);yield value==null?0:((Number)value).intValue();}
            default -> throw new AssertionError("Unexpected result method: "+m.getName());
        });
    }
}
