package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PostgresProviderTest {
    private final DatabaseProvider provider = DatabaseProviders.get(DatabaseType.POSTGRESQL);
    public interface BaseColumnMetadata extends java.sql.ResultSetMetaData {
        String getBaseColumnName(int column) throws java.sql.SQLException;
        String getBaseSchemaName(int column) throws java.sql.SQLException;
    }

    @Test void aliasedColumnsUseTheDriverExtensionWithoutDependingOnPgJdbcTypes() throws Exception {
        var metadata = (BaseColumnMetadata) java.lang.reflect.Proxy.newProxyInstance(
                BaseColumnMetadata.class.getClassLoader(), new Class<?>[]{BaseColumnMetadata.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getColumnName", "getColumnLabel" -> "display_alias";
                    case "getBaseColumnName" -> "original_id";
                    case "getSchemaName" -> "";
                    case "getBaseSchemaName" -> "original_schema";
                    default -> throw new java.sql.SQLFeatureNotSupportedException();
                });
        assertEquals("display_alias", metadata.getColumnName(1));
        assertEquals("original_id", provider.sourceColumnName(metadata, 1));
        assertEquals("original_schema", provider.sourceSchemaName(metadata, 1));
        var unavailable = (java.sql.ResultSetMetaData) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.ResultSetMetaData.class.getClassLoader(), new Class<?>[]{java.sql.ResultSetMetaData.class},
                (proxy, method, arguments) -> "display_alias");
        assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> provider.sourceColumnName(unavailable, 1));
    }

    @Test void connectionAndDriverDefaultsAreRegisteredAndJava8Compatible() throws Exception {
        var config = new ConnectionConfig(DatabaseType.POSTGRESQL,"postgres");
        assertEquals(5432,config.getPort()); assertEquals("postgres",config.getUser()); assertEquals("postgres",config.getDatabaseName());
        assertEquals(provider,DatabaseProviders.forUrl(config.buildJdbcUrl()).orElseThrow());
        config.setDatabaseName("quoted database"); assertTrue(config.buildJdbcUrl().contains("quoted%20database"));
        assertEquals("org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar",DriverCatalog.artifactPath(DatabaseType.POSTGRESQL,"42.7.14"));
        assertThrows(IllegalArgumentException.class,()->DriverCatalog.artifactPath(DatabaseType.POSTGRESQL,"42.2.29.jre7"));
        try (var input = org.postgresql.Driver.class.getResourceAsStream("Driver.class")) { byte[] bytes=input.readNBytes(8); assertEquals(52,(bytes[6]&255)*256+(bytes[7]&255)); }
    }
    @Test void serialAndDefaultsUsePortablePostgresSyntax() {
        var config=new ConnectionConfig(DatabaseType.POSTGRESQL,"ddl");
        String sql=DdlService.getInstance().buildCreateTableSql(config,"Case_sensitive","Items",List.of(new ColumnDefinition("ID","INT",0,false,true,true,""),new ColumnDefinition("TEXT","VARCHAR",80,true,false,false,"TEXT:O'Brien\\path")));
        assertTrue(sql.contains("\"ID\" SERIAL NOT NULL")); assertTrue(sql.contains("E'O''Brien\\\\path'")); assertTrue(sql.contains("\"Case_sensitive\".\"Items\""));
        assertFalse(sql.contains("ENGINE="));
    }
    @Test void binaryAndTemporalValuesHaveFaithfulSqlLiterals() {
        assertEquals("decode('00ff','hex')",provider.sqlLiteral(new byte[]{0,(byte)255},"bytea"));
        assertEquals("E'O''Brien\\\\path\\n'",provider.sqlLiteral("O'Brien\\path\n","text"));
        var tz=new ColumnMetadata("T","TIMESTAMP WITH TIME ZONE",Types.TIMESTAMP,35,6,true,false,false,null);
        assertEquals(java.time.OffsetDateTime.parse("2026-10-13T10:20:30Z"),CellValueConverter.convert(DatabaseType.POSTGRESQL,tz,"2026-10-13 10:20:30+00"));
        assertThrows(IllegalArgumentException.class,()->CellValueConverter.convert(DatabaseType.POSTGRESQL,tz,"2026-10-13 10:20:30.123456789+00"));
        assertEquals("infinity",CellValueConverter.convert(DatabaseType.POSTGRESQL,tz,"infinity"));
        assertEquals(java.util.UUID.fromString("dd39038f-081d-477a-9153-d931c322d331"),CellValueConverter.convert(DatabaseType.POSTGRESQL,new ColumnMetadata("ID","uuid",Types.OTHER,0,0,false,true,false,null),"dd39038f-081d-477a-9153-d931c322d331"));
    }
}
