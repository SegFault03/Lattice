package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class H2ProviderTest {
    @Test void modesBuildH2UrlsWithoutChangingCustomOptions() {
        var config = new ConnectionConfig(DatabaseType.H2, "h2");
        assertEquals("sa", config.getUser());
        assertEquals(9092, config.getPort());
        assertEquals("jdbc:h2:mem:testdb", config.buildJdbcUrl());
        config.setHsqlMode(HsqlMode.FILE);
        config.setDatabaseName("data/test");
        assertEquals("jdbc:h2:file:./data/test", config.buildJdbcUrl());
        config.setDatabaseName("data/test.mv.db");
        assertEquals("jdbc:h2:file:./data/test", config.buildJdbcUrl());
        config.setDatabaseName("C:\\data\\test");
        assertEquals("jdbc:h2:file:C:/data/test", config.buildJdbcUrl());
        config.setHsqlMode(HsqlMode.SERVER);
        config.setDatabaseName("~/test");
        assertEquals("jdbc:h2:tcp://localhost:9092/~/test", config.buildJdbcUrl());
        config.setCustomUrl(" jdbc:h2:ssl://host/~/db;IFEXISTS=TRUE ");
        assertEquals("jdbc:h2:ssl://host/~/db;IFEXISTS=TRUE", config.copy().buildJdbcUrl());
        assertEquals(DatabaseType.H2, DatabaseProviders.forUrl(config.buildJdbcUrl()).orElseThrow().type());
    }

    @Test void driverSelectionAndSqlStayIndependentFromHsql() {
        var provider = DatabaseProviders.get(DatabaseType.H2);
        assertEquals("com/h2database/h2/2.3.232/h2-2.3.232.jar", provider.driver().artifactPath("2.3.232"));
        for (String invalid : new String[]{"1.4.200", "2.3.232-jdk8", "../2.3.232", ""})
            assertFalse(provider.driver().acceptsVersion(invalid));
        assertTrue(provider.driver().isolatesClass("org.h2.Driver"));
        assertFalse(provider.driver().isolatesClass("org.hsqldb.jdbc.JDBCDriver"));
        assertEquals("\"a\"\"b\".\"items\"", provider.dialect().formatTable(provider.namespace("a\"b"), "items"));
        assertEquals("'O''Brien\\path'", provider.sqlLiteral("O'Brien\\path", "VARCHAR"));
        assertNotSame(DatabaseProviders.get(DatabaseType.HSQLDB).dialect(), provider.dialect());
    }
}
