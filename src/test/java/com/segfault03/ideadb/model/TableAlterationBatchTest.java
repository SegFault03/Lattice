package com.segfault03.ideadb.model;

import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TableAlterationBatchTest {
    private static ColumnMetadata column(String name) {return new ColumnMetadata(name,"VARCHAR",Types.VARCHAR,180,0,true,false,false,null);}
    private static TableAlteration rename(ColumnMetadata original,String name) {return new TableAlteration(TableAlteration.Kind.RENAME_COLUMN,original,name,"",0,0,false,"");}
    @Test void renameChainsRunFromTheFreeEndAndTableRenameIsAlwaysLast() {
        var a=column("a");var b=column("b");
        var ra=rename(a,"b");var rb=rename(b,"c");
        var add=TableAlteration.column(TableAlteration.Kind.ADD,null,"a","VARCHAR","32",true,"");
        var table=new TableAlteration(TableAlteration.Kind.RENAME_TABLE,null,"contacts","",0,0,false,"");
        var batch=new TableAlterationBatch("customers",List.of(a,b),List.of(table,add,ra,rb));
        assertNull(batch.problem()); assertEquals(List.of(rb,ra,add,table),batch.orderedChanges()); assertEquals("contacts",batch.targetTableName());
    }
    @Test void droppedNamesCanBeReusedButOtherDuplicateOrCyclicNamesCannot() {
        var a=column("a");var b=column("b");
        var add=TableAlteration.column(TableAlteration.Kind.ADD,null,"a","VARCHAR","32",true,"");
        var drop=new TableAlteration(TableAlteration.Kind.DROP,a,"a","",0,0,false,"");
        assertNull(new TableAlterationBatch("t",List.of(a,b),List.of(add,drop)).problem());
        assertNotNull(new TableAlterationBatch("t",List.of(a,b),List.of(add)).problem());
        var cycle=new TableAlterationBatch("t",List.of(a,b),List.of(rename(a,"b"),rename(b,"a")));
        assertTrue(cycle.problem().contains("cycle"));
        assertNotNull(new TableAlterationBatch("t",List.of(a,b),List.of(drop,rename(a,"c"))).problem());
    }
    @Test void aBatchIsImmutableAndCannotReferenceAMissingOriginal() {
        var a=column("a");var baseline=new ArrayList<>(List.of(a));var edits=new ArrayList<>(List.of(rename(a,"b")));
        var batch=new TableAlterationBatch("t",baseline,edits);baseline.clear();edits.clear();
        assertEquals(1,batch.baseline().size());assertEquals(1,batch.changes().size());assertNull(batch.problem());
        assertTrue(new TableAlterationBatch("t",List.of(),batch.changes()).problem().contains("missing"));
    }
}
