package com.segfault03.ideadb.sql.completion;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * When a name must be quoted to mean what it says. Unquoted names fold to lower case in PostgreSQL and to upper case
 * in H2 and HSQLDB, so a name stored in another case needs quotes there; MySQL and generic SQL keep case as written.
 */
public final class SqlIdentifiers {
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private SqlIdentifiers() {}

    /** Whether the name cannot be written bare whatever the dialect folds to: odd characters or a reserved word. */
    public static boolean isUnwritableBare(@NotNull String name, @NotNull SqlDialectProfile profile) {
        return !PLAIN.matcher(name).matches() || profile.keywords().contains(name.toUpperCase(Locale.ROOT));
    }

    public static boolean needsQuotes(@NotNull String name, @NotNull SqlDialectProfile profile) {
        if (isUnwritableBare(name, profile)) return true;
        DatabaseType type = profile.databaseType();
        if (type == null) return false;
        return switch (type) {
            case POSTGRESQL -> !name.equals(name.toLowerCase(Locale.ROOT));
            case H2, HSQLDB -> !name.equals(name.toUpperCase(Locale.ROOT));
            case MYSQL -> false;
        };
    }

    /** Quotes with the dialect's identifier quote, doubling any quote inside the name. */
    public static @NotNull String quote(@NotNull String name, @NotNull SqlDialectProfile profile) {
        String quote = profile.syntax().backtickIdentifiers() ? "`" : "\"";
        return quote + name.replace(quote, quote + quote) + quote;
    }

    /** The text to insert for a name: quoted only when it has to be. */
    public static @NotNull String forInsertion(@NotNull String name, @NotNull SqlDialectProfile profile) {
        return needsQuotes(name, profile) ? quote(name, profile) : name;
    }

    /**
     * Repeats a name the statement itself uses, such as an alias. The statement's own spelling already resolves, so
     * folding is not a reason to quote it; only a name that could not be written bare is quoted.
     */
    public static @NotNull String forReference(@NotNull String name, @NotNull SqlDialectProfile profile) {
        return isUnwritableBare(name, profile) ? quote(name, profile) : name;
    }
}
