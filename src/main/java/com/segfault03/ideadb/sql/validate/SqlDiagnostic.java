package com.segfault03.ideadb.sql.validate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One finding in SQL text.
 *
 * @param replacement text that would fix it (a likely intended spelling), or null
 */
public record SqlDiagnostic(@NotNull Kind kind, int start, int end, @NotNull String message, @Nullable String replacement) {
    /** Each kind is its own inspection, so its severity and on/off state are the user's to set. */
    public enum Kind { SYNTAX, UNKNOWN_TABLE, UNKNOWN_COLUMN, AMBIGUOUS_COLUMN, ALIAS }

    public SqlDiagnostic(@NotNull Kind kind, int start, int end, @NotNull String message) { this(kind, start, end, message, null); }
}
