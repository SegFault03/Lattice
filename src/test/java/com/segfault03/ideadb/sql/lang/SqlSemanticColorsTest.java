package com.segfault03.ideadb.sql.lang;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ColumnReference;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class SqlSemanticColorsTest {
    private static ColumnMetadata column(String type, int dataType, boolean key, ColumnReference reference) {
        var column = new ColumnMetadata("c", type, dataType, 10, 0, true, key, false, null);
        return reference == null ? column : column.withReference(reference);
    }

    private static final ColumnReference REFERENCE = new ColumnReference("PUBLIC", "t", "id");

    @Test void aPrimaryKeyIsOneEvenWhenItIsAlsoAForeignKey() {
        assertEquals(Role.PRIMARY_KEY, SqlSemanticColors.roleOf(column("INTEGER", Types.INTEGER, true, null)));
        assertEquals(Role.PRIMARY_KEY, SqlSemanticColors.roleOf(column("INTEGER", Types.INTEGER, true, REFERENCE)));
    }

    @Test void aColumnThatReferencesAnotherIsAForeignKey() {
        assertEquals(Role.FOREIGN_KEY, SqlSemanticColors.roleOf(column("INTEGER", Types.INTEGER, false, REFERENCE)));
    }

    @Test void otherColumnsFollowTheKindOfTheirValues() {
        assertEquals(Role.NUMERIC, SqlSemanticColors.roleOf(column("DECIMAL", Types.DECIMAL, false, null)));
        assertEquals(Role.TEXT, SqlSemanticColors.roleOf(column("VARCHAR", Types.VARCHAR, false, null)));
        assertEquals(Role.TEMPORAL, SqlSemanticColors.roleOf(column("TIMESTAMP", Types.TIMESTAMP, false, null)));
        assertEquals(Role.BOOLEAN, SqlSemanticColors.roleOf(column("BOOLEAN", Types.BOOLEAN, false, null)));
        assertEquals(Role.BINARY, SqlSemanticColors.roleOf(column("BLOB", Types.BLOB, false, null)));
        assertEquals(Role.OTHER, SqlSemanticColors.roleOf(column("OTHER", Types.OTHER, false, null)));
    }

    @Test void typesThatAreStoredAsTextButMeanSomethingElseAreOther() {
        assertEquals(Role.OTHER, SqlSemanticColors.roleOf(column("JSON", Types.VARCHAR, false, null)));
        assertEquals(Role.OTHER, SqlSemanticColors.roleOf(column("uuid", Types.CHAR, false, null)));
        assertEquals(Role.OTHER, SqlSemanticColors.roleOf(column("ENUM", Types.VARCHAR, false, null)));
    }

    @Test void everyRoleHasItsOwnKeyAndDistinctDefaultsForEachBrightness() {
        var keys = new HashSet<String>();
        for (Role role : Role.values()) {
            assertTrue(keys.add(role.key().getExternalName()), role.name());
            assertNotEquals(role.defaultColor(true), role.defaultColor(false), role + ": a dark and a light scheme need different colors");
        }
        for (boolean dark : new boolean[] {true, false}) {
            var colors = new HashSet<java.awt.Color>();
            for (Role role : Role.values()) assertTrue(colors.add(role.defaultColor(dark)), role + " repeats another role's color");
        }
    }
}
