package com.segfault03.ideadb.sql.inject;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The method names whose first argument is SQL: JDBC, Spring's JdbcTemplate, JPA and Hibernate native queries, jOOQ
 * and Exposed. The user can edit the list. A few names are common outside SQL ({@code update}, {@code execute}) and
 * only count when the string also starts like a statement.
 */
public final class SqlKnownCalls {
    public static final String DEFAULT = String.join("\n",
            "prepareStatement", "prepareCall", "executeQuery", "executeUpdate", "executeLargeUpdate", "nativeSQL",
            "createNativeQuery", "createSQLQuery", "queryForObject", "queryForList", "queryForMap", "queryForRowSet",
            "queryForStream", "batchUpdate", "resultQuery", "query", "update", "execute", "addBatch", "exec", "fetch");

    private static final Set<String> AMBIGUOUS = Set.of("query", "update", "execute", "addbatch", "exec", "fetch");

    private final Set<String> names;

    private SqlKnownCalls(Set<String> names) { this.names = names; }

    /** One name per line; blank lines and {@code #} comments are ignored, and a qualifier ({@code Statement.execute}) is dropped. */
    public static @NotNull SqlKnownCalls parse(@NotNull String text) {
        Set<String> names = Arrays.stream(text.split("[\\r\\n,]+"))
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.substring(line.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new SqlKnownCalls(names);
    }

    public boolean contains(@NotNull String method) { return names.contains(method.toLowerCase(Locale.ROOT)); }

    /** Whether a call to this method is only SQL when its string begins like a statement. */
    public static boolean isAmbiguous(@NotNull String method) { return AMBIGUOUS.contains(method.toLowerCase(Locale.ROOT)); }
}
