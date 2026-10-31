package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.completion.SqlContext.TableRef;
import com.segfault03.ideadb.sql.completion.SqlStatementAnalyzer;
import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.completion.SqlTokenizer;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a statement does, for Explain and for Run and Roll Back. Classification reads the dialect's tokens, so keywords
 * in strings and comments never count. MySQL and MariaDB execute the body of {@code /*!} and {@code /*M!} comments, so in
 * every dialect a statement containing one is {@link #OTHER}. Anything that could commit or change the schema is {@link #OTHER}.
 */
public enum SqlStatementKind {
    QUERY, DML, OTHER;

    private static final String EXPLAIN_REFUSAL = "Only queries and DML can be explained.";
    private static final String ROLLBACK_QUERY_REFUSAL = "Run and Roll Back is for INSERT, UPDATE, DELETE and MERGE. Use Explain for queries.";
    private static final String ROLLBACK_OTHER_REFUSAL =
            "Run and Roll Back runs only INSERT, UPDATE, DELETE and MERGE; this statement could commit or change the schema.";

    public static @NotNull SqlStatementKind of(@NotNull CharSequence sql, @NotNull SqlDialectProfile profile) {
        return parse(sql, profile).kind;
    }

    /**
     * The unquoted, optionally schema-qualified names of the tables the statement reads or writes, distinct and in
     * first-seen order. WITH names and derived tables are not tables.
     */
    public static @NotNull List<String> targetTables(@NotNull CharSequence sql, @NotNull SqlDialectProfile profile) {
        Parsed parsed = parse(sql, profile);
        Set<String> names = new LinkedHashSet<>();
        for (TableRef ref : SqlStatementAnalyzer.structure(sql, parsed.tokens, profile).refs()) {
            if (ref.derived() || ref.name() == null || ref.name().isEmpty()) continue;
            String namespace = ref.namespace() == null || ref.namespace().isEmpty() ? null : ref.namespace();
            if (namespace == null && parsed.cteNames.contains(ref.name().toLowerCase(Locale.ROOT))) continue;
            names.add(namespace == null ? ref.name() : namespace + "." + ref.name());
        }
        return new ArrayList<>(names);
    }

    public static @Nullable String explainRefusal(@NotNull SqlStatementKind kind) {
        return kind == OTHER ? EXPLAIN_REFUSAL : null;
    }

    public static @Nullable String rollbackRefusal(@NotNull SqlStatementKind kind) {
        return switch (kind) {
            case QUERY -> ROLLBACK_QUERY_REFUSAL;
            case OTHER -> ROLLBACK_OTHER_REFUSAL;
            case DML -> null;
        };
    }

    /** The classification with the tokens it was read from and the WITH names the statement defines (lower case). */
    private record Parsed(@NotNull SqlStatementKind kind, @NotNull List<SqlToken> tokens, @NotNull Set<String> cteNames) {}

    private static @NotNull Parsed parse(CharSequence sql, SqlDialectProfile profile) {
        List<SqlToken> tokens = SqlTokenizer.tokenize(sql, profile);
        List<SqlToken> code = new ArrayList<>();
        Set<String> cteNames = new HashSet<>();
        for (SqlToken token : tokens) {
            if (token.type() == LatticeSqlTokens.BLOCK_COMMENT && isExecutableComment(sql, token)) {
                return new Parsed(OTHER, tokens, cteNames);
            }
            if (!LatticeSqlTokens.COMMENTS.contains(token.type())) code.add(token);
        }
        return new Parsed(classify(sql, code, profile, cteNames), tokens, cteNames);
    }

    private static boolean isExecutableComment(CharSequence sql, SqlToken token) {
        String text = token.text(sql);
        return text.startsWith("/*!") || text.startsWith("/*M!");
    }

    private static @NotNull SqlStatementKind classify(CharSequence sql, List<SqlToken> code, SqlDialectProfile profile, Set<String> cteNames) {
        int end = code.size();
        for (int i = 0; i < code.size(); i++) {
            if (!isPunct(sql, code.get(i), ';')) continue;
            // One statement only: a second one could be anything.
            if (i + 1 < code.size()) return OTHER;
            end = i;
        }
        List<SqlToken> body = code.subList(0, end);
        int at = 0;
        boolean dataModifyingCte = false;
        if (word(sql, body, 0).equals("WITH")) {
            at = 1;
            if (word(sql, body, at).equals("RECURSIVE")) at++;
            while (true) {
                if (at >= body.size() || !isName(body.get(at))) return OTHER;
                cteNames.add(unquote(body.get(at).text(sql)).toLowerCase(Locale.ROOT));
                at++;
                if (isPunct(sql, body, at, '(')) {
                    at = closing(sql, body, at);
                    if (at < 0) return OTHER;
                    at++;
                }
                if (!word(sql, body, at).equals("AS")) return OTHER;
                at++;
                if (word(sql, body, at).equals("NOT")) at++;
                if (word(sql, body, at).equals("MATERIALIZED")) at++;
                if (!isPunct(sql, body, at, '(')) return OTHER;
                int close = closing(sql, body, at);
                if (close < 0) return OTHER;
                if (isDmlVerb(word(sql, body, at + 1), profile)) dataModifyingCte = true;
                at = close + 1;
                if (isPunct(sql, body, at, ',')) { at++; continue; }
                break;
            }
        }
        String verb = word(sql, body, at);
        switch (verb) {
            case "SELECT", "VALUES", "TABLE" -> {
                if (hasTopLevelInto(sql, body, at)) return OTHER;
                return dataModifyingCte ? DML : QUERY;
            }
            default -> {
                return isDmlVerb(verb, profile) ? DML : OTHER;
            }
        }
    }

    private static boolean isDmlVerb(String verb, SqlDialectProfile profile) {
        return switch (verb) {
            case "INSERT", "UPDATE", "DELETE", "MERGE" -> true;
            case "REPLACE" -> profile.databaseType() == DatabaseType.MYSQL;
            default -> false;
        };
    }

    /** SELECT ... INTO creates tables, or writes files or variables. */
    private static boolean hasTopLevelInto(CharSequence sql, List<SqlToken> tokens, int from) {
        int depth = 0;
        for (int i = from; i < tokens.size(); i++) {
            SqlToken token = tokens.get(i);
            if (isPunct(sql, token, '(')) depth++;
            else if (isPunct(sql, token, ')')) depth--;
            else if (depth == 0 && word(sql, tokens, i).equals("INTO")) return true;
        }
        return false;
    }

    /** The index of the ")" matching the "(" at {@code open}, or -1 when unbalanced. */
    private static int closing(CharSequence sql, List<SqlToken> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (isPunct(sql, tokens.get(i), '(')) depth++;
            else if (isPunct(sql, tokens.get(i), ')') && --depth == 0) return i;
        }
        return -1;
    }

    /** The upper-case text of a keyword or bare identifier token, else the empty string. */
    private static @NotNull String word(CharSequence sql, List<SqlToken> tokens, int i) {
        if (i < 0 || i >= tokens.size()) return "";
        SqlToken token = tokens.get(i);
        if (token.type() != LatticeSqlTokens.KEYWORD && token.type() != LatticeSqlTokens.IDENTIFIER) return "";
        return token.text(sql).toUpperCase(Locale.ROOT);
    }

    private static boolean isName(SqlToken token) {
        return token.type() == LatticeSqlTokens.KEYWORD || LatticeSqlTokens.NAMES.contains(token.type());
    }

    private static boolean isPunct(CharSequence sql, List<SqlToken> tokens, int i, char c) {
        return i >= 0 && i < tokens.size() && isPunct(sql, tokens.get(i), c);
    }

    private static boolean isPunct(CharSequence sql, SqlToken token, char c) {
        return token.type() == LatticeSqlTokens.PUNCTUATION && token.end() - token.start() == 1 && sql.charAt(token.start()) == c;
    }

    private static @NotNull String unquote(String name) {
        if (name.length() >= 2) {
            char open = name.charAt(0);
            char close = name.charAt(name.length() - 1);
            if ((open == '"' || open == '`') && close == open) return name.substring(1, name.length() - 1).replace("" + open + open, "" + open);
            if (open == '[' && close == ']') return name.substring(1, name.length() - 1);
        }
        return name;
    }
}
