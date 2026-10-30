package com.segfault03.ideadb.sql.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One completion candidate, independent of the editor. {@code label} is what the user matches against and
 * {@code insertText} replaces the typed prefix. Higher priority sorts first.
 */
public record SqlSuggestion(@NotNull Kind kind, @NotNull String label, @NotNull String insertText,
                            @Nullable String typeText, @Nullable String tailText, double priority) {

    public enum Kind { KEYWORD, FUNCTION, TYPE, TABLE, VIEW, NAMESPACE, COLUMN, ALIAS, JOIN }

    /** Whether choosing this suggestion should open the next popup, as after "schema." or "alias.". */
    public boolean continuesName() { return insertText.endsWith("."); }
}
