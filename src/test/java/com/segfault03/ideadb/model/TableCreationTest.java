package com.segfault03.ideadb.model;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TableCreationTest {
    private static TableCreation.Column column(String name,boolean key,boolean auto,boolean nullable,String value) {
        return TableCreation.Column.parse(name,"BIGINT","",nullable,key,auto,value);
    }
    @Test void definitionsAreImmutableSnapshotsAndKeepDecimalScaleAndDefaults() {
        var columns=new ArrayList<>(List.of(TableCreation.Column.parse("amount","DECIMAL","12, 3",false,false,false,"3.234")));
        var creation=new TableCreation(" odd\"table ",columns);columns.clear();
        assertEquals("odd\"table",creation.name());assertNull(creation.problem(DatabaseType.H2));assertEquals(1,creation.columns().size());
        var definition=creation.definitions().getFirst();assertEquals(3,definition.getDecimalDigits());assertEquals("3.234",definition.getDefaultValue());
        definition.setName("corrupt");assertEquals("amount",creation.definitions().getFirst().getName());
    }
    @Test void duplicateAndBlankNamesAndMalformedPrecisionAreRejected() {
        assertNotNull(new TableCreation("",List.of(column("id",true,true,false,""))).problem(DatabaseType.H2));
        assertNotNull(new TableCreation("t",List.of()).problem(DatabaseType.H2));
        assertTrue(new TableCreation("t",List.of(column("id",false,false,true,""),column("ID",false,false,true,""))).problem(DatabaseType.H2).contains("already exists"));
        assertNotNull(new TableCreation("t",List.of(column("",false,false,true,""))).problem(DatabaseType.H2));
        for(String size:List.of("oops","0","-1","10,11","99999999999"))
            assertThrows(IllegalArgumentException.class,()->TableCreation.Column.parse("amount","DECIMAL",size,true,false,false,""));
        var injected=TableCreation.Column.parse("x","INT; DROP TABLE y","",true,false,false,"");
        assertTrue(new TableCreation("t",List.of(injected)).problem(DatabaseType.H2).contains("single data type"));
    }
    @Test void keysAndIdentityConstraintsAreValidatedPerEngineWithoutLosingValues() {
        assertNotNull(new TableCreation("t",List.of(column("id",true,false,true,""))).problem(DatabaseType.H2));
        assertTrue(new TableCreation("t",List.of(column("id",true,true,false,"7"))).problem(DatabaseType.H2).contains("another default"));
        var text=TableCreation.Column.parse("id","VARCHAR","32",false,true,true,"");
        assertTrue(new TableCreation("t",List.of(text)).problem(DatabaseType.H2).contains("integer"));
        var nonKeyIdentity=new TableCreation("t",List.of(column("id",false,true,false,"")));
        assertNull(nonKeyIdentity.problem(DatabaseType.POSTGRESQL));assertNotNull(nonKeyIdentity.problem(DatabaseType.MYSQL));
        var composite=new TableCreation("t",List.of(column("tenant",true,false,false,""),column("id",true,true,false,"")));
        assertNull(composite.problem(DatabaseType.H2));assertTrue(composite.problem(DatabaseType.MYSQL).contains("first column"));
        var two=new TableCreation("t",List.of(column("a",true,true,false,""),column("b",true,true,false,"")));
        assertNotNull(two.problem(DatabaseType.HSQLDB));assertNotNull(two.problem(DatabaseType.MYSQL));assertNull(two.problem(DatabaseType.POSTGRESQL));
    }
}
