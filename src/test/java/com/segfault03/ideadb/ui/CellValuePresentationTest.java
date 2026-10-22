package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.RowDefaults;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
