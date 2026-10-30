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

    /** Classifies an unquoted word. A function name only counts as one when a call parenthesis follows. */
    public @NotNull WordKind classify(@NotNull CharSequence word, boolean followedByParenthesis) {
        String upper = word.toString().toUpperCase(Locale.ROOT);
        if (keywords.contains(upper)) return WordKind.KEYWORD;
        if (followedByParenthesis && functions.contains(upper)) return WordKind.FUNCTION;
        if (types.contains(upper)) return WordKind.TYPE;
        return WordKind.IDENTIFIER;
    }
}
