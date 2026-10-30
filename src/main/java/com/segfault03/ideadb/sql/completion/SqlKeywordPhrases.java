package com.segfault03.ideadb.sql.completion;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.completion.SqlContext.Position;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-word keyword phrases worth offering at each position ("GROUP BY", "LEFT JOIN"), filtered to the ones the
 * dialect accepts: LIMIT is not ANSI, FETCH FIRST is not MySQL, NULLS FIRST is not MySQL, ILIKE is not ANSI.
 * Phrases that begin a clause are offered only where that clause can follow.
 */
final class SqlKeywordPhrases {
    private SqlKeywordPhrases() {}

    /** Stands for the generic dialect, which has no database type. */
    private static final DatabaseType ANSI = null;
    private static final DatabaseType MY = DatabaseType.MYSQL, PG = DatabaseType.POSTGRESQL,
            HSQL = DatabaseType.HSQLDB, H2 = DatabaseType.H2;

    /** A phrase and the databases that accept it; none listed means every dialect, the generic one included. */
    private record Phrase(String text, DatabaseType... only) {
        boolean accepted(SqlDialectProfile profile) {
            if (only.length == 0) return true;
            for (DatabaseType type : only) if (type == profile.databaseType()) return true;
            return false;
        }
    }

    private static Phrase p(String text, DatabaseType... only) { return new Phrase(text, only); }

    private static final List<Phrase> STATEMENT = List.of(
            p("SELECT"), p("INSERT INTO"), p("UPDATE"), p("DELETE FROM"), p("WITH"), p("CREATE TABLE"), p("CREATE INDEX"),
            p("CREATE VIEW"), p("ALTER TABLE"), p("DROP TABLE"), p("DROP INDEX"), p("DROP VIEW"), p("TRUNCATE TABLE"),
            p("EXPLAIN"), p("COMMIT"), p("ROLLBACK"), p("GRANT"), p("REVOKE"),
            p("BEGIN", PG, H2, HSQL), p("START TRANSACTION", MY, PG),
            p("SHOW", MY, PG, H2), p("USE", MY), p("DESCRIBE", MY), p("REPLACE INTO", MY), p("MERGE INTO", H2, HSQL),
            p("COPY", PG), p("VACUUM", PG), p("ANALYZE", PG, MY), p("SCRIPT", H2, HSQL), p("CALL", MY, H2, HSQL));

    private static final List<Phrase> JOINS = List.of(
            p("JOIN"), p("INNER JOIN"), p("LEFT JOIN"), p("LEFT OUTER JOIN"), p("RIGHT JOIN"), p("RIGHT OUTER JOIN"),
            p("CROSS JOIN"), p("NATURAL JOIN"), p("FULL JOIN", PG, H2, HSQL), p("FULL OUTER JOIN", PG, H2, HSQL));

    private static final List<Phrase> RESULT_CLAUSES = List.of(
            p("WHERE"), p("GROUP BY"), p("HAVING"), p("ORDER BY"), p("LIMIT", MY, PG, H2, HSQL),
            p("OFFSET"), p("FETCH FIRST", ANSI, PG, H2, HSQL), p("UNION"), p("UNION ALL"),
            p("EXCEPT", PG, H2, HSQL), p("INTERSECT", PG, H2, HSQL));

    private static final List<Phrase> OPERATORS = List.of(
            p("AND"), p("OR"), p("IN"), p("NOT IN"), p("LIKE"), p("NOT LIKE"), p("BETWEEN"), p("IS NULL"), p("IS NOT NULL"),
            p("ILIKE", PG, H2), p("SIMILAR TO", PG), p("REGEXP", MY));

    private static final List<Phrase> VALUE_WORDS = List.of(
            p("NULL"), p("TRUE"), p("FALSE"), p("CASE"), p("NOT"), p("EXISTS"), p("DEFAULT"));

    private static final List<Phrase> ORDERING = List.of(
            p("ASC"), p("DESC"), p("NULLS FIRST", PG, H2, HSQL), p("NULLS LAST", PG, H2, HSQL));

    /** The phrases for a position, in the order they should be offered. */
    static @NotNull List<String> forPosition(@NotNull SqlContext context, @NotNull SqlDialectProfile profile) {
        List<Phrase> chosen = new ArrayList<>();
        boolean afterOperand = context.afterOperand();
        switch (context.position()) {
            case STATEMENT_START -> chosen.addAll(STATEMENT);
            case SELECT_LIST -> {
                if (afterOperand) { chosen.add(p("AS")); chosen.add(p("FROM")); chosen.addAll(OPERATORS); }
                else { chosen.add(p("DISTINCT")); chosen.add(p("ALL")); chosen.addAll(VALUE_WORDS); }
            }
            case AFTER_TABLE -> afterTable(context, chosen);
            case CONDITION -> {
                if (afterOperand) {
                    chosen.addAll(OPERATORS);
                    if (context.joinTarget() != null) chosen.addAll(JOINS);
                    chosen.addAll(RESULT_CLAUSES);
                } else chosen.addAll(VALUE_WORDS);
            }
            case ORDER_GROUP -> {
                if (afterOperand) { chosen.addAll(ORDERING); chosen.addAll(RESULT_CLAUSES); }
            }
            case SET_VALUE -> {
                if (afterOperand) { chosen.add(p("WHERE")); chosen.addAll(OPERATORS); }
                else chosen.addAll(VALUE_WORDS);
            }
            case VALUES -> chosen.addAll(VALUE_WORDS);
            default -> {}
        }
        List<String> accepted = new ArrayList<>();
        for (Phrase phrase : chosen) {
            if (phrase.accepted(profile) && !accepted.contains(phrase.text())) accepted.add(phrase.text());
        }
        return accepted;
    }

    private static void afterTable(SqlContext context, List<Phrase> chosen) {
        SqlContext.TableRef target = context.target();
        if (target != null) {
            if (target.introducer().equals("UPDATE")) { chosen.add(p("SET")); return; }
            chosen.add(p("VALUES")); chosen.add(p("SELECT")); chosen.add(p("DEFAULT VALUES", PG, H2, HSQL));
            return;
        }
        var tables = context.tables();
        if (!tables.isEmpty() && tables.get(tables.size() - 1).introducer().equals("JOIN")) {
            chosen.add(p("ON")); chosen.add(p("USING"));
        }
        chosen.add(p("WHERE"));
        chosen.addAll(JOINS);
        chosen.addAll(RESULT_CLAUSES);
    }

    /** Whether the position takes value expressions, where functions are useful. */
    static boolean takesFunctions(@NotNull Position position, boolean afterOperand) {
        if (afterOperand) return false;
        return switch (position) {
            case SELECT_LIST, CONDITION, ORDER_GROUP, SET_VALUE, VALUES -> true;
            default -> false;
        };
    }
}
