package com.segfault03.ideadb.sql.lang;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ColumnReference;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily;
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

    @Test void everyKeyIsOneRolesAndEveryFamilyLooksDifferentInDarkAndLightSchemes() {
        var keys = new HashSet<String>();
        for (Role role : Role.values()) {
            for (var key : role.keys()) assertTrue(keys.add(key.getExternalName()), role.name() + " shares a key");
        }
        for (ColorFamily family : ColorFamily.values()) {
            for (boolean dark : new boolean[] {true, false}) {
                var looks = new HashSet<String>();
                for (Role role : Role.values()) {
                    assertTrue(looks.add(role.defaultColor(dark, family).getRGB() + "/" + role.defaultFontType(family)), role + " repeats another role's look in " + family);
                }
            }
            for (Role role : Role.values()) {
                assertNotEquals(role.defaultColor(true, family), role.defaultColor(false, family), role + " in " + family + ": a dark and a light scheme need different colors");
            }
        }
    }

    @Test void tablesViewsAndAliasesHaveKeysPerFamilyAndColumnsOneForAll() {
        assertEquals("LATTICE_SQL_TABLE", Role.TABLE.key(ColorFamily.CORAL).getExternalName(), "coral keeps the plain names");
        assertEquals("LATTICE_SQL_TABLE_PERIWINKLE", Role.TABLE.key(ColorFamily.PERIWINKLE).getExternalName());
        assertEquals("LATTICE_SQL_VIEW_LIME", Role.VIEW.key(ColorFamily.LIME).getExternalName());
        assertEquals("LATTICE_SQL_ALIAS_NEUTRAL", Role.ALIAS.key(ColorFamily.NEUTRAL).getExternalName());
        assertEquals(4, Role.TABLE.keys().size());
        for (Role column : new Role[] {Role.PRIMARY_KEY, Role.FOREIGN_KEY, Role.NUMERIC, Role.TEXT, Role.OTHER}) {
            assertFalse(column.byFamily(), column.name());
            assertEquals(1, column.keys().size());
            for (ColorFamily family : ColorFamily.values()) assertEquals(column.defaultColor(true, ColorFamily.CORAL), column.defaultColor(true, family));
        }
    }

    @Test void theFamiliesDifferOnlyInTablesViewsAndAliases() {
        for (Role role : new Role[] {Role.TABLE, Role.VIEW, Role.ALIAS}) {
            var colors = new HashSet<java.awt.Color>();
            for (ColorFamily family : ColorFamily.values()) colors.add(role.defaultColor(false, family));
            assertEquals(4, colors.size(), role + " looks the same in two families");
        }
    }

    @Test void withoutAnIdeTheDefaultPeriwinkleFamilyApplies() {
        assertEquals(ColorFamily.PERIWINKLE, SqlSemanticColors.family());
        assertEquals(Role.TABLE.key(ColorFamily.PERIWINKLE), Role.TABLE.key());
    }
}
