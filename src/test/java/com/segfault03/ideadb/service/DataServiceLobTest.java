package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.TruncatedValue;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DataServiceLobTest {
    private static final int BLOB_LIMIT = 2 * 1024 * 1024;
    private static final int CLOB_LIMIT = 1024 * 1024;

    @Test void smallLobsAreDetachedInFull() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) DataService.detachValue(new SerialBlob(new byte[]{1, 2, 3})));
        assertEquals("abc", DataService.detachValue(new SerialClob("abc".toCharArray())));
        assertEquals("x", DataService.detachDisplayValue("x"));
    }

    @Test void displayMarksOversizedLobsAsTruncatedInsteadOfFailing() throws Exception {
        TruncatedValue blob = (TruncatedValue) DataService.detachDisplayValue(new SerialBlob(new byte[BLOB_LIMIT + 10]));
        assertEquals(BLOB_LIMIT, ((byte[]) blob.preview()).length);
        assertEquals(BLOB_LIMIT + 10L, blob.fullLength());
        TruncatedValue text = (TruncatedValue) DataService.detachDisplayValue(new SerialClob("a".repeat(CLOB_LIMIT + 10).toCharArray()));
        assertEquals(CLOB_LIMIT, ((String) text.preview()).length());
        assertEquals(CLOB_LIMIT + 10L, text.fullLength());
    }

    @Test void displayLeavesLobsAtTheLimitUntruncated() throws Exception {
        assertEquals(BLOB_LIMIT, ((byte[]) DataService.detachDisplayValue(new SerialBlob(new byte[BLOB_LIMIT]))).length);
        assertEquals(CLOB_LIMIT, ((String) DataService.detachDisplayValue(new SerialClob("a".repeat(CLOB_LIMIT).toCharArray()))).length());
    }

    @Test void exportStillRefusesOversizedLobsRatherThanTruncating() throws Exception {
        assertThrows(SQLException.class, () -> DataService.detachValue(new SerialBlob(new byte[BLOB_LIMIT + 1])));
        assertThrows(SQLException.class, () -> DataService.detachValue(new SerialClob("a".repeat(CLOB_LIMIT + 1).toCharArray())));
    }
}
