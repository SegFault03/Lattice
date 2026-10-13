package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseProviderTest {
    @Test void everyPersistedTypeHasAnIndependentCompleteProvider() {
        for (DatabaseType type : DatabaseType.values()) {
            var provider = DatabaseProviders.get(type);
            var config = new ConnectionConfig(type, "defaults");
            assertEquals(type, provider.type());
            assertEquals(provider.defaultPort(), config.getPort());
            assertEquals(provider.defaultUser(), config.getUser());
            assertEquals(provider, DatabaseProviders.forUrl(config.buildJdbcUrl()).orElseThrow());
            assertNotNull(provider.dialect());
            assertTrue(provider.features().contains(DatabaseFeature.CREATE_TABLE));
            assertDoesNotThrow(() -> provider.requireFeature(DatabaseFeature.CREATE_TABLE));
            assertNotNull(provider.metadata());
            assertFalse(provider.driver().driverClasses().isEmpty());
            assertEquals(type.getDriverClassName(), provider.driver().driverClasses().getFirst());
            for (String version : provider.driver().suggestedVersions()) assertTrue(provider.driver().acceptsVersion(version));
        }
        assertNotSame(DatabaseProviders.get(DatabaseType.MYSQL).dialect(), DatabaseProviders.get(DatabaseType.HSQLDB).dialect());
        assertTrue(DatabaseProviders.forUrl("jdbc:postgresql://localhost/db").isEmpty());
        assertThrows(NullPointerException.class, () -> DatabaseProviders.get(null));
    }

    @Test void aMinimalProviderCannotAccidentallyAdvertiseDdlSupport() {
        var provider = (DatabaseProvider) java.lang.reflect.Proxy.newProxyInstance(DatabaseProvider.class.getClassLoader(),
                new Class[]{DatabaseProvider.class}, (proxy, method, args) -> {
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    if (method.getName().equals("type")) return DatabaseType.MYSQL;
                    throw new AssertionError("Unsupported operations must fail before calling " + method.getName());
                });
        assertTrue(provider.features().isEmpty());
        assertEquals(DatabaseProvider.ConnectionForm.SERVER, provider.connectionForm());
        assertThrows(UnsupportedOperationException.class, () -> provider.requireFeature(DatabaseFeature.CREATE_TABLE));
    }

    @Test void namespacesRetainCatalogAndSchemaAndEscapeEachIdentifier() {
        var mysql = DatabaseProviders.get(DatabaseType.MYSQL);
        var hsql = DatabaseProviders.get(DatabaseType.HSQLDB);
        assertEquals(new DatabaseNamespace("shop", null), mysql.namespace("shop"));
        assertEquals(new DatabaseNamespace(null, "PUBLIC"), hsql.namespace("PUBLIC"));
        assertEquals("`cat``alog`.`sch``ema`.`ta``ble`", mysql.dialect().formatTable(new DatabaseNamespace("cat`alog", "sch`ema"), "ta`ble"));
        assertEquals("\"cat\"\"alog\".\"sch\"\"ema\".\"ta\"\"ble\"", hsql.dialect().formatTable(new DatabaseNamespace("cat\"alog", "sch\"ema"), "ta\"ble"));
        assertEquals("\"items\"", hsql.dialect().formatTable(new DatabaseNamespace(null, null), "items"));
        assertThrows(IllegalArgumentException.class, () -> hsql.dialect().quoteIdentifier(""));
    }

    @Test void driverRulesCoverLegacyCoordinatesClassifiersAndIsolation() {
        var mysql = DatabaseProviders.get(DatabaseType.MYSQL).driver();
        var hsql = DatabaseProviders.get(DatabaseType.HSQLDB).driver();
        assertEquals("mysql/mysql-connector-java/5.1.49/mysql-connector-java-5.1.49.jar", mysql.artifactPath("5.1.49"));
        assertEquals("com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar", mysql.artifactPath("8.0.33"));
        assertEquals("org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4-jdk8.jar", hsql.artifactPath("2.7.4-jdk8"));
        assertEquals("2.7.4", hsql.baseVersion("2.7.4-jdk8"));
        assertEquals(List.of("2.7.4", "2.7.4-jdk8"), hsql.versionsForRelease("2.7.4"));
        assertFalse(mysql.acceptsVersion("8.0.33-jdk8"));
        for (var driver : List.of(mysql, hsql)) {
            assertFalse(driver.acceptsVersion("../secret"));
            assertTrue(driver.isolatesClass(driver.driverClasses().getFirst()));
            assertTrue(driver.isolatesResource(driver.driverClasses().getFirst().replace('.', '/') + ".class"));
            assertFalse(driver.isolatesClass("java.sql.Connection"));
        }
        assertFalse(mysql.isolatesClass(hsql.driverClasses().getFirst()));
    }

    @Test void customUrlsAndEmbeddedModesPreserveConnectionOptions() {
        assertEquals(HsqlMode.MEM, new ConnectionConfig(DatabaseType.MYSQL, "legacy defaults").getHsqlMode());
        var config = new ConnectionConfig(DatabaseType.HSQLDB, "embedded");
        config.setHsqlMode(HsqlMode.FILE); config.setDatabaseName("C:\\data\\test");
        assertEquals("jdbc:hsqldb:file:C:/data/test;shutdown=true", config.buildJdbcUrl());
        assertFalse(DatabaseProviders.get(config.getType()).usesServer(config));
        config.setHsqlMode(null);
        assertTrue(config.buildJdbcUrl().startsWith("jdbc:hsqldb:hsql://"));
        config.setHsqlMode(HsqlMode.MEM);
        assertTrue(config.buildJdbcUrl().startsWith("jdbc:hsqldb:mem:"));
        config.setCustomUrl(" jdbc:hsqldb:mem:custom;sql.syntax_mys=true ");
        assertEquals("jdbc:hsqldb:mem:custom;sql.syntax_mys=true", config.buildJdbcUrl());
        assertEquals(config.buildJdbcUrl(), config.copy().buildJdbcUrl());
    }

    @Test void mismatchedOrUnknownUrlsFailBeforeOpeningADriver() throws Exception {
        try (var owner = new ManagerOwner()) {
            var config = new ConnectionConfig(DatabaseType.MYSQL, "mismatch");
            config.setCustomUrl("jdbc:hsqldb:mem:wrong");
            assertThrows(SQLException.class, () -> owner.manager.openConnection(config));
            assertEquals(DatabaseType.MYSQL, config.getType());
            config.setCustomUrl("jdbc:unknown:db");
            assertFalse(owner.manager.testConnection(config).isSuccess());
        }
    }

    @Test void changingProviderTypeRetiresCachedBrowserAndConsoleConnectionsEvenWithTheSameUrl() throws Exception {
        try (var owner = new ManagerOwner()) {
            var config = new ConnectionConfig(DatabaseType.HSQLDB, "retirement");
            config.setCustomUrl("jdbc:hsqldb:mem:retirement_" + java.util.UUID.randomUUID() + ";shutdown=true");
            owner.manager.registerConfiguration(config);
            Connection browser = owner.manager.getConnection(config);
            try (var console = owner.manager.createSession(config)) {
                Connection pinned = console.execute(conn -> conn);
                var changed = config.copy(); changed.setType(DatabaseType.MYSQL);
                owner.manager.registerConfiguration(changed);
                assertTrue(browser.isClosed()); assertTrue(pinned.isClosed());
                assertThrows(SQLException.class, () -> owner.manager.getConnection(changed));
                assertThrows(SQLException.class, () -> console.execute(conn -> conn));
            }
        }
    }

    @Test void versionOrderingSupportsAdditionalReleaseSegmentsWithoutIntegerOverflow() {
        assertTrue(DriverVersionOrder.descending("23.9.0.25.07", "23.9.0.24.12") < 0);
        assertTrue(DriverVersionOrder.descending("2147483648.0.0", "9.0.0") < 0);
        assertTrue(DatabaseProviders.get(DatabaseType.HSQLDB).driver().compareVersionsDescending("2.7.4-jdk8", "2.7.4") < 0);
    }

    @Test void providerSpecificValuesAndStreamingDoNotLeakBetweenEngines() {
        var mysql = DatabaseProviders.get(DatabaseType.MYSQL);
        var hsql = DatabaseProviders.get(DatabaseType.HSQLDB);
        assertEquals(Integer.MIN_VALUE, mysql.fetchSize(100));
        assertEquals(100, hsql.fetchSize(100));
        assertEquals(new BigInteger("5"), mysql.bitValue("101"));
        assertArrayEquals(new byte[]{5}, (byte[]) hsql.bitValue("101"));
        var time = new ColumnMetadata("T", "TIME", java.sql.Types.TIME, 0, 6, true, false, false, null);
        assertEquals("838:59:59.123456", mysql.timeValue(time, "838:59:59.123456"));
        assertThrows(IllegalArgumentException.class, () -> mysql.timeValue(time, "839:00:00"));
        assertEquals(java.time.LocalTime.of(12, 30), hsql.timeValue(time, "12:30:00"));
        assertEquals("'O''Brien\\path'", hsql.sqlLiteral("O'Brien\\path", "VARCHAR"));
        assertTrue(mysql.sqlLiteral("O'Brien\\path", "VARCHAR").startsWith("CONVERT(X'"));
        assertThrows(IllegalArgumentException.class, () -> mysql.sqlLiteral(Double.NaN, "DOUBLE"));
    }

    @Test void ddlDefaultsAndIdentityStaySpecificToTheirEngine() {
        var column = new ColumnDefinition("ID", "INTEGER", 0, false, true, true, "");
        var mysql = new ConnectionConfig(DatabaseType.MYSQL, "mysql");
        var hsql = new ConnectionConfig(DatabaseType.HSQLDB, "hsql");
        var ddl = DdlService.getInstance();
        assertTrue(ddl.buildCreateTableSql(mysql, "shop", "items", List.of(column)).contains("AUTO_INCREMENT"));
        String sql = ddl.buildCreateTableSql(hsql, "PUBLIC", "items", List.of(column));
        assertTrue(sql.contains("GENERATED BY DEFAULT AS IDENTITY"));
        assertFalse(sql.contains("ENGINE="));
        for (DatabaseType type : DatabaseType.values()) {
            var dialect = DatabaseProviders.get(type).dialect();
            assertEquals("SELECT 1 LIMIT 10 OFFSET 20", dialect.paginate("SELECT 1", 10, 20));
            assertThrows(IllegalArgumentException.class, () -> dialect.paginate("SELECT 1", -1, 0));
        }
        assertEquals("INSERT INTO t () VALUES ()", DatabaseProviders.get(DatabaseType.MYSQL).dialect().defaultInsert("t"));
        assertEquals("INSERT INTO t DEFAULT VALUES", DatabaseProviders.get(DatabaseType.HSQLDB).dialect().defaultInsert("t"));
    }

    @Test void browserOperationsAreSerializedAndConsoleSessionsRemainIndependent() throws Exception {
        try (var owner = new ManagerOwner()) {
            var config = new ConnectionConfig(DatabaseType.HSQLDB, "serialization");
            config.setCustomUrl("jdbc:hsqldb:mem:browser_" + java.util.UUID.randomUUID() + ";shutdown=true");
            Connection browser = owner.manager.getConnection(config);
            AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
            ExecutorService workers = Executors.newFixedThreadPool(4);
            CountDownLatch ready = new CountDownLatch(4), start = new CountDownLatch(1);
            try {
                var futures = new java.util.ArrayList<Future<?>>();
                for (int i = 0; i < 4; i++) futures.add(workers.submit(() -> {
                    ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int j = 0; j < 25; j++) owner.manager.executeBrowser(config, conn -> {
                        assertSame(browser, conn); assertTrue(Thread.holdsLock(conn));
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try (var statement = conn.createStatement(); var rows = statement.executeQuery("VALUES(1)")) {
                            assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                        } finally { active.decrementAndGet(); }
                        return null;
                    });
                    return null;
                }));
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
                for (var future : futures) future.get(15, TimeUnit.SECONDS);
                assertEquals(1, peak.get());
                try (var console = owner.manager.createSession(config)) {
                    assertNotSame(browser, console.execute(conn -> conn));
                }
                assertFalse(browser.isClosed());
            } finally { workers.shutdownNow(); }
        }
    }

    private static class ManagerOwner implements AutoCloseable {
        final DatabaseConnectionManager manager = new DatabaseConnectionManager();
        public void close() { manager.dispose(); }
    }
}
