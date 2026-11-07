package com.segfault03.ideadb.sql.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile.Syntax;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tokenizes SQL according to a dialect profile. Every token is atomic (a multi-line comment or string is one
 * token), so the lexer state is always 0 and the editor may restart at any token boundary. Every token is at
 * least one character, tokens are contiguous, and unterminated constructs run to the end of the buffer.
 */
public final class LatticeSqlLexer extends LexerBase {
    private static final String OPERATOR_CHARS = "+-*/%=<>!|&^~";
    private static final String STRING_PREFIXES = "eEnNbBxX";

    private final SqlDialectProfile profile;
    private final Syntax syntax;
    private CharSequence buffer = "";
    private int end;
    private int position;
    private int tokenStart;
    private IElementType tokenType;

    public LatticeSqlLexer(@NotNull SqlDialectProfile profile) {
        this.profile = profile;
        this.syntax = profile.syntax();
    }

    @Override public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.end = endOffset;
        this.position = startOffset;
        locate();
    }

    private char at(int index) { return index >= 0 && index < end ? buffer.charAt(index) : '\0'; }

    private static boolean identifierStart(char c) { return Character.isLetter(c) || c == '_'; }
    private static boolean identifierPart(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    private void locate() {
        tokenStart = position;
        if (position >= end) { tokenType = null; return; }
        char c = at(position);
        if (Character.isWhitespace(c)) {
            while (position < end && Character.isWhitespace(at(position))) position++;
            tokenType = TokenType.WHITE_SPACE;
        } else if (lineCommentStarts(position)) {
            while (position < end && at(position) != '\n' && at(position) != '\r') position++;
            tokenType = LatticeSqlTokens.LINE_COMMENT;
        } else if (c == '/' && at(position + 1) == '*') {
            blockComment();
        } else if (c == '\'') {
            quoted('\'', syntax.backslashEscapes());
            tokenType = LatticeSqlTokens.STRING;
        } else if (c == '"') {
            if (syntax.doubleQuoteIsString()) {
                quoted('"', syntax.backslashEscapes());
                tokenType = LatticeSqlTokens.STRING;
            } else {
                quoted('"', false);
                tokenType = LatticeSqlTokens.QUOTED_IDENTIFIER;
            }
        } else if (c == '`' && syntax.backtickIdentifiers()) {
            quoted('`', false);
            tokenType = LatticeSqlTokens.QUOTED_IDENTIFIER;
        } else if (STRING_PREFIXES.indexOf(c) >= 0 && at(position + 1) == '\'' && !identifierPart(at(position - 1))) {
            boolean escapes = syntax.backslashEscapes() || ((c == 'e' || c == 'E') && syntax.escapeStrings());
            position++;
            quoted('\'', escapes);
            tokenType = LatticeSqlTokens.STRING;
        } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(at(position + 1)) && !identifierPart(at(position - 1)) && at(position - 1) != ')')) {
            number();
        } else if (identifierStart(c)) {
            word();
        } else if (c == '$' && dollar()) {
            // consumed
        } else if (c == '?') {
            position++;
            tokenType = LatticeSqlTokens.PARAMETER;
        } else if (c == ':' && colon()) {
            // consumed
        } else if (c == '@' && syntax.atVariables() && (identifierStart(at(position + 1)) || at(position + 1) == '@')) {
            position++;
            if (at(position) == '@') position++;
            while (position < end && identifierPart(at(position))) position++;
            tokenType = LatticeSqlTokens.PARAMETER;
        } else if (OPERATOR_CHARS.indexOf(c) >= 0 || (c == '#' && !syntax.hashComments())) {
            position++;
            while (position < end && OPERATOR_CHARS.indexOf(at(position)) >= 0 && !commentStarts(position)) position++;
            tokenType = LatticeSqlTokens.OPERATOR;
        } else {
            position++;
            tokenType = LatticeSqlTokens.PUNCTUATION;
        }
    }

    private boolean lineCommentStarts(int index) {
        char c = at(index);
        if (c == '-' && at(index + 1) == '-') {
            if (!syntax.dashDashNeedsSpace()) return true;
            char after = at(index + 2);
            return index + 2 >= end || Character.isWhitespace(after) || Character.isISOControl(after);
        }
        if (c == '#') return syntax.hashComments();
        return c == '/' && at(index + 1) == '/' && syntax.slashSlashComments();
    }

    private boolean commentStarts(int index) {
        return lineCommentStarts(index) || (at(index) == '/' && at(index + 1) == '*');
    }

    private void blockComment() {
        position += 2;
        int depth = 1;
        while (position < end) {
            if (syntax.nestedBlockComments() && at(position) == '/' && at(position + 1) == '*') {
                depth++;
                position += 2;
            } else if (at(position) == '*' && at(position + 1) == '/') {
                position += 2;
                if (--depth == 0) break;
            } else {
                position++;
            }
        }
        position = Math.min(position, end);
        tokenType = LatticeSqlTokens.BLOCK_COMMENT;
    }

    /** Reads a quoted run; a doubled quote is an escaped quote, and a backslash escapes the next character when allowed. */
    private void quoted(char quote, boolean backslashEscapes) {
        position++;
        while (position < end) {
            char c = at(position);
            if (backslashEscapes && c == '\\') {
                position = Math.min(end, position + 2);
                continue;
            }
            position++;
            if (c == quote) {
                if (at(position) == quote) position++;
                else return;
            }
        }
    }

    private void number() {
        if (at(position) == '0' && (at(position + 1) == 'x' || at(position + 1) == 'X') && Character.digit(at(position + 2), 16) >= 0) {
            position += 2;
            while (Character.digit(at(position), 16) >= 0) position++;
        } else {
            while (Character.isDigit(at(position))) position++;
            if (at(position) == '.' && Character.isDigit(at(position + 1))) {
                position++;
                while (Character.isDigit(at(position))) position++;
            }
            char e = at(position);
            if ((e == 'e' || e == 'E') && (Character.isDigit(at(position + 1))
                    || ((at(position + 1) == '+' || at(position + 1) == '-') && Character.isDigit(at(position + 2))))) {
                position += 2;
                while (Character.isDigit(at(position))) position++;
            }
        }
        tokenType = LatticeSqlTokens.NUMBER;
    }

    private void word() {
        while (position < end && identifierPart(at(position))) position++;
        int next = position;
        while (next < end && Character.isWhitespace(at(next))) next++;
        SqlDialectProfile.WordKind kind = profile.classify(buffer.subSequence(tokenStart, position), at(next) == '(');
        tokenType = switch (kind) {
            case KEYWORD -> LatticeSqlTokens.KEYWORD;
            case FUNCTION -> LatticeSqlTokens.FUNCTION;
            case TYPE -> LatticeSqlTokens.TYPE;
            case IDENTIFIER -> LatticeSqlTokens.IDENTIFIER;
        };
    }

    /** Dollar-quoted strings and numbered parameters; returns false when the dollar is plain punctuation. */
    private boolean dollar() {
        if (syntax.dollarQuoting()) {
            int tagEnd = position + 1;
            if (!Character.isDigit(at(tagEnd))) {
                while (identifierPart(at(tagEnd)) && at(tagEnd) != '$') tagEnd++;
                if (at(tagEnd) == '$') {
                    tagEnd++;
                    String tag = buffer.subSequence(position, tagEnd).toString();
                    int close = indexOf(tag, tagEnd);
                    position = close < 0 ? end : close + tag.length();
                    tokenType = LatticeSqlTokens.STRING;
                    return true;
                }
            }
        }
        if (syntax.dollarParameters() && Character.isDigit(at(position + 1))) {
            position++;
            while (Character.isDigit(at(position))) position++;
            tokenType = LatticeSqlTokens.PARAMETER;
            return true;
        }
        return false;
    }

    private int indexOf(String needle, int from) {
        for (int i = from; i + needle.length() <= end; i++) {
            if (buffer.charAt(i) == needle.charAt(0) && buffer.subSequence(i, i + needle.length()).toString().equals(needle)) return i;
        }
        return -1;
    }

    /** The cast operator, assignment, and named parameters; returns false when the colon is plain punctuation. */
    private boolean colon() {
        char next = at(position + 1);
        if (next == ':' && syntax.castOperator()) {
            position += 2;
            tokenType = LatticeSqlTokens.OPERATOR;
            return true;
        }
        if (next == '=') {
            position += 2;
            tokenType = LatticeSqlTokens.OPERATOR;
            return true;
        }
        char previous = at(position - 1);
        if (identifierStart(next) && !identifierPart(previous) && previous != ')' && previous != ']' && previous != ':') {
            position++;
            while (position < end && identifierPart(at(position))) position++;
            tokenType = LatticeSqlTokens.PARAMETER;
            return true;
        }
        return false;
    }

    @Override public int getState() { return 0; }
    @Override public @Nullable IElementType getTokenType() { return tokenType; }
    @Override public int getTokenStart() { return tokenStart; }
    @Override public int getTokenEnd() { return position; }
    @Override public void advance() { locate(); }
    @Override public @NotNull CharSequence getBufferSequence() { return buffer; }
    @Override public int getBufferEnd() { return end; }
}
