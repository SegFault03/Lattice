package com.segfault03.ideadb.sql.inject;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The comments and annotation values with which a user states that a string is, or is not, SQL. */
public final class SqlMarkers {
    /** What a comment above a statement says about the strings in it. */
    public enum Mark { NONE, SQL, NOT_SQL }

    private static final Pattern LANGUAGE = Pattern.compile("(?i)\\blanguage\\s*=\\s*([\\w.]+)");
    private static final Pattern NOT_SQL = Pattern.compile("(?i)\\blattice\\s*:\\s*not-sql\\b");
    private static final Set<String> SQL_NAMES = Set.of("sql", "mysql", "postgresql", "postgres", "h2", "hsqldb", "hsql", "mariadb", "sqlite", "tsql", "plsql", "oracle");

    /** The comment text written by the "not SQL" intention. */
    public static final String NOT_SQL_COMMENT = "lattice:not-sql";
    /** The comment text written by the "treat as SQL" intention. */
    public static final String SQL_COMMENT = "language=SQL";

    private SqlMarkers() {}

    public static @NotNull Mark ofComment(@NotNull String comment) {
        if (NOT_SQL.matcher(comment).find()) return Mark.NOT_SQL;
        Matcher language = LANGUAGE.matcher(comment);
        return language.find() && isSqlLanguage(language.group(1)) ? Mark.SQL : Mark.NONE;
    }

    /** Whether the value of {@code @Language("...")} names SQL or one of the SQL dialects. */
    public static boolean isSqlLanguage(@NotNull String name) { return SQL_NAMES.contains(name.strip().toLowerCase(Locale.ROOT)); }
}
