package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TableCreationServiceTest {
    private static TableCreation.Column c(String name,String type,String length,boolean nullable,boolean key,boolean auto,String value){return TableCreation.Column.parse(name,type,length,nullable,key,auto,value);}
    @Test void reviewedDefinitionCreatesOrderedColumnsIdentityPrecisionAndWorkingDefaults() throws Exception {
        for(DatabaseType engine:List.of(DatabaseType.HSQLDB,DatabaseType.H2)) {
            var config=new ConnectionConfig(engine,"composer");config.setHsqlMode(HsqlMode.MEM);config.setDatabaseName("create_"+UUID.randomUUID());var manager=new DatabaseConnectionManager();
            var definition=new TableCreation("customer\"records",List.of(c("id","BIGINT","",false,true,true,""),c("name","VARCHAR","120",false,false,false,""),
                    c("email","VARCHAR","255",false,false,false,""),c("status","VARCHAR","20",false,false,false,"TEXT:active"),
                    c("balance","DECIMAL","10,2",true,false,false,"0"),c("created_at","TIMESTAMP","",false,false,false,"SQL:CURRENT_TIMESTAMP")));
            assertNull(definition.problem(engine));
            try(var conn=manager.openConnection(config)) {
                var service=DdlService.getInstance();String preview=service.buildCreateTableSql(config,"PUBLIC",definition.name(),definition.definitions());
                assertTrue(MetadataService.getInstance().getColumns(conn,config,"PUBLIC",definition.name()).isEmpty(),"Preview must not create the table");
                var executed=new ArrayList<String>();
                Connection recording=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                    Object result=invoke(conn,m,a);
                    if(m.getName().equals("createStatement"))return java.lang.reflect.Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{Statement.class},(sp,sm,sa)->{
                        if(sm.getName().equals("execute"))executed.add((String)sa[0]);return invoke(result,sm,sa);
                    });
                    return result;
                });
                service.createTable(recording,config,"PUBLIC",definition.name(),definition.definitions());assertEquals(List.of(preview),executed);
                var columns=MetadataService.getInstance().getColumns(conn,config,"PUBLIC",definition.name());assertEquals(definition.columns().stream().map(TableCreation.Column::name).toList(),columns.stream().map(ColumnMetadata::getName).toList());
                assertTrue(columns.getFirst().isPrimaryKey());assertTrue(columns.getFirst().isAutoIncrement());assertEquals(120,columns.get(1).getColumnSize());assertEquals(255,columns.get(2).getColumnSize());
                assertTrue(columns.get(4).isNullable());assertEquals(10,columns.get(4).getColumnSize());assertEquals(2,columns.get(4).getDecimalDigits());
                String target="\"PUBLIC\".\"customer\"\"records\"";
                try(var stmt=conn.createStatement()) {
                    stmt.execute("INSERT INTO "+target+" (\"name\",\"email\") VALUES ('Ada','ada@example.com')");
                    try(var row=stmt.executeQuery("SELECT \"id\",\"status\",\"balance\",\"created_at\" FROM "+target)){assertTrue(row.next());assertNotNull(row.getObject(1));assertEquals("active",row.getString(2));assertEquals("0.00",row.getBigDecimal(3).toPlainString());assertNotNull(row.getTimestamp(4));}
                }
            } finally {manager.dispose();}
        }
    }
    @Test void compositeKeysKeepTheirDefinitionOrderAndAreEnforcedByTheDatabase() throws Exception {
        for(DatabaseType engine:List.of(DatabaseType.HSQLDB,DatabaseType.H2)) {
            var config=new ConnectionConfig(engine,"composite");config.setHsqlMode(HsqlMode.MEM);config.setDatabaseName("composite_"+UUID.randomUUID());var manager=new DatabaseConnectionManager();
            var definition=new TableCreation("items",List.of(c("tenant","BIGINT","",false,true,false,""),c("code","VARCHAR","32",false,true,false,"")));
            try(var conn=manager.openConnection(config);var stmt=conn.createStatement()) {
                assertNull(definition.problem(engine));String sql=DdlService.getInstance().buildCreateTableSql(config,"PUBLIC",definition.name(),definition.definitions());
                assertTrue(sql.contains("PRIMARY KEY (\"tenant\", \"code\")"));DdlService.getInstance().createTable(conn,config,"PUBLIC",definition.name(),definition.definitions());
                stmt.execute("INSERT INTO \"items\" VALUES(1,'a')");stmt.execute("INSERT INTO \"items\" VALUES(2,'a')");
                assertThrows(SQLException.class,()->stmt.execute("INSERT INTO \"items\" VALUES(1,'a')"));
            } finally {manager.dispose();}
        }
    }
    private static Object invoke(Object target,java.lang.reflect.Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
    }
}
