package com.segfault03.ideadb.sql.run;

import com.intellij.util.xmlb.XmlSerializer;
import org.junit.jupiter.api.Test;
import org.jdom.Element;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.segfault03.ideadb.sql.run.SqlParameterValue.Type;
import static org.junit.jupiter.api.Assertions.*;

class SqlRunStateTest {
    private static final String FILE_A = "file:///a.sql";
    private static final String FILE_B = "file:///b.sql";

    private static SqlParameterValue number(String text) { return new SqlParameterValue(Type.NUMBER, text); }

    @Test void remembersPerFileAndKey() {
        SqlRunState state = new SqlRunState();
        state.remember(FILE_A, Map.of("1", number("1"), "name", new SqlParameterValue(Type.TEXT, "x")));
        state.remember(FILE_B, Map.of("1", number("2")));
        assertEquals(number("1"), state.values(FILE_A).get("1"));
        assertEquals(new SqlParameterValue(Type.TEXT, "x"), state.values(FILE_A).get("name"));
        assertEquals(Map.of("1", number("2")), state.values(FILE_B));
        assertTrue(state.values("file:///none.sql").isEmpty());
    }

    @Test void mergeKeepsOtherKeys() {
        SqlRunState state = new SqlRunState();
        state.remember(FILE_A, Map.of("1", number("1"), "2", number("2")));
        state.remember(FILE_A, Map.of("2", number("3")));
        assertEquals(Map.of("1", number("1"), "2", number("3")), state.values(FILE_A));
    }

    @Test void valuesReturnsACopy() {
        SqlRunState state = new SqlRunState();
        state.remember(FILE_A, Map.of("1", number("1")));
        state.values(FILE_A).clear();
        assertEquals(1, state.values(FILE_A).size());
    }

    @Test void stateRoundTripsThroughLoadState() {
        SqlRunState state = new SqlRunState();
        Map<String, SqlParameterValue> values = new LinkedHashMap<>();
        values.put("1", number("1"));
        values.put("n", new SqlParameterValue(Type.NULL, ""));
        values.put("t", new SqlParameterValue(Type.TIMESTAMP, "2024-01-02 03:04:05"));
        state.remember(FILE_A, values);
        state.setConfirmRollbackRuns(false);

        Element element = XmlSerializer.serialize(state.getState());
        SqlRunState loaded = new SqlRunState();
        loaded.loadState(XmlSerializer.deserialize(element, SqlRunState.State.class));

        assertEquals(values, loaded.values(FILE_A));
        assertFalse(loaded.confirmRollbackRuns());
    }

    @Test void unknownTypeIsSkippedWhenLoading() {
        SqlRunState.State stored = new SqlRunState.State();
        SqlRunState.StoredValue bad = new SqlRunState.StoredValue();
        bad.type = "BLOB";
        bad.text = "x";
        SqlRunState.StoredValue good = new SqlRunState.StoredValue();
        good.type = "NUMBER";
        good.text = "5";
        stored.files.put(FILE_A, new LinkedHashMap<>(Map.of("bad", bad, "good", good)));
        SqlRunState state = new SqlRunState();
        state.loadState(stored);
        assertEquals(Map.of("good", number("5")), state.values(FILE_A));
    }

    @Test void confirmDefaultsToTrue() {
        SqlRunState state = new SqlRunState();
        assertTrue(state.confirmRollbackRuns());
        state.setConfirmRollbackRuns(false);
        assertFalse(state.confirmRollbackRuns());
    }
}
