package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;

import java.util.*;

/** The syntax checks on one statement: unterminated tokens, brackets, and the shape of queries. They need no schema. */
final class SqlShapeChecks {
    private static final Set<String> STATEMENT_WORDS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TRUNCATE",
            "WITH", "EXPLAIN", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT", "VALUES", "REPLACE", "MERGE");
    private static final List<String> SELECT_ORDER = List.of("SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT");
    private static final Set<String> COMPARISONS = Set.of("=", "<>", "!=", "<", ">", "<=", ">=");
    private static final Set<String> POSITION_FUNCTIONS = Set.of("SUBSTRING", "POSITION");
    private static final Set<String> LIST_BREAKERS = Set.of("SELECT", "WHERE", "BY", "SET", "ON", "FROM");
    private static final Set<String> CONDITION_WORDS = Set.of("WHERE", "HAVING", "AND", "OR");
    private static final Set<String> TABLE_NAME_STOPPERS = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "ON");
    private static final Set<String> INSERT_SOURCES = Set.of("VALUES", "SELECT", "SET", "DEFAULT", "WITH", "TABLE", "VALUE");
    /** The letters the lexer accepts before a quote: escape, national, bit and hex strings. */
    private static final String STRING_PREFIXES = "eEnNbBxX";

    private final SqlStatementContext c;
    private final CharSequence text;
    private final List<SqlToken> s;
    private final int n;
    private final String kind;
    /** The last statement of the script and not ended by ';': the user may still be typing it. */
    private final boolean typing;
    private int[] joinMarker, selectListMarker;

    SqlShapeChecks(SqlStatementContext context) {
        this.c = context; this.text = context.text(); this.s = context.s; this.n = context.n; this.kind = context.kind; this.typing = context.typing;
    }

    /** Checks that need only the statement's tokens. */
    void tokens() {
        lexical();
        startWord();
        parentheses();
    }

    void lexical() {
        for (int i = 0; i < n; i++) {
            c.tick();
            var type = s.get(i).type();
            String raw = s.get(i).text(text);
            if (type == LatticeSqlTokens.STRING && !terminatedString(raw)) c.report(Kind.SYNTAX, i, i, "Unterminated string literal");
            if (type == LatticeSqlTokens.QUOTED_IDENTIFIER && (raw.length() < 2 || raw.charAt(raw.length() - 1) != raw.charAt(0))) {
                c.report(Kind.SYNTAX, i, i, "Unterminated quoted name");
            }
        }
    }

    boolean terminatedString(String raw) {
        if (raw.isEmpty()) return false;
        if (raw.charAt(0) == '$') {
            int tagEnd = raw.indexOf('$', 1);
            if (tagEnd < 0) return false;
            String tag = raw.substring(0, tagEnd + 1);
            return raw.length() >= 2 * tag.length() && raw.endsWith(tag);
        }
        int open = STRING_PREFIXES.indexOf(raw.charAt(0)) >= 0 ? 1 : 0;
        if (open >= raw.length()) return true;
        char quote = raw.charAt(open);
        return raw.length() - open >= 2 && raw.charAt(raw.length() - 1) == quote;
    }

    /** "selct * from t": a first word that is not a statement but is one letter away from one. */
    void startWord() {
        if (n == 0 || s.get(0).type() != LatticeSqlTokens.IDENTIFIER) return;
        String first = c.up(0);
        if (first.length() < 4 || STATEMENT_WORDS.contains(first)) return; // a real statement word the dialect's list lacks (MERGE)
        for (String candidate : STATEMENT_WORDS) {
            if (Math.abs(candidate.length() - first.length()) <= 1 && SqlEditDistance.within(first, candidate, 1)) {
                boolean lower = text.charAt(s.get(0).start()) >= 'a';
                c.report(Kind.SYNTAX, 0, 0, "Unknown statement '" + s.get(0).text(text) + "'. Did you mean " + candidate + "?",
                        lower ? candidate.toLowerCase(Locale.ROOT) : candidate);
                return;
            }
        }
    }

    void parentheses() {
        Deque<Integer> opened = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            c.tick();
            if (c.punct(i, '(')) opened.push(i);
            else if (c.punct(i, ')')) {
                if (opened.isEmpty()) c.report(Kind.SYNTAX, i, i, "Unmatched ')'");
                else opened.pop();
            }
        }
        for (int open : opened) c.report(Kind.SYNTAX, open, open, "Unclosed '('");
    }

    void shape() {
        selectBlocks();
        separators();
        incompleteExpressions();
        missingComma();
    }

    /** Clause order and repeats within one query: WHERE after GROUP BY, two FROMs, an empty select list. */
    void selectBlocks() {
        Map<Long, Integer> lastRank = new HashMap<>();
        for (int i = 0; i < n; i++) {
            c.tick();
            if (s.get(i).type() != LatticeSqlTokens.KEYWORD) continue;
            String word = c.up(i);
            int rank = SELECT_ORDER.indexOf(word);
            if (rank < 0) continue;
            if ((word.equals("GROUP") || word.equals("ORDER")) && !c.keyword(i + 1, "BY")) continue;
            if (word.equals("FROM") && (c.keyword(i - 1, "DISTINCT") || insideFieldFunction(c.structure.blockOf()[i]))) continue;
            long key = (long) c.structure.blockOf()[i] << 32 | c.segment[i];
            if (word.equals("SELECT")) {
                lastRank.put(key, 0);
                if (selectListEmpty(i) && !(typing && !c.in(afterSelect(i)))) c.report(Kind.SYNTAX, i, i, "Select list expected after SELECT");
                continue;
            }
            if (!lastRank.containsKey(key)) continue; // not a SELECT: DELETE FROM, UPDATE ... WHERE
            int previous = lastRank.get(key);
            if (rank < previous) c.report(Kind.SYNTAX, i, i, word + " cannot come after " + SELECT_ORDER.get(previous));
            else if (rank == previous && rank > 0) c.report(Kind.SYNTAX, i, i, "Duplicate " + word);
            lastRank.put(key, Math.max(rank, previous));
        }
    }

    boolean selectListEmpty(int select) {
        int at = afterSelect(select);
        return !c.in(at) || c.clauseStart(at) || c.punct(at, ')');
    }

    /** The first token of the select list, after DISTINCT or ALL. */
    int afterSelect(int select) {
        int at = select + 1;
        while (c.keyword(at, "DISTINCT") || c.keyword(at, "ALL")) at++;
        return at;
    }

    /** FROM inside EXTRACT(), TRIM(), SUBSTRING()... is part of the function's syntax. */
    boolean insideFieldFunction(int block) {
        if (block == 0) return false;
        int open = c.structure.open().get(block);
        return open > 0 && c.isWord(open - 1) && (SqlStatementContext.FIELD_FUNCTIONS.contains(c.up(open - 1)) || POSITION_FUNCTIONS.contains(c.up(open - 1)));
    }

    /** ",," and a comma before a clause or a closing bracket. */
    void separators() {
        for (int i = 0; i < n; i++) {
            c.tick();
            if (!c.punct(i, ',')) continue;
            if (c.punct(i + 1, ',')) c.report(Kind.SYNTAX, i + 1, i + 1, "Unexpected ','");
            else if (!c.in(i + 1) && typing) continue;
            else if (!c.in(i + 1) || c.punct(i + 1, ')') || c.clauseStart(i + 1)) c.report(Kind.SYNTAX, i, i, "Expression expected after ','");
            else if (i == 0 || c.punct(i - 1, '(') || clauseStartOrKeyword(i - 1)) c.report(Kind.SYNTAX, i, i, "Unexpected ','");
        }
    }

    boolean clauseStartOrKeyword(int i) {
        return s.get(i).type() == LatticeSqlTokens.KEYWORD && LIST_BREAKERS.contains(c.up(i));
    }

    /** A condition, comparison or list that stops where something has to follow. */
    void incompleteExpressions() {
        for (int i = 0; i < n; i++) {
            c.tick();
            String word = s.get(i).type() == LatticeSqlTokens.KEYWORD ? c.up(i) : "";
            boolean comparison = s.get(i).type() == LatticeSqlTokens.OPERATOR && COMPARISONS.contains(c.up(i));
            boolean listClause = word.equals("BY") && (c.keyword(i - 1, "ORDER") || c.keyword(i - 1, "GROUP"));
            boolean needsOperand = comparison || listClause || CONDITION_WORDS.contains(word) || word.equals("ON") && inJoin(i);
            if (!needsOperand) continue;
            boolean doubledLogic = (word.equals("AND") || word.equals("OR")) && (c.keyword(i + 1, "AND") || c.keyword(i + 1, "OR"));
            if (!c.in(i + 1) && typing) continue; // the operand is the next thing to be typed
            if (c.endsExpression(i + 1) || doubledLogic) c.report(Kind.SYNTAX, i, i, "Expression expected after " + (word.isEmpty() ? s.get(i).text(text) : word));
        }
        // FROM, JOIN and a statement's target need a table
        for (int i = 0; i < n; i++) {
            c.tick();
            if (s.get(i).type() != LatticeSqlTokens.KEYWORD) continue;
            String word = c.up(i);
            boolean introduces = word.equals("JOIN") || word.equals("FROM") && !c.keyword(i - 1, "DISTINCT") && !insideFieldFunction(c.structure.blockOf()[i])
                    || word.equals("UPDATE") && i == 0 || word.equals("INTO") && kind.equals("INSERT");
            if (introduces && !(typing && !c.in(i + 1)) && (!c.in(i + 1) || c.punct(i + 1, ')') || c.punct(i + 1, ',') || c.keywordIn(i + 1, TABLE_NAME_STOPPERS))) {
                c.report(Kind.SYNTAX, i, i, "Table name expected after " + word);
            }
        }
        insertWithoutSource();
        updateWithoutSet();
    }

    void insertWithoutSource() {
        if (!kind.equals("INSERT") || n < 3 || typing) return;
        for (int i = 0; i < n; i++) {
            c.tick();
            if (c.keywordIn(i, INSERT_SOURCES)) return;
            if (c.punct(i, '(') && c.keyword(i + 1, "SELECT")) return;
        }
        c.report(Kind.SYNTAX, n - 1, n - 1, "VALUES or SELECT expected");
    }

    void updateWithoutSet() {
        if (!kind.equals("UPDATE") || n < 2 || typing) return;
        for (int i = 0; i < n; i++) if (c.keyword(i, "SET")) return;
        c.report(Kind.SYNTAX, n - 1, n - 1, "SET expected");
    }

    boolean inJoin(int on) {
        if (joinMarker == null) joinMarker = c.nearestBefore(i -> c.keyword(i, "JOIN") || c.clauseStart(i));
        int k = joinMarker[on];
        return k >= 0 && c.keyword(k, "JOIN");
    }

    /** "select a b c from t": a name, an alias, then another name. */
    void missingComma() {
        for (int i = 2; i < n; i++) {
            c.tick();
            if (!(c.ident(i) && c.ident(i - 1) && c.ident(i - 2))) continue;
            if (c.punct(i + 1, '.') || c.punct(i + 1, '(') || c.punct(i - 1, '.') || c.punct(i - 2, '.') || c.punct(i - 3, '.')) continue;
            if (!inSelectList(i) || SqlSafeWords.contains(c.name(i)) || SqlSafeWords.contains(c.name(i - 1)) || SqlSafeWords.contains(c.name(i - 2))) continue;
            c.report(Kind.SYNTAX, i, i, "Missing ',' before " + s.get(i).text(text));
        }
    }

    boolean inSelectList(int i) {
        if (selectListMarker == null) {
            selectListMarker = c.nearestBefore(k -> c.keyword(k, "SELECT") || c.clauseStart(k) || c.keyword(k, "ON") || c.keyword(k, "SET") || c.keyword(k, "BY"));
        }
        int k = selectListMarker[i];
        return k >= 0 && c.keyword(k, "SELECT");
    }
}
