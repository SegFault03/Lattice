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
    @Test @Tag("integration") void existingDatabaseFlows() { PluginIntegrationTest.main(new String[0]); }
    @Test @Tag("integration") void liveFunctionalRegressions() throws Exception { FunctionalRegressionTest.main(new String[0]); }
}
