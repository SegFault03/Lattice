package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.util.TextRange;
import com.segfault03.ideadb.sql.run.SqlStatementKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One piece of SQL the editor shows as a card: a statement of a {@code .sql} file, or the whole SQL of one injection
 * into a Java or Kotlin string. {@link #segments} are host-document ranges in ascending order; {@link #sql} is the text
 * the actions work on, with dynamic parts of an injection already {@code ?}. {@link #kind} is null unless the card
 * holds exactly one statement.
 */
public record SqlCard(@NotNull List<TextRange> segments, @NotNull String sql, boolean injected,
                      @Nullable SqlStatementKind kind, int statementCount) {
    public SqlCard {
        if (segments.isEmpty()) throw new IllegalArgumentException("A card needs at least one segment");
        segments = List.copyOf(segments);
    }

    /** From the start of the first segment to the end of the last. */
    public @NotNull TextRange range() {
        return new TextRange(segments.get(0).getStartOffset(), segments.get(segments.size() - 1).getEndOffset());
    }
}
