package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.completion.SqlTokenizer;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A script file cut into the statements a script run sends one by one. Statements end at top-level {@code ;} tokens,
 * so semicolons in strings, comments and {@code $$} bodies never split. On MySQL, a {@code DELIMITER} line changes the
 * terminator for the lines after it, the way the mysql client reads stored routine definitions; the line itself is a
 * client command and is not sent.
 */
public record SqlScript(@NotNull String text, @NotNull List<Statement> statements) {
    /** One statement: its 1-based position, offsets in the script (without the terminator) and 1-based first line. */
    public record Statement(int index, int start, int end, int line, @NotNull String sql) {
        /** The statement on one line, shortened for a table cell. */
        public @NotNull String summary(int limit) {
            String flat = sql.replaceAll("\\s+", " ").strip();
            return flat.length() <= limit ? flat : flat.substring(0, Math.max(0, limit - 1)).stripTrailing() + "…";
        }
    }

    public static @NotNull SqlScript parse(@NotNull String text, @NotNull SqlDialectProfile profile) {
        List<int[]> ranges = new ArrayList<>();
        if (profile.databaseType() == DatabaseType.MYSQL && hasDelimiterCommand(text)) splitWithDelimiters(text, profile, ranges);
        else for (SqlStatementRange range : SqlStatementRange.inSelection(text, 0, text.length(), profile)) ranges.add(new int[]{range.start(), range.end()});
        List<Statement> statements = new ArrayList<>(ranges.size());
        int line = 1;
        int counted = 0;
        for (int[] range : ranges) {
            for (; counted < range[0]; counted++) if (text.charAt(counted) == '\n') line++;
            statements.add(new Statement(statements.size() + 1, range[0], range[1], line, text.substring(range[0], range[1])));
        }
        return new SqlScript(text, List.copyOf(statements));
    }

    /** The 1-based line of an offset in the script. */
    public int lineOf(int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static boolean hasDelimiterCommand(String text) {
        for (String line : text.split("\n", -1)) if (delimiterOf(line) != null) return true;
        return false;
    }

    /** The new terminator a {@code DELIMITER x} line sets, or null when the line is not one. */
    private static String delimiterOf(String line) {
        String trimmed = line.strip();
        if (trimmed.length() < 10 || !trimmed.substring(0, 9).toUpperCase(Locale.ROOT).equals("DELIMITER")) return null;
        if (!Character.isWhitespace(trimmed.charAt(9))) return null;
        String value = trimmed.substring(10).strip();
        return value.isEmpty() || value.contains(" ") ? null : value;
    }

    private static void splitWithDelimiters(String text, SqlDialectProfile profile, List<int[]> out) {
        String delimiter = ";";
        int segment = 0;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? text.length() : newline;
            String next = delimiterOf(text.substring(lineStart, lineEnd));
            if (next != null && !insideToken(text, segment, lineStart, profile)) {
                split(text, segment, lineStart, delimiter, profile, out);
                delimiter = next;
                segment = Math.min(text.length(), lineEnd + 1);
            }
            if (newline < 0) break;
            lineStart = newline + 1;
        }
        split(text, segment, text.length(), delimiter, profile, out);
    }

    /** Whether {@code at} falls inside a string or comment that starts in {@code [from, at)}. */
    private static boolean insideToken(String text, int from, int at, SqlDialectProfile profile) {
        for (SqlToken token : SqlTokenizer.tokenize(text.subSequence(from, at), profile)) {
            boolean protectedText = LatticeSqlTokens.COMMENTS.contains(token.type()) || token.type() == LatticeSqlTokens.STRING
                    || token.type() == LatticeSqlTokens.QUOTED_IDENTIFIER;
            if (!protectedText || from + token.end() < at) continue;
            String raw = token.text(text.subSequence(from, at));
            // an unterminated string or block comment runs to the end of the slice
            if (token.type() == LatticeSqlTokens.BLOCK_COMMENT ? !raw.endsWith("*/") : token.type() != LatticeSqlTokens.LINE_COMMENT
                    && (raw.length() < 2 || raw.charAt(raw.length() - 1) != raw.charAt(0))) return true;
        }
        return false;
    }

    private static void split(String text, int from, int to, String delimiter, SqlDialectProfile profile, List<int[]> out) {
        if (from >= to) return;
        if (delimiter.equals(";")) {
            for (SqlStatementRange range : SqlStatementRange.inSelection(text, from, to, profile)) out.add(new int[]{range.start(), range.end()});
            return;
        }
        // A custom terminator ends a statement wherever it appears outside strings and comments.
        List<SqlToken> tokens = SqlTokenizer.tokenize(text.subSequence(from, to), profile);
        int start = from;
        int position = from;
        int tokenIndex = 0;
        while (position < to) {
            while (tokenIndex < tokens.size() && from + tokens.get(tokenIndex).end() <= position) tokenIndex++;
            SqlToken token = tokenIndex < tokens.size() ? tokens.get(tokenIndex) : null;
            boolean shielded = token != null && from + token.start() <= position
                    && (LatticeSqlTokens.COMMENTS.contains(token.type()) || token.type() == LatticeSqlTokens.STRING || token.type() == LatticeSqlTokens.QUOTED_IDENTIFIER);
            if (shielded) { position = from + token.end(); continue; }
            if (text.startsWith(delimiter, position)) {
                add(text, start, position, profile, out);
                position += delimiter.length();
                start = position;
                continue;
            }
            position++;
        }
        add(text, start, to, profile, out);
    }

    /** Adds the code of {@code [from, to)} trimmed of whitespace and of comments around it; nothing when only comments remain. */
    private static void add(String text, int from, int to, SqlDialectProfile profile, List<int[]> out) {
        int first = -1;
        int last = -1;
        for (SqlToken token : SqlTokenizer.tokenize(text.subSequence(from, to), profile)) {
            if (LatticeSqlTokens.COMMENTS.contains(token.type())) continue;
            if (first < 0) first = from + token.start();
            last = from + token.end();
        }
        if (first >= 0) out.add(new int[]{first, last});
    }
}
