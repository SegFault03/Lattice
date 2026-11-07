package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.CellValueConverter;
import com.segfault03.ideadb.model.RowDefaults;
import com.segfault03.ideadb.model.TruncatedValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CellValuePresentationTest {
    @Test void formatsOnlyCompleteValidJsonWithoutChangingRawValue() {
        String raw = " {\"items\":[1,{\"label\":\"a\\nb\"}],\"ok\":true} ";
        assertEquals(raw, CellValuePresentation.raw(raw));
        assertEquals("{\n  \"items\": [\n    1,\n    {\n      \"label\": \"a\\nb\"\n    }\n  ],\n  \"ok\": true\n}",
                CellValuePresentation.formatted(raw));
        for (String malformed : new String[]{"{\"value\":}", "[1,]", "{\"x\":1} trailing", "{\"x\":\"\\q\"}"})
            assertEquals(malformed, CellValuePresentation.formatted(malformed));
    }

    @Test void sqlSentinelsStayDistinctFromLiteralTextWhenEditing() {
        assertEquals("NULL", CellValuePresentation.raw(null));
        assertEquals("(Default)", CellValuePresentation.raw(RowDefaults.Value.USE_DEFAULT));
        assertEquals("", CellValuePresentation.editable(null));
        assertEquals("", CellValuePresentation.editable(RowDefaults.Value.USE_DEFAULT));
        assertEquals("NULL", CellValuePresentation.editable("NULL"));
        assertEquals("0x00FF", CellValuePresentation.raw(new byte[]{0, (byte) 0xFF}));
    }

    @Test void truncatedValuesShowPreviewWithNoticeAndCopyAsPreview() {
        TruncatedValue text = new TruncatedValue("abc", 3L * 1024 * 1024 + 100_000);
        assertEquals("abc", CellValuePresentation.raw(text));
        assertEquals("abc … (truncated, 3.1 Mi chars)", CellValuePresentation.abbreviated(text));
        assertEquals("abc … (truncated, 3.1 Mi chars)", text.toString());
        TruncatedValue bytes = new TruncatedValue(new byte[]{0, (byte) 0xFF}, 3L * 1024 * 1024 + 100_000);
        assertEquals("0x00FF", CellValuePresentation.raw(bytes));
        assertEquals("0x00FF … (truncated, 3.1 MiB)", CellValuePresentation.abbreviated(bytes));
    }

    @Test void truncatedValuesRefuseConversionToSql() {
        assertThrows(IllegalArgumentException.class, () -> CellValueConverter.convert(null, null, new TruncatedValue("a", 9)));
    }

    @Test void gridShowsBinaryAsAbbreviatedHex() {
        assertEquals("0x00FF", CellValuePresentation.abbreviated(new byte[]{0, (byte) 0xFF}));
        String shown = CellValuePresentation.abbreviated(new byte[100]);
        assertEquals(65, shown.length());
        assertEquals("0x" + "00".repeat(31) + "…", shown);
    }
}
