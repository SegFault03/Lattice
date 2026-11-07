package com.segfault03.ideadb.sql.doc;

import org.jetbrains.annotations.NotNull;

/** What a name in SQL text refers to, by the names the schema uses (not as typed). */
public sealed interface SqlTarget {
    record Schema(@NotNull String namespace) implements SqlTarget {}
    record Table(@NotNull String namespace, @NotNull String table) implements SqlTarget {}
    record Column(@NotNull String namespace, @NotNull String table, @NotNull String column) implements SqlTarget {}
    /** A name the statement gives a table, as in {@code FROM orders o}. */
    record Alias(@NotNull String alias, @NotNull String namespace, @NotNull String table) implements SqlTarget {}
}
