package com.segfault03.ideadb.sql.validate;

import com.intellij.openapi.progress.ProgressManager;
import com.segfault03.ideadb.sql.completion.SqlContext.TableRef;
import com.segfault03.ideadb.sql.completion.SqlStatementAnalyzer;
import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.completion.SqlTokens;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.IntPredicate;

/**
 * One statement's tokens and what is worked out from them once: the query structure, the segments between set operators,
 * the matching brackets and the nearest earlier marker of a group. The checks of {@link SqlShapeChecks} and
 * {@link SqlNameChecks} read it and report their findings through it.
 */
final class SqlStatementContext extends SqlTokens {
    /** Functions whose first argument names a field or unit, not a column. */
    static final Set<String> FIELD_FUNCTIONS = Set.of("EXTRACT", "DATE_PART", "DATE_TRUNC", "DATEADD", "DATEDIFF", "TIMESTAMPADD", "TIMESTAMPDIFF", "TRIM", "OVERLAY");
    private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS");
    private static final Set<String> CLAUSE_STARTS = Set.of("FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "UNION", "INTERSECT", "EXCEPT",
            "OFFSET", "FETCH", "WINDOW", "RETURNING");
    /** Statements are checked for cancellation once per this many tokens. */
    private static final int CANCEL_CHECK_INTERVAL = 1024;

    final SqlScriptFacts script;
    final CharSequence text;
    final List<SqlToken> s;
    final List<SqlDiagnostic> out;
    final SqlDialectProfile profile;
    final int n;
    final String kind;
    /** The last statement of the script and not ended by ';': the user may still be typing it. */
    final boolean typing;
    SqlStatementAnalyzer.Structure structure;
    /** For each token, how many set operators (UNION...) came before it in its group. */
    int[] segment;
    private int ticks;
    /** The ")" that closes each "(" (the last token when it never closes), -1 elsewhere. */
    private int[] closeOf;

    SqlStatementContext(SqlScriptFacts script, List<SqlToken> statement, List<SqlDiagnostic> out, boolean typing) {
        super(statement, script.text);
        this.script = script; this.text = script.text; this.s = statement; this.out = out; this.profile = script.profile;
        this.typing = typing;
        this.n = statement.size();
        this.kind = firstWord();
    }

    String firstWord() {
        if (n == 0) return "";
        if (punct(0, '(')) { for (int i = 1; i < n; i++) if (punct(i, '(')) continue; else return isWord(i) ? up(i) : ""; }
        return isWord(0) ? up(0) : "";
    }

    /** Called once per token of a loop: long statements stay cancellable. */
    void tick() { if (++ticks % CANCEL_CHECK_INTERVAL == 0) ProgressManager.checkCanceled(); }

    String name(int i) { return script.unquote(s.get(i)); }

    /** Works out the structure and segments of a query statement. */
    void analyzeQuery() {
        structure = withoutNonTables(SqlStatementAnalyzer.structure(text, s, profile));
        computeSegments();
    }

    private void computeSegments() {
        segment = new int[n];
        int[] counters = new int[structure.open().size()];
        for (int i = 0; i < n; i++) {
            tick();
            int block = structure.blockOf()[i];
            if (s.get(i).type() == LatticeSqlTokens.KEYWORD && SET_OPERATORS.contains(up(i))) counters[block]++;
            segment[i] = counters[block];
        }
    }

    void report(Kind k, int fromToken, int toToken, String message) { report(k, fromToken, toToken, message, null); }
    void report(Kind k, int fromToken, int toToken, String message, @Nullable String replacement) {
        out.add(new SqlDiagnostic(k, s.get(fromToken).start(), s.get(toToken).end(), message, replacement));
    }

    private SqlStatementAnalyzer.Structure withoutNonTables(SqlStatementAnalyzer.Structure found) {
        List<TableRef> tables = new ArrayList<>();
        for (TableRef ref : found.refs()) {
            boolean notATable = ref.introducer().equals("UPDATE") && ref.introducerIndex() > 0
                    && (word(ref.introducerIndex() - 1, "KEY") || word(ref.introducerIndex() - 1, "DO") || word(ref.introducerIndex() - 1, "FOR"));
            if (!notATable) tables.add(ref);
        }
        return new SqlStatementAnalyzer.Structure(found.blockOf(), found.parent(), found.open(), tables);
    }

    boolean clauseStart(int i) {
        return in(i) && s.get(i).type() == LatticeSqlTokens.KEYWORD && CLAUSE_STARTS.contains(up(i));
    }

    boolean endsExpression(int i) { return !in(i) || punct(i, ')') || punct(i, ',') || clauseStart(i); }

    /**
     * For each token, the nearest earlier token of the same group that satisfies {@code marker}, or -1: one pass over
     * the statement answers every backward search that stops at such a token.
     */
    int[] nearestBefore(IntPredicate marker) {
        int[] blockOf = structure.blockOf();
        int[] last = new int[structure.open().size()];
        Arrays.fill(last, -1);
        int[] found = new int[n];
        for (int i = 0; i < n; i++) {
            tick();
            found[i] = last[blockOf[i]];
            if (marker.test(i)) last[blockOf[i]] = i;
        }
        return found;
    }

    int closing(int open) {
        if (closeOf == null) {
            closeOf = new int[n];
            Arrays.fill(closeOf, n - 1);
            Deque<Integer> opened = new ArrayDeque<>();
            for (int k = 0; k < n; k++) {
                if (punct(k, '(')) opened.push(k);
                else if (punct(k, ')') && !opened.isEmpty()) closeOf[opened.pop()] = k;
            }
        }
        return closeOf[open];
    }
}
