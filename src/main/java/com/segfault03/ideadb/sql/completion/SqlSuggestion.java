package com.segfault03.ideadb.sql.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One completion candidate, independent of the editor. {@code label} is what the user matches against and
 * {@code insertText} replaces the typed prefix. Higher priority sorts first.
 */
public record SqlSuggestion(@NotNull Kind kind, @NotNull String label, @NotNull String insertText,
                            @Nullable String typeText, @Nullable String tailText, double priority, @NotNull Key key) {

    public enum Kind { KEYWORD, FUNCTION, TYPE, TABLE, VIEW, NAMESPACE, COLUMN, ALIAS, JOIN }

    /** The key role of a column; it picks the icon and the "PK" or "FK" flag in front of the type. */
    public enum Key { NONE, PRIMARY, FOREIGN, PRIMARY_FOREIGN }

    public SqlSuggestion(@NotNull Kind kind, @NotNull String label, @NotNull String insertText,
                         @Nullable String typeText, @Nullable String tailText, double priority) {
        this(kind, label, insertText, typeText, tailText, priority, Key.NONE);
    }

    /** Whether choosing this suggestion should open the next popup, as after "schema." or "alias.". */
    public boolean continuesName() { return insertText.endsWith("."); }
}
