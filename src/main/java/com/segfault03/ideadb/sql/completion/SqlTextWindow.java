package com.segfault03.ideadb.sql.completion;

import org.jetbrains.annotations.NotNull;

/**
 * The part of a large text worth analyzing for the caret: the statement around it plus some margin. Database dumps
 * are megabytes of statements, and completion and hover must not scan all of them for every keystroke. Statements
 * in such files end with a semicolon at the end of a line, which is where the window starts and ends when it can.
 */
public final class SqlTextWindow {
    /** Texts up to this size are analyzed whole. */
    static final int WHOLE_TEXT_LIMIT = 100_000;
    /** How far from the caret the window reaches looking for a statement boundary. */
    static final int REACH = 50_000;

    private SqlTextWindow() {}

    /** The range of {@code text} to analyze: all of it when it is small, else the statement around {@code caret}. */
    public record Range(int start, int end) {
        public int length() { return end - start; }
    }

    public static @NotNull Range around(@NotNull CharSequence text, int caret) {
        int length = text.length();
        if (length <= WHOLE_TEXT_LIMIT) return new Range(0, length);
        int clamped = Math.max(0, Math.min(caret, length));
        int start = Math.max(0, clamped - REACH);
        int end = Math.min(length, clamped + REACH);
        int boundaryBefore = lastBoundary(text, clamped, start);
        if (boundaryBefore >= 0) start = boundaryBefore;
        else if (start > 0) start = nextLineStart(text, start, clamped);
        int boundaryAfter = firstBoundary(text, clamped, end);
        if (boundaryAfter >= 0) end = boundaryAfter;
        return new Range(start, Math.max(end, Math.min(length, start)));
    }

    /** The offset just after the last ";" that ends a line before {@code caret}, not looking before {@code floor}; -1 if none. */
    private static int lastBoundary(CharSequence text, int caret, int floor) {
        for (int i = Math.min(caret, text.length()) - 1; i >= floor; i--) {
            if (text.charAt(i) == ';' && endsLine(text, i + 1)) return i + 1;
        }
        return -1;
    }

    /** The offset just after the first ";" that ends a line at or after {@code caret}, not looking past {@code ceiling}; -1 if none. */
    private static int firstBoundary(CharSequence text, int caret, int ceiling) {
        for (int i = caret; i < ceiling; i++) {
            if (text.charAt(i) == ';' && endsLine(text, i + 1)) return i + 1;
        }
        return -1;
    }

    private static boolean endsLine(CharSequence text, int afterSemicolon) {
        for (int i = afterSemicolon; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return true;
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    private static int nextLineStart(CharSequence text, int from, int limit) {
        for (int i = from; i < limit; i++) if (text.charAt(i) == '\n') return i + 1;
        return from;
    }
}
