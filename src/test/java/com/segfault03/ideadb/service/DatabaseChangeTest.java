package com.segfault03.ideadb.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseChangeTest {
    @Test void tableChangesOnlyRefreshTheMatchingDataSourceNamespaceAndTable() {
        var change = DatabaseChange.table("source", "PUBLIC", "customers");
        assertTrue(change.affects("source", "PUBLIC", "customers"));
        assertFalse(change.affects("other", "PUBLIC", "customers"));
        assertFalse(change.affects("source", "OTHER", "customers"));
        assertFalse(change.affects("source", "PUBLIC", "orders"));
        assertFalse(change.affects("source", "PUBLIC", "CUSTOMERS"), "Quoted names are case sensitive");
        assertFalse(change.namespacesChanged());
    }

    @Test void namespaceChangesIncludeBothNamesOfARenamedTable() {
        var change = DatabaseChange.table("source", "PUBLIC", null);
        assertTrue(change.affects("source", "PUBLIC", "customers"));
        assertTrue(change.affects("source", "PUBLIC", "renamed_customers"));
        assertFalse(change.affects("source", "OTHER", "customers"));
    }

    @Test void arbitrarySqlAndNamespaceOperationsRefreshTheWholeConnection() {
        var change = DatabaseChange.connection("source");
        assertTrue(change.namespacesChanged());
        assertTrue(change.affects("source", "PUBLIC", "customers"));
        assertTrue(change.affects("source", "OTHER", "orders"));
        assertFalse(change.affects("other", "PUBLIC", "customers"));
    }

    @Test void invalidScopesAreRejected() {
        assertThrows(NullPointerException.class, () -> DatabaseChange.connection(null));
        assertThrows(IllegalArgumentException.class, () -> DatabaseChange.table("source", null, "customers"));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseChange("source", "PUBLIC", null, true));
    }
}
