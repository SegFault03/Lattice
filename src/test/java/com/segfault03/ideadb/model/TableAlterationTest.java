package com.segfault03.ideadb.model;

import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TableAlterationTest {
    private final ColumnMetadata amount = new ColumnMetadata("amount", "DECIMAL", Types.DECIMAL, 12, 3, false, false, false, "0.000");

    @Test void preservesPrecisionScaleAndDatabaseDefaultUntilTheUserChangesThem() {
        var same = TableAlteration.column(TableAlteration.Kind.MODIFY, amount, "amount", "DECIMAL", "12, 3", false, "0.000");
        assertFalse(same.changed("items"));
        assertEquals(12, same.definition().getSize()); assertEquals(3, same.definition().getDecimalDigits());
        assertEquals(ColumnDefinition.DefaultKind.PRESERVED, same.definition().getDefaultKind());
        var changed = TableAlteration.column(TableAlteration.Kind.MODIFY, amount, "amount", "DECIMAL", "15, 4", false, "SQL:round(1.2345, 4)");
        assertTrue(changed.changed("items")); assertNull(changed.problem(List.of(amount), "items"));
        assertEquals(4, changed.definition().getDecimalDigits());
        assertEquals(ColumnDefinition.DefaultKind.AUTO, changed.definition().getDefaultKind());
    }
    @Test void invalidLengthsAndSqlFragmentsAreRejectedInsteadOfSilentlyBecomingZero() {
        for (String length : List.of("", "-1", "0", "abc", "10, 11", "999999999999"))
            assertThrows(IllegalArgumentException.class, () -> TableAlteration.column(TableAlteration.Kind.ADD, null, "value", "DECIMAL", length, true, ""));
        assertThrows(IllegalArgumentException.class, () -> TableAlteration.column(TableAlteration.Kind.ADD, null, "value", "VARCHAR", "10, 2", true, ""));
        var bad = TableAlteration.column(TableAlteration.Kind.ADD, null, "value", "INT; DROP TABLE items", "", true, "");
        assertEquals("Enter a single data type.", bad.problem(List.of(), "items"));
        var text = TableAlteration.column(TableAlteration.Kind.ADD, null, "value", "TEXT", "ignored", true, "TEXT:hello; world");
        assertEquals(0, text.size()); assertNull(text.problem(List.of(), "items"));
    }
    @Test void generatedAndPrimaryKeyPropertiesCannotBeChangedUnsafely() {
        var generated = new ColumnMetadata("total", "DECIMAL", Types.DECIMAL, 12, 3, true, false, false, null, true);
        var edit = TableAlteration.column(TableAlteration.Kind.MODIFY, generated, "total", "DECIMAL", "14, 3", true, "");
        assertTrue(edit.problem(List.of(generated), "items").contains("generated"));
        var key = new ColumnMetadata("id", "BIGINT", Types.BIGINT, 64, 0, false, true, true, null);
        var nullable = TableAlteration.column(TableAlteration.Kind.MODIFY, key, "id", "BIGINT", "", true, "");
        assertTrue(nullable.problem(List.of(key), "items").contains("primary-key"));
        var identityDefault = TableAlteration.column(TableAlteration.Kind.MODIFY, key, "id", "BIGINT", "", false, "7");
        assertTrue(identityDefault.problem(List.of(key), "items").contains("identity"));
    }
    @Test void namesAreQuotedByTheDialectButDuplicateAndBlankNamesAreCaughtInTheEditor() {
        var duplicate = TableAlteration.column(TableAlteration.Kind.ADD, null, "AMOUNT", "INT", "", false, "");
        assertTrue(duplicate.problem(List.of(amount), "items").contains("already exists"));
        var quoted = TableAlteration.column(TableAlteration.Kind.ADD, null, "odd\"name", "INT", "", true, "");
        assertNull(quoted.problem(List.of(amount), "items"));
        var rename = TableAlteration.column(TableAlteration.Kind.RENAME_COLUMN, amount, "amount", "INT", "", true, "");
        assertFalse(rename.changed("items"));
        var table = TableAlteration.column(TableAlteration.Kind.RENAME_TABLE, null, "items", "INT", "", true, "");
        assertFalse(table.changed("items"));
    }
    @Test void columnDefinitionIsAFreshSnapshotRatherThanMutableSharedUiState() {
        var change = TableAlteration.column(TableAlteration.Kind.MODIFY, amount, "amount", "DECIMAL", "14, 3", false, "0.000");
        change.definition().setName("corrupted");
        assertEquals("amount", change.definition().getName());
    }
}
