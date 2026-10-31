package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.provider.DatabaseProvider;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes parameter values into SQL as dialect literals, or rewrites the SQL to JDBC placeholders. */
public final class SqlLiteralRenderer {
    public record JdbcStatement(@NotNull String sql, @NotNull List<Object> bindings) {}

    private SqlLiteralRenderer() {}

    /** Replaces every parameter occurrence with the literal for its value. */
    public static @NotNull String render(@NotNull CharSequence sql, @NotNull SqlParameters.Result params,
                                         @NotNull Map<String, SqlParameterValue> values, @NotNull DatabaseType type) {
        requireUsable(params);
        DatabaseProvider provider = DatabaseProviders.get(type);
        StringBuilder out = new StringBuilder(sql);
        List<SqlParameters.Occurrence> occurrences = params.occurrences();
        for (int i = occurrences.size() - 1; i >= 0; i--) {
            SqlParameters.Occurrence occurrence = occurrences.get(i);
            out.replace(occurrence.start(), occurrence.end(), literal(provider, valueOf(values, occurrence.key())));
        }
        return out.toString();
    }

    /**
     * Rewrites every parameter form ({@code ?}, {@code $n}, {@code :name}) to {@code ?}; pgjdbc does not accept
     * {@code $n}. Bindings follow occurrence order, so a repeated key is bound once per occurrence.
     */
    public static @NotNull JdbcStatement toJdbc(@NotNull CharSequence sql, @NotNull SqlParameters.Result params,
                                                @NotNull Map<String, SqlParameterValue> values) {
        requireUsable(params);
        StringBuilder out = new StringBuilder(sql);
        List<SqlParameters.Occurrence> occurrences = params.occurrences();
        for (int i = occurrences.size() - 1; i >= 0; i--) {
            SqlParameters.Occurrence occurrence = occurrences.get(i);
            out.replace(occurrence.start(), occurrence.end(), "?");
        }
        List<Object> bindings = new ArrayList<>(occurrences.size());
        for (SqlParameters.Occurrence occurrence : occurrences) bindings.add(valueOf(values, occurrence.key()).jdbcValue());
        return new JdbcStatement(out.toString(), bindings);
    }

    private static void requireUsable(SqlParameters.Result params) {
        if (params.error() != null) throw new IllegalArgumentException(params.error());
    }

    private static SqlParameterValue valueOf(Map<String, SqlParameterValue> values, String key) {
        SqlParameterValue value = values.get(key);
        if (value == null) throw new IllegalArgumentException("No value for parameter " + key);
        String error = value.validationError();
        if (error != null) throw new IllegalArgumentException("Parameter " + key + ": " + error);
        return value;
    }

    private static String literal(DatabaseProvider provider, SqlParameterValue value) {
        return switch (value.type()) {
            case TEXT -> provider.sqlLiteral(value.text(), "VARCHAR");
            case NUMBER -> provider.sqlLiteral(value.jdbcValue(), "NUMERIC");
            case BOOLEAN -> provider.sqlLiteral(value.jdbcValue(), "BOOLEAN");
            case DATE -> "DATE '" + value.text() + "'";
            case TIMESTAMP -> "TIMESTAMP '" + value.text() + "'";
            case NULL -> "NULL";
        };
    }
}
