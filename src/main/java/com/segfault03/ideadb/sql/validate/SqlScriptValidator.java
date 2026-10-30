package com.segfault03.ideadb.sql.validate;

import com.intellij.openapi.progress.ProgressManager;
import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.completion.SqlTokenizer;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Checks a SQL script locally: the statements' shape, and, against the cached schema, their tables and columns. No
 * database is contacted.
 *
 * <p>Every check is built to stay quiet when it is not sure. A statement is skipped when it contains template syntax
 * ({@code ${...}}, {@code {{...}}}) and the rest of the file is skipped after a stored routine or a DELIMITER change,
 * where one statement spans several semicolons. A name is checked against the schema only when every table the query
 * can see is a real table whose columns are known; a derived table, a CTE, a function that returns rows or a table
 * the script itself creates or alters makes the query opaque for its unqualified names. Words of the SQL standard that
 * a dialect's word list lacks are never called columns.
 */
public final class SqlScriptValidator {
    /** Larger scripts are not validated: such a file is a dump, and checking it on every edit costs more than it helps. */
    public static final int MAX_LENGTH = 200_000;

    private static final Set<String> QUERY_STATEMENTS = Set.of("SELECT", "WITH", "VALUES", "INSERT", "REPLACE", "UPDATE", "DELETE");

    private SqlScriptValidator() {}

    public static @NotNull List<SqlDiagnostic> validate(@NotNull CharSequence text, @NotNull SqlDialectProfile profile, @Nullable SqlSchema schema) {
        return validate(text, profile, schema, true);
    }

    /**
     * @param lastMayBeTyped whether a last statement with no ';' after it may be one the user is still typing, so a missing
     *                       final piece is not reported. True for script files; false for SQL in a string, which is complete.
     */
    public static @NotNull List<SqlDiagnostic> validate(@NotNull CharSequence text, @NotNull SqlDialectProfile profile, @Nullable SqlSchema schema,
                                                        boolean lastMayBeTyped) {
        if (text.length() > MAX_LENGTH) return List.of();
        List<SqlToken> all = SqlTokenizer.tokenize(text, profile);
        List<SqlDiagnostic> out = new ArrayList<>();
        List<SqlToken> code = new ArrayList<>();
        for (SqlToken token : all) {
            if (token.type() == LatticeSqlTokens.LINE_COMMENT) continue;
            if (token.type() == LatticeSqlTokens.BLOCK_COMMENT) {
                String comment = token.text(text);
                if (comment.length() < 4 || !comment.endsWith("*/")) {
                    out.add(new SqlDiagnostic(Kind.SYNTAX, token.start(), Math.min(token.end(), token.start() + 2), "Unterminated comment"));
                }
                continue;
            }
            code.add(token);
        }

        List<List<SqlToken>> statements = split(code, text);
        SqlScriptFacts script = new SqlScriptFacts(text, profile, schema, statements);
        for (int i = 0; i < statements.size(); i++) {
            List<SqlToken> statement = statements.get(i);
            ProgressManager.checkCanceled();
            if (statement.isEmpty()) continue;
            if (script.opaque(statement)) break;
            if (script.templated(statement)) continue;
            // the last statement has no ';' after it: it may be what the user is typing
            check(script, statement, out, lastMayBeTyped && i == statements.size() - 1);
        }
        out.sort(Comparator.comparingInt(SqlDiagnostic::start));
        return out;
    }

    private static List<List<SqlToken>> split(List<SqlToken> code, CharSequence text) {
        List<List<SqlToken>> statements = new ArrayList<>();
        List<SqlToken> current = new ArrayList<>();
        for (SqlToken token : code) {
            if (token.type() == LatticeSqlTokens.PUNCTUATION && text.charAt(token.start()) == ';') {
                statements.add(current);
                current = new ArrayList<>();
            } else current.add(token);
        }
        statements.add(current);
        return statements;
    }

    private static void check(SqlScriptFacts script, List<SqlToken> statement, List<SqlDiagnostic> out, boolean typing) {
        var context = new SqlStatementContext(script, statement, out, typing);
        var shape = new SqlShapeChecks(context);
        shape.tokens();
        if (!QUERY_STATEMENTS.contains(context.kind)) return;
        context.analyzeQuery();
        shape.shape();
        new SqlNameChecks(script, context).run();
    }
}
