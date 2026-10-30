package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.RowDefaults;
import com.segfault03.ideadb.model.TruncatedValue;

/** Text-only presentations for values in the cell details view. */
final class CellValuePresentation {
    private static final java.util.HexFormat HEX = java.util.HexFormat.of().withUpperCase();
    private static final int GRID_BINARY_CHARS = 64;
    private CellValuePresentation() {}

    static String raw(Object value) {
        if (value == null) return "NULL";
        if (value == RowDefaults.Value.USE_DEFAULT) return "(Default)";
        if (value instanceof byte[] bytes) {
            return "0x" + HEX.formatHex(bytes);
        }
        if (value instanceof TruncatedValue cut) return raw(cut.preview());
        return String.valueOf(value);
    }

    /** Grid form of an oversized LOB: the abbreviated preview followed by the truncation notice. */
    static String abbreviated(TruncatedValue cut) {
        return (cut.preview() instanceof byte[] bytes ? abbreviated(bytes) : (String) cut.preview()) + cut.suffix();
    }

    /** Grid form of a binary value: hex truncated to {@link #GRID_BINARY_CHARS} characters plus an ellipsis. */
    static String abbreviated(byte[] bytes) {
        int keep = (GRID_BINARY_CHARS - 2) / 2;
        if (bytes.length <= keep) return "0x" + HEX.formatHex(bytes);
        return "0x" + HEX.formatHex(bytes, 0, keep) + "…";
    }

    /** Editing a SQL sentinel begins with empty text; Apply can then stage an actual empty string. */
    static String editable(Object value) {
        return value == null || value == RowDefaults.Value.USE_DEFAULT ? "" : raw(value);
    }

    static boolean isStructured(Object value) {
        if (!(value instanceof String text)) return false;
        String trimmed = text.trim();
        if (trimmed.length() > 1_000_000 || !(trimmed.startsWith("{") || trimmed.startsWith("["))) return false;
        try {
            JsonFormatter formatter = new JsonFormatter(trimmed);
            formatter.value(0); formatter.space();
            return formatter.done();
        } catch (IllegalArgumentException ignored) { return false; }
    }

    static String formatted(Object value) {
        String raw = raw(value);
        String trimmed = raw.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return raw;
        if (trimmed.length() > 1_000_000) return raw;
        try {
            JsonFormatter formatter = new JsonFormatter(trimmed);
            String result = formatter.value(0);
            formatter.space();
            return formatter.done() ? result : raw;
        }
        catch (IllegalArgumentException ignored) { return raw; }
    }

    private static final class JsonFormatter {
        private final String input;
        private int index;
        private JsonFormatter(String input) { this.input = input; }
        private boolean done() { return index == input.length(); }
        private void space() { while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++; }
        private String value(int depth) {
            if (depth > 128) throw new IllegalArgumentException();
            space();
            if (index >= input.length()) throw new IllegalArgumentException();
            char c = input.charAt(index);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            int start = index;
            while (index < input.length() && ",]} \t\r\n".indexOf(input.charAt(index)) < 0) index++;
            String literal = input.substring(start, index);
            if (!(literal.equals("true") || literal.equals("false") || literal.equals("null") || literal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                throw new IllegalArgumentException();
            return literal;
        }
        private String object(int depth) {
            index++; space(); if (take('}')) return "{}";
            StringBuilder out = new StringBuilder("{"); int count = 0;
            while (true) {
                space(); if (peek() != '"') throw new IllegalArgumentException();
                String key = string(); space(); require(':');
                String item = value(depth + 1);
                if (count++ > 0) out.append(',');
                out.append('\n').append("  ".repeat(depth + 1)).append(key).append(": ").append(item);
                space(); if (take('}')) break; require(',');
            }
            return out.append('\n').append("  ".repeat(depth)).append('}').toString();
        }
        private String array(int depth) {
            index++; space(); if (take(']')) return "[]";
            StringBuilder out = new StringBuilder("["); int count = 0;
            while (true) {
                String item = value(depth + 1);
                if (count++ > 0) out.append(',');
                out.append('\n').append("  ".repeat(depth + 1)).append(item);
                space(); if (take(']')) break; require(',');
            }
            return out.append('\n').append("  ".repeat(depth)).append(']').toString();
        }
        private String string() {
            int start = index++; boolean escaped = false;
            while (index < input.length()) {
                char c = input.charAt(index++);
                if (escaped) {
                    if (c == 'u') {
                        if (index + 4 > input.length()) throw new IllegalArgumentException();
                        for (int n = 0; n < 4; n++) if (Character.digit(input.charAt(index + n), 16) < 0) throw new IllegalArgumentException();
                        index += 4;
                    } else if ("\"\\/bfnrt".indexOf(c) < 0) throw new IllegalArgumentException();
                    escaped = false;
                }
                else if (c == '\\') escaped = true;
                else if (c == '"') return input.substring(start, index);
                else if (c < 0x20) throw new IllegalArgumentException();
            }
            throw new IllegalArgumentException();
        }
        private char peek() { return index < input.length() ? input.charAt(index) : '\0'; }
        private boolean take(char c) { if (peek() == c) { index++; return true; } return false; }
        private void require(char c) { if (!take(c)) throw new IllegalArgumentException(); }
    }
}
