package com.segfault03.ideadb.service;

import java.util.Objects;

/** The committed scope to read again. Null namespace/table means all within the parent. */
public record DatabaseChange(String connectionId, String namespace, String table, boolean namespacesChanged) {
    public DatabaseChange {
        Objects.requireNonNull(connectionId);
        if (namespacesChanged && (namespace != null || table != null))
            throw new IllegalArgumentException("A namespace-list refresh must cover the connection");
        if (namespace == null && table != null)
            throw new IllegalArgumentException("A table refresh requires a namespace");
    }

    public static DatabaseChange table(String connectionId, String namespace, String table) {
        return new DatabaseChange(connectionId, namespace, table, false);
    }

    public static DatabaseChange connection(String connectionId) {
        return new DatabaseChange(connectionId, null, null, true);
    }

    public boolean affects(String id, String database, String name) {
        return connectionId.equals(id) && (namespace == null || namespace.equals(database))
                && (table == null || table.equals(name));
    }
}
