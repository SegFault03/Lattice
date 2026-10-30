package com.segfault03.ideadb.sql.completion;

import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A statement's tokens with the questions the analyzers keep asking: is the token at this index a keyword, a given
 * punctuation character, a word. The index accessors answer false for an index outside the statement, except
 * {@link #isWord(int)}, whose index must be valid; {@link #isNameLike(int)} is its checked counterpart. Uppercase text is
 * worked out once per token, when first asked for.
 */
public class SqlTokens {
    private final List<SqlToken> tokens;
    private final CharSequence text;
    private final int size;
    private final String[] upper;

    public SqlTokens(List<SqlToken> tokens, CharSequence text) {
        this.tokens = tokens;
        this.text = text;
        this.size = tokens.size();
        this.upper = new String[size];
    }

    /** Whether the token is a name or a word that can act as one: identifier, keyword, function, type or quoted name. */
    public static boolean isWord(SqlToken token) { return isWord(token.type()); }

    public static boolean isWord(IElementType type) {
        return type == LatticeSqlTokens.IDENTIFIER || type == LatticeSqlTokens.KEYWORD || type == LatticeSqlTokens.FUNCTION
                || type == LatticeSqlTokens.TYPE || type == LatticeSqlTokens.QUOTED_IDENTIFIER;
    }

    public static boolean isPunct(SqlToken token, CharSequence text, char c) {
        return token.type() == LatticeSqlTokens.PUNCTUATION && text.charAt(token.start()) == c;
    }

    public CharSequence text() { return text; }
    public int size() { return size; }
    public SqlToken get(int i) { return tokens.get(i); }
    public boolean in(int i) { return i >= 0 && i < size; }

    public String up(int i) {
        String word = upper[i];
        if (word == null) upper[i] = word = tokens.get(i).text(text).toUpperCase(Locale.ROOT);
        return word;
    }

    public boolean punct(int i, char c) { return in(i) && isPunct(tokens.get(i), text, c); }
    public boolean keyword(int i, String word) { return in(i) && tokens.get(i).type() == LatticeSqlTokens.KEYWORD && up(i).equals(word); }
    public boolean keywordIn(int i, Set<String> words) { return in(i) && tokens.get(i).type() == LatticeSqlTokens.KEYWORD && words.contains(up(i)); }
    /** A word of any of the {@linkplain #isWord(SqlToken) word} types with this uppercase text. */
    public boolean word(int i, String word) { return in(i) && isWord(i) && up(i).equals(word); }
    public boolean ident(int i) { return in(i) && (tokens.get(i).type() == LatticeSqlTokens.IDENTIFIER || tokens.get(i).type() == LatticeSqlTokens.QUOTED_IDENTIFIER); }
    public boolean operator(int i, String op) { return in(i) && tokens.get(i).type() == LatticeSqlTokens.OPERATOR && up(i).equals(op); }
    /** Unchecked: {@code i} must be a valid index. */
    public boolean isWord(int i) { return isWord(tokens.get(i)); }
    /** {@link #isWord(int)} for any index. */
    public boolean isNameLike(int i) { return in(i) && isWord(tokens.get(i)); }
}
