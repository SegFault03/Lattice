package com.segfault03.ideadb.sql.completion;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/** One lexed token with its offsets in the text it came from. */
public record SqlToken(@NotNull IElementType type, int start, int end) {
    public @NotNull String text(@NotNull CharSequence source) { return source.subSequence(start, end).toString(); }
}
