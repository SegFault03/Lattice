package com.segfault03.ideadb.state;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TableDraftStateTest {
    private static TableDraftState.Draft draft(String value) {
        return TableDraftState.Draft.capture(List.of("A"), List.of("TEXT"), List.of(List.of("old")), List.of(List.of(value)));
    }

    @Test void stateIsASnapshotSoLaterEditsDoNotLeakIntoSerialization() {
        var store = new TableDraftState();
        store.put("table:c:db:t", draft("one"));
        var snapshot = store.getState();
        store.get("table:c:db:t").rows.get(0).cells.get(0).text = "two";
        store.put("table:c:db:u", draft("x"));
        assertEquals(1, snapshot.drafts.size());
        assertEquals("one", snapshot.drafts.get("table:c:db:t").values().get(0).get(0));
        assertNotSame(store.get("table:c:db:t"), snapshot.drafts.get("table:c:db:t"));
    }

    @Test void removingAConnectionDropsOnlyItsDrafts() {
        var store = new TableDraftState();
        store.put("table:c1:db:t", draft("a"));
        store.put("table:c1:db:u", draft("b"));
        store.put("table:c10:db:t", draft("c"));
        store.removeConnection("c1");
        assertNull(store.get("table:c1:db:t"));
        assertNull(store.get("table:c1:db:u"));
        assertNotNull(store.get("table:c10:db:t"));
    }
}
