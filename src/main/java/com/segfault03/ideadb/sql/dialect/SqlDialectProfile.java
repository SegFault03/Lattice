package com.segfault03.ideadb.sql.dialect;

import com.segfault03.ideadb.model.DatabaseType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Everything the editor features need to know about one SQL dialect: its lexical rules and its word lists.
 * The profile for a file comes from the connection bound to it, so MySQL, PostgreSQL, HSQLDB and H2 differ
 * where their syntax differs (quoting, comments, escapes, parameter markers, keywords, functions, types).
 */
public record SqlDialectProfile(@NotNull String id, @NotNull String displayName, @Nullable DatabaseType databaseType,
                                @NotNull Syntax syntax, @NotNull Set<String> keywords,
                                @NotNull Set<String> functions, @NotNull Set<String> types) {

    /** Lexical switches that change how the same characters tokenize. */
    public record Syntax(boolean doubleQuoteIsString, boolean backslashEscapes, boolean backtickIdentifiers,
                         boolean hashComments, boolean slashSlashComments, boolean dashDashNeedsSpace,
                         boolean dollarQuoting, boolean nestedBlockComments, boolean escapeStrings,
                         boolean castOperator, boolean dollarParameters, boolean atVariables) {}

    public enum WordKind { KEYWORD, FUNCTION, TYPE, IDENTIFIER }

    /**
     * Type names that are also keywords where the keyword use dominates: {@code SET} (UPDATE ... SET, CHARACTER SET),
     * {@code IDENTITY} (GENERATED ... AS IDENTITY), {@code ARRAY} (the ARRAY[...] constructor) and {@code INTERVAL}
     * (the INTERVAL '1' DAY literal). Any other word of the type list is a type, whether or not it is also a keyword.
     */
    private static final Set<String> KEYWORD_FIRST_TYPES = Set.of("SET", "IDENTITY", "ARRAY", "INTERVAL");

    /**
     * Function names that are syntax words before a parenthesis (IN (...), EXISTS (...), VALUES (...), ON (...)): the
     * keyword wins even when a dialect also lists the word as a function.
     */
    private static final Set<String> KEYWORD_BEFORE_PARENTHESIS = Set.of("IN", "EXISTS", "VALUES", "ANY", "ALL", "SOME", "USING", "ON", "AS",
            "OVER", "FILTER", "WITHIN", "ROW", "ARRAY", "NOT", "AND", "OR", "KEY", "PRIMARY", "UNIQUE", "CHECK", "REFERENCES", "FOREIGN",
            "IF", "CASE", "WHEN", "THEN", "ELSE", "BETWEEN", "LIKE", "IS", "SET", "WHERE", "HAVING", "SELECT", "FROM", "JOIN");

    /**
     * Classifies an unquoted word. A type name is a type and a function name followed by a call parenthesis is a
     * function, even when the dialect also lists the word as a keyword (the two color settings would otherwise never
     * show for {@code BINARY} or {@code LEFT(}), except for the words that are syntax first (see the two lists above).
     */
    public @NotNull WordKind classify(@NotNull CharSequence word, boolean followedByParenthesis) {
        String upper = word.toString().toUpperCase(Locale.ROOT);
        boolean keyword = keywords.contains(upper);
        boolean type = types.contains(upper);
        boolean function = followedByParenthesis && functions.contains(upper);
        if (type && !(keyword && KEYWORD_FIRST_TYPES.contains(upper))) return WordKind.TYPE;
        if (function && !(keyword && KEYWORD_BEFORE_PARENTHESIS.contains(upper))) return WordKind.FUNCTION;
        if (keyword) return WordKind.KEYWORD;
        if (type) return WordKind.TYPE;
        return WordKind.IDENTIFIER;
    }
}
