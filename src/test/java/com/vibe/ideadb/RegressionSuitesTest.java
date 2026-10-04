package com.vibe.ideadb;

import com.vibe.ideadb.model.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/** Default tests need no database; integration-tagged tests exercise the live fixture servers. */
public class RegressionSuitesTest {
    @Test void editorContracts() { EditorIntegrationTest.main(new String[0]); }
    @Test void typedDraftPersistence() { FunctionalRegressionTest.drafts(); }
    @Test void credentialPersistenceAndMigration() { FunctionalRegressionTest.credentials(); }
    @Test void originalRowIdentity() {
        var original=new ArrayList<Object>(List.of(1,7,"value"));
        var keys=RowIdentity.originalKeys(List.of("ID","TENANT","VALUE"),original,List.of("ID","TENANT"));
        original.set(0,9); assertEquals(Map.of("ID",1,"TENANT",7),keys);
        assertThrows(IllegalStateException.class,() -> RowIdentity.originalKeys(List.of("ID"),List.of(1),List.of()));
    }
    @Test void valueConversionBoundaries() {
        var unsigned=new ColumnMetadata("ID","INT UNSIGNED",java.sql.Types.INTEGER,10,0,false,true,false,null);
        assertEquals(4294967295L,CellValueConverter.convert(DatabaseType.MYSQL,unsigned,"4294967295"));
        assertNotNull(CellValueConverter.validate(DatabaseType.MYSQL,unsigned,"4294967296"));
        assertNotNull(CellValueConverter.validate(DatabaseType.MYSQL,unsigned,"-1"));
    }
    @Test void mutationCleanupPreservesOutcome() throws Exception {
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        var rolledBack=new java.util.concurrent.atomic.AtomicBoolean();
        var connection=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args) -> switch(method.getName()) {
            case "getAutoCommit" -> true;
            case "isClosed" -> closed.get();
            case "setAutoCommit" -> { if(Boolean.TRUE.equals(args[0])) throw new java.sql.SQLException("reset failed"); yield null; }
            case "commit" -> { committed.set(true); yield null; }
            case "rollback" -> { rolledBack.set(true); yield null; }
            case "close" -> { closed.set(true); throw new java.sql.SQLException("close failed"); }
            default -> throw new AssertionError("Unexpected call " + method.getName());
        });
        var data=com.vibe.ideadb.service.DataService.getInstance();
        assertEquals("saved",data.withMutationConnection(connection,c -> data.inTransaction(c,unused -> "saved")));
        assertTrue(committed.get()); assertFalse(rolledBack.get()); assertTrue(closed.get());
        closed.set(false); committed.set(false);
        var original=new java.sql.SQLException("write failed");
        var error=assertThrows(java.sql.SQLException.class,() -> data.withMutationConnection(connection,c -> data.inTransaction(c,unused -> { throw original; })));
        assertSame(original,error); assertEquals(1,error.getSuppressed().length);
        assertTrue(rolledBack.get()); assertFalse(committed.get()); assertTrue(closed.get());
    }
    @Test void failedRollbackCannotEnableAutoCommit() {
        var restored=new java.util.concurrent.atomic.AtomicBoolean();
        var connection=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args) -> switch(method.getName()) {
            case "getAutoCommit" -> true;
            case "isClosed" -> false; // even a failed close must never allow implicit commit
            case "setAutoCommit" -> { if(Boolean.TRUE.equals(args[0])) restored.set(true); yield null; }
            case "rollback", "close" -> throw new java.sql.SQLException(method.getName() + " failed");
            default -> throw new AssertionError("Unexpected call " + method.getName());
        });
        var original=new java.sql.SQLException("write failed");
        var data=com.vibe.ideadb.service.DataService.getInstance();
        var error=assertThrows(java.sql.SQLException.class,() -> data.withMutationConnection(connection,c -> data.inTransaction(c,unused -> { throw original; })));
        assertSame(original,error); assertEquals(2,error.getSuppressed().length);
        assertFalse(restored.get(),"Auto-commit could commit writes after rollback failed");
    }
    @Test void driverSelectionPersistenceAndCoordinates() {
        var configuration = new ConnectionConfig(DatabaseType.MYSQL, "selected driver");
        assertEquals(DriverSource.BUNDLED, configuration.getDriverSource());
        configuration.setDriverSource(DriverSource.DOWNLOAD); configuration.setDriverVersion("5.1.49");
        configuration.setDriverJarPath("D:/drivers/mysql.jar");
        var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(configuration.copy()), ConnectionConfig.class);
        assertEquals(DriverSource.DOWNLOAD, restored.getDriverSource());
        assertEquals("5.1.49", restored.getDriverVersion()); assertEquals(configuration.getDriverJarPath(), restored.getDriverJarPath());
        assertEquals("mysql/mysql-connector-java/5.1.49/mysql-connector-java-5.1.49.jar", com.vibe.ideadb.service.DriverCatalog.artifactPath(DatabaseType.MYSQL,"5.1.49"));
        assertEquals("com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar", com.vibe.ideadb.service.DriverCatalog.artifactPath(DatabaseType.MYSQL,"8.0.33"));
        assertEquals("org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4-jdk8.jar", com.vibe.ideadb.service.DriverCatalog.artifactPath(DatabaseType.HSQLDB,"2.7.4-jdk8"));
        assertThrows(IllegalArgumentException.class, () -> com.vibe.ideadb.service.DriverCatalog.artifactPath(DatabaseType.MYSQL,"../secret"));
        assertThrows(IllegalArgumentException.class, () -> com.vibe.ideadb.service.DriverCatalog.artifactPath(DatabaseType.MYSQL,"8.0.33-jdk8"));
    }
    @Test @Tag("integration") void changingDriverRevokesExistingSessions() throws Exception {
        var config = new ConnectionConfig(DatabaseType.HSQLDB, "driver switch");
        var manager = com.vibe.ideadb.service.DatabaseConnectionManager.getInstance();
        manager.registerConfiguration(config);
        try {
            var previous = manager.getConnection(config);
            var unchanged = config.copy(); unchanged.setDriverVersion("unused selection");
            manager.registerConfiguration(unchanged);
            assertSame(previous, manager.getConnection(config), "Unused driver fields must not retire sessions");
            var updated = config.copy(); updated.setDriverSource(DriverSource.LOCAL_JAR);
            updated.setDriverJarPath(new java.io.File("lib/hsqldb-2.7.3.jar").getAbsolutePath());
            manager.registerConfiguration(updated);
            assertTrue(previous.isClosed());
            assertThrows(java.sql.SQLException.class, () -> manager.getConnection(config));
            try (var current = manager.openConnection(updated)) { assertFalse(current.isClosed()); }
        } finally { manager.removeConfiguration(config.getId()); }
    }
    @Test @Tag("integration") void existingDatabaseFlows() { PluginIntegrationTest.main(new String[0]); }
    @Test @Tag("integration") void liveFunctionalRegressions() throws Exception { FunctionalRegressionTest.main(new String[0]); }
}
