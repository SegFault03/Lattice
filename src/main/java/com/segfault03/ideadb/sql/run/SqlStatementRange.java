package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.sql.completion.SqlTextWindow;
import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.completion.SqlTokenizer;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The offsets of one SQL statement, without surrounding whitespace, comments outside it or the closing semicolon.
 * Statements are split on top-level {@code ;} tokens of the dialect's lexer, so semicolons in strings, comments and
 * {@code $$} bodies never split.
 */
public record SqlStatementRange(int start, int end) {
    /**
     * The statement at {@code caret}: the one containing it, else the closest one ending before it (caret just after
     * a semicolon, on a blank line or in a comment between statements). Null before the first statement.
     */
    public static @Nullable SqlStatementRange atCaret(@NotNull CharSequence text, int caret, @NotNull SqlDialectProfile profile) {
        int clamped = Math.max(0, Math.min(caret, text.length()));
        SqlTextWindow.Range window = SqlTextWindow.around(text, clamped);
        SqlStatementRange preceding = null;
        for (SqlStatementRange range : split(text, window.start(), window.end(), profile)) {
            if (range.start() <= clamped && clamped <= range.end()) return range;
            if (range.end() < clamped) preceding = range;
        }
        return preceding;
    }

    /** The statements within {@code [start, end)} of {@code text}, in order; comment-only parts are dropped. */
    public static @NotNull List<SqlStatementRange> inSelection(@NotNull CharSequence text, int start, int end,
                                                               @NotNull SqlDialectProfile profile) {
        int from = Math.max(0, Math.min(start, text.length()));
        int to = Math.max(from, Math.min(end, text.length()));
        return split(text, from, to, profile);
    }

    private static List<SqlStatementRange> split(CharSequence text, int from, int to, SqlDialectProfile profile) {
        List<SqlStatementRange> ranges = new ArrayList<>();
        int first = -1;
        int last = -1;
        for (SqlToken token : SqlTokenizer.tokenize(text.subSequence(from, to), profile)) {
            if (token.type() == LatticeSqlTokens.PUNCTUATION && text.charAt(from + token.start()) == ';') {
                if (first >= 0) ranges.add(new SqlStatementRange(first, last));
                first = -1;
            } else if (!LatticeSqlTokens.COMMENTS.contains(token.type())) {
                if (first < 0) first = from + token.start();
                last = from + token.end();
            }
        }
        if (first >= 0) ranges.add(new SqlStatementRange(first, last));
        return ranges;
    }
}
