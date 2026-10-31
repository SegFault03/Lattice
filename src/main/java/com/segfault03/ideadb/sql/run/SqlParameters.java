package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.completion.SqlTokenizer;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The parameters of one SQL statement, found from the dialect lexer's parameter tokens so strings, comments and
 * dollar-quoted bodies never contribute. {@code ?} is keyed by order ("1", "2", ...), {@code $n} by its number and
 * {@code :name} by the name as written (":name"). Session variables such as MySQL {@code @x} are not parameters.
 */
public final class SqlParameters {
    public static final String MIXED_STYLES = "Use either positional (?, $1) or named (:name) parameters, not both";

    public enum Style { NONE, POSITIONAL, NAMED }

    public record Occurrence(@NotNull String key, int start, int end) {}

    /** {@code keys} are distinct, in first-seen order. */
    public record Result(@NotNull Style style, @NotNull List<Occurrence> occurrences, @NotNull List<String> keys, @Nullable String error) {}

    private SqlParameters() {}

    /**
     * @param jdbcHost true for strings injected into host code that is bound through JDBC; in PostgreSQL a bare
     *                 {@code ?} is then a parameter, otherwise it is the jsonb operator ({@code ?}, {@code ?|}, {@code ?&}).
     */
    public static @NotNull Result of(@NotNull CharSequence sql, @NotNull SqlDialectProfile profile, boolean jdbcHost) {
        boolean questionMarks = jdbcHost || profile != SqlDialects.POSTGRESQL;
        List<Occurrence> occurrences = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        boolean positional = false;
        boolean named = false;
        int question = 0;
        for (SqlToken token : SqlTokenizer.tokenize(sql, profile)) {
            if (token.type() != LatticeSqlTokens.PARAMETER) continue;
            String text = token.text(sql);
            String key;
            if (text.equals("?")) {
                if (!questionMarks) continue;
                key = String.valueOf(++question);
                positional = true;
            } else if (text.charAt(0) == '$') {
                key = text.substring(1);
                positional = true;
            } else if (text.charAt(0) == ':') {
                key = text;
                named = true;
            } else {
                continue;
            }
            occurrences.add(new Occurrence(key, token.start(), token.end()));
            keys.add(key);
        }
        Style style = positional ? Style.POSITIONAL : named ? Style.NAMED : Style.NONE;
        return new Result(style, occurrences, new ArrayList<>(keys), positional && named ? MIXED_STYLES : null);
    }
}
