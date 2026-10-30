package com.segfault03.ideadb.sql.validate;

import com.intellij.openapi.progress.ProgressManager;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.completion.SqlContext.TableRef;
import com.segfault03.ideadb.sql.completion.SqlStatementAnalyzer;
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

    private static final Set<String> STATEMENT_WORDS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TRUNCATE",
            "WITH", "EXPLAIN", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT", "VALUES", "REPLACE", "MERGE");
    private static final Set<String> QUERY_STATEMENTS = Set.of("SELECT", "WITH", "VALUES", "INSERT", "REPLACE", "UPDATE", "DELETE");
    private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS");
    private static final Set<String> CLAUSE_STARTS = Set.of("FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "UNION", "INTERSECT", "EXCEPT", "OFFSET", "FETCH", "WINDOW", "RETURNING");
    private static final List<String> SELECT_ORDER = List.of("SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT");
    private static final Set<String> COMPARISONS = Set.of("=", "<>", "!=", "<", ">", "<=", ">=");
    private static final Set<String> TRAILING_QUALIFIERS = Set.of("EXCLUDED", "NEW", "OLD", "INSERTED", "DELETED", "VALUES");
    /** Functions whose first argument names a field or unit, not a column. */
    private static final Set<String> FIELD_FUNCTIONS = Set.of("EXTRACT", "DATE_PART", "DATE_TRUNC", "DATEADD", "DATEDIFF", "TIMESTAMPADD", "TIMESTAMPDIFF", "TRIM", "OVERLAY");

    private SqlScriptValidator() {}

    public static @NotNull List<SqlDiagnostic> validate(@NotNull CharSequence text, @NotNull SqlDialectProfile profile, @Nullable SqlSchema schema) {
        if (text.length() > MAX_LENGTH) return List.of();
        List<SqlToken> all = SqlTokenizer.tokenize(text, profile);
        List<SqlDiagnostic> out = new ArrayList<>();
        List<SqlToken> code = new ArrayList<>();
        for (SqlToken token : all) {
            if (token.type() == LatticeSqlTokens.LINE_COMMENT) continue;
            if (token.type() == LatticeSqlTokens.BLOCK_COMMENT) {
                String comment = token.text(text);
                if (comment.length() < 4 || !comment.endsWith("*/")) out.add(new SqlDiagnostic(Kind.SYNTAX, token.start(), Math.min(token.end(), token.start() + 2), "Unterminated comment"));
                continue;
            }
            code.add(token);
        }

        List<List<SqlToken>> statements = split(code, text);
        Script script = new Script(text, profile, schema, statements);
        for (List<SqlToken> statement : statements) {
            ProgressManager.checkCanceled();
            if (statement.isEmpty()) continue;
            if (script.opaque(statement)) break;
            if (script.templated(statement)) continue;
            new Check(script, statement, out).run();
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

    // ---- what is known about the whole script -----------------------------------------------------------------------

    private static final class Script {
        final CharSequence text;
        final SqlDialectProfile profile;
        final @Nullable SqlSchema schema;
        /** Tables and views the script creates, and tables it alters: their shape is the script's, not the schema's. */
        final Set<String> created = new HashSet<>();
        final Set<String> altered = new HashSet<>();
        /** A USE, SET SCHEMA or SET search_path changes what an unqualified table means. */
        boolean changesDefaultSchema;

        Script(CharSequence text, SqlDialectProfile profile, @Nullable SqlSchema schema, List<List<SqlToken>> statements) {
            this.text = text; this.profile = profile; this.schema = schema;
            for (List<SqlToken> statement : statements) collect(statement);
        }

        String up(SqlToken token) { return token.text(text).toUpperCase(Locale.ROOT); }

        private void collect(List<SqlToken> s) {
            if (s.isEmpty()) return;
            String first = up(s.get(0));
            if (first.equals("USE")) changesDefaultSchema = true;
            if (first.equals("SET") && s.size() > 1) {
                String second = up(s.get(1));
                if (second.equals("SCHEMA") || second.equals("SEARCH_PATH") || second.equals("SESSION") || second.equals("LOCAL") || second.equals("CATALOG")) changesDefaultSchema = true;
            }
            for (int i = 0; i < s.size(); i++) {
                String word = up(s.get(i));
                if ((word.equals("TABLE") || word.equals("VIEW")) && i + 1 < s.size() && (first.equals("CREATE") || first.equals("ALTER"))) {
                    int at = i + 1;
                    while (at < s.size() && Set.of("IF", "NOT", "EXISTS", "ONLY").contains(up(s.get(at)))) at++;
                    String name = lastNamePart(s, at);
                    if (name != null) (first.equals("CREATE") ? created : altered).add(name.toLowerCase(Locale.ROOT));
                }
                // ALTER TABLE a RENAME TO b and RENAME TABLE a TO b: b exists from here on
                if (word.equals("TO") && (first.equals("RENAME") || first.equals("ALTER")) && i + 1 < s.size()) {
                    String name = lastNamePart(s, i + 1);
                    if (name != null) created.add(name.toLowerCase(Locale.ROOT));
                }
                // SELECT ... INTO new_table, and CREATE TABLE ... AS
                if (word.equals("INTO") && first.equals("SELECT") && i + 1 < s.size()) {
                    String name = lastNamePart(s, i + 1);
                    if (name != null) created.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }

        /** The last part of the dotted name starting at {@code at}, unquoted. */
        @Nullable String lastNamePart(List<SqlToken> s, int at) {
            String name = null;
            while (at < s.size()) {
                SqlToken token = s.get(at);
                if (token.type() != LatticeSqlTokens.IDENTIFIER && token.type() != LatticeSqlTokens.QUOTED_IDENTIFIER && token.type() != LatticeSqlTokens.KEYWORD) break;
                name = unquote(token);
                if (at + 2 < s.size() && s.get(at + 1).type() == LatticeSqlTokens.PUNCTUATION && text.charAt(s.get(at + 1).start()) == '.') at += 2;
                else break;
            }
            return name;
        }

        String unquote(SqlToken token) {
            String raw = token.text(text);
            if (token.type() != LatticeSqlTokens.QUOTED_IDENTIFIER || raw.length() < 2) return raw;
            char quote = raw.charAt(0);
            String inner = raw.substring(1, raw.charAt(raw.length() - 1) == quote ? raw.length() - 1 : raw.length());
            return inner.replace("" + quote + quote, "" + quote);
        }

        /** Stored routines and client commands: everything from here on is not a sequence of ordinary statements. */
        boolean opaque(List<SqlToken> s) {
            String first = up(s.get(0));
            if (first.equals("DELIMITER") || first.equals("DECLARE") || first.equals("DO")) return true;
            if (first.equals("BEGIN")) return s.size() > 1 && !Set.of("TRANSACTION", "WORK", "ISOLATION", "READ", "DEFERRED", "IMMEDIATE", "EXCLUSIVE", "ATOMIC").contains(up(s.get(1))) || s.size() > 1 && up(s.get(1)).equals("ATOMIC");
            if (!first.equals("CREATE")) return false;
            boolean routine = false;
            for (SqlToken token : s) {
                String word = up(token);
                if (Set.of("FUNCTION", "PROCEDURE", "TRIGGER", "EVENT", "PACKAGE").contains(word)) routine = true;
                if (routine && (word.equals("BEGIN") || word.equals("DECLARE"))) return true;
            }
            return false;
        }

        /** Template syntax (Flyway placeholders, Jinja, ERB) makes the statement something other than SQL text. */
        boolean templated(List<SqlToken> s) {
            String raw = text.subSequence(s.get(0).start(), s.get(s.size() - 1).end()).toString();
            return raw.contains("${") || raw.contains("{{") || raw.contains("{%") || raw.contains("<%") || raw.contains("#{");
        }
    }

    // ---- checks on one statement --------------------------------------------------------------------------------------

    private static final class Check {
        final Script script;
        final CharSequence text;
        final List<SqlToken> s;
        final List<SqlDiagnostic> out;
        final SqlDialectProfile profile;
        final int n;
        final String kind;
        SqlStatementAnalyzer.Structure structure;
        int[] segment;
        boolean[] skip;
        Set<String> aliasNames = new HashSet<>();
        Set<String> usingColumns = new HashSet<>();
        Set<String> ctes = new HashSet<>();
        Set<String> knownQualifiers = new HashSet<>();
        boolean natural;

        Check(Script script, List<SqlToken> statement, List<SqlDiagnostic> out) {
            this.script = script; this.text = script.text; this.s = statement; this.out = out; this.profile = script.profile;
            this.n = statement.size();
            this.kind = firstWord();
        }

        // -- token helpers ------------------------------------------------------------------------------------------

        String up(int i) { return script.up(s.get(i)); }
        boolean in(int i) { return i >= 0 && i < n; }
        boolean punct(int i, char c) { return in(i) && s.get(i).type() == LatticeSqlTokens.PUNCTUATION && text.charAt(s.get(i).start()) == c; }
        boolean keyword(int i, String word) { return in(i) && s.get(i).type() == LatticeSqlTokens.KEYWORD && up(i).equals(word); }
        boolean word(int i, String word) { return in(i) && isWord(i) && up(i).equals(word); }
        boolean isWord(int i) {
            var t = s.get(i).type();
            return t == LatticeSqlTokens.IDENTIFIER || t == LatticeSqlTokens.KEYWORD || t == LatticeSqlTokens.FUNCTION || t == LatticeSqlTokens.TYPE || t == LatticeSqlTokens.QUOTED_IDENTIFIER;
        }
        boolean ident(int i) { return in(i) && (s.get(i).type() == LatticeSqlTokens.IDENTIFIER || s.get(i).type() == LatticeSqlTokens.QUOTED_IDENTIFIER); }
        boolean operator(int i, String op) { return in(i) && s.get(i).type() == LatticeSqlTokens.OPERATOR && s.get(i).text(text).equals(op); }
        String name(int i) { return script.unquote(s.get(i)); }
        String firstWord() {
            if (n == 0) return "";
            if (punct(0, '(')) { for (int i = 1; i < n; i++) if (punct(i, '(')) continue; else return isWord(i) ? up(i) : ""; }
            return isWord(0) ? up(0) : "";
        }

        void report(Kind k, int fromToken, int toToken, String message) { report(k, fromToken, toToken, message, null); }
        void report(Kind k, int fromToken, int toToken, String message, @Nullable String replacement) {
            out.add(new SqlDiagnostic(k, s.get(fromToken).start(), s.get(toToken).end(), message, replacement));
        }

        // -- the statement ---------------------------------------------------------------------------------------------

        void run() {
            lexical();
            startWord();
            parentheses();
            if (!QUERY_STATEMENTS.contains(kind)) return;
            structure = withoutNonTables(SqlStatementAnalyzer.structure(text, s, profile));
            computeSegments();
            shape();
            semantic();
        }

        void lexical() {
            for (int i = 0; i < n; i++) {
                var type = s.get(i).type();
                String raw = s.get(i).text(text);
                if (type == LatticeSqlTokens.STRING && !terminatedString(raw)) report(Kind.SYNTAX, i, i, "Unterminated string literal");
                if (type == LatticeSqlTokens.QUOTED_IDENTIFIER && (raw.length() < 2 || raw.charAt(raw.length() - 1) != raw.charAt(0))) report(Kind.SYNTAX, i, i, "Unterminated quoted name");
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
            int open = (raw.charAt(0) == 'E' || raw.charAt(0) == 'e' || raw.charAt(0) == 'N' || raw.charAt(0) == 'n' || raw.charAt(0) == 'B' || raw.charAt(0) == 'X' || raw.charAt(0) == 'x') ? 1 : 0;
            if (open >= raw.length()) return true;
            char quote = raw.charAt(open);
            return raw.length() - open >= 2 && raw.charAt(raw.length() - 1) == quote;
        }

        /** "selct * from t": a first word that is not a statement but is one letter away from one. */
        void startWord() {
            if (n == 0 || s.get(0).type() != LatticeSqlTokens.IDENTIFIER) return;
            String first = up(0);
            if (first.length() < 4 || STATEMENT_WORDS.contains(first)) return; // a real statement word the dialect's list lacks (MERGE)
            for (String candidate : STATEMENT_WORDS) {
                if (Math.abs(candidate.length() - first.length()) <= 1 && SqlEditDistance.within(first, candidate, 1)) {
                    boolean lower = text.charAt(s.get(0).start()) >= 'a';
                    report(Kind.SYNTAX, 0, 0, "Unknown statement '" + s.get(0).text(text) + "'. Did you mean " + candidate + "?",
                            lower ? candidate.toLowerCase(Locale.ROOT) : candidate);
                    return;
                }
            }
        }

        void parentheses() {
            Deque<Integer> opened = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                if (punct(i, '(')) opened.push(i);
                else if (punct(i, ')')) {
                    if (opened.isEmpty()) report(Kind.SYNTAX, i, i, "Unmatched ')'");
                    else opened.pop();
                }
            }
            for (int open : opened) report(Kind.SYNTAX, open, open, "Unclosed '('");
        }

        // -- the shape of queries ----------------------------------------------------------------------------------------

        /** "FOR UPDATE", "ON DUPLICATE KEY UPDATE" and "DO UPDATE SET" are not table references. */
        SqlStatementAnalyzer.Structure withoutNonTables(SqlStatementAnalyzer.Structure found) {
            List<TableRef> tables = new ArrayList<>();
            for (TableRef ref : found.refs()) {
                boolean notATable = ref.introducer().equals("UPDATE") && ref.introducerIndex() > 0
                        && (word(ref.introducerIndex() - 1, "KEY") || word(ref.introducerIndex() - 1, "DO") || word(ref.introducerIndex() - 1, "FOR"));
                if (!notATable) tables.add(ref);
            }
            return new SqlStatementAnalyzer.Structure(found.blockOf(), found.parent(), found.open(), tables);
        }

        void computeSegments() {
            segment = new int[n];
            Map<Integer, Integer> counters = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int block = structure.blockOf()[i];
                if (s.get(i).type() == LatticeSqlTokens.KEYWORD && SET_OPERATORS.contains(up(i))) counters.merge(block, 1, Integer::sum);
                segment[i] = counters.getOrDefault(block, 0);
            }
        }

        boolean clauseStart(int i) {
            return in(i) && s.get(i).type() == LatticeSqlTokens.KEYWORD && CLAUSE_STARTS.contains(up(i));
        }

        boolean endsExpression(int i) { return !in(i) || punct(i, ')') || punct(i, ',') || clauseStart(i); }

        void shape() {
            selectBlocks();
            separators();
            incompleteExpressions();
            missingComma();
        }

        /** Clause order and repeats within one query: WHERE after GROUP BY, two FROMs, an empty select list. */
        void selectBlocks() {
            Map<String, Integer> lastRank = new HashMap<>();
            for (int i = 0; i < n; i++) {
                if (s.get(i).type() != LatticeSqlTokens.KEYWORD) continue;
                String word = up(i);
                int rank = SELECT_ORDER.indexOf(word);
                if (rank < 0) continue;
                if ((word.equals("GROUP") || word.equals("ORDER")) && !keyword(i + 1, "BY")) continue;
                if (word.equals("FROM") && (keyword(i - 1, "DISTINCT") || insideFieldFunction(structure.blockOf()[i]))) continue;
                String key = structure.blockOf()[i] + ":" + segment[i];
                if (word.equals("SELECT")) { lastRank.put(key, 0); if (selectListEmpty(i)) report(Kind.SYNTAX, i, i, "Select list expected after SELECT"); continue; }
                if (!lastRank.containsKey(key)) continue; // not a SELECT: DELETE FROM, UPDATE ... WHERE
                int previous = lastRank.get(key);
                if (rank < previous) report(Kind.SYNTAX, i, i, word + " cannot come after " + SELECT_ORDER.get(previous));
                else if (rank == previous && rank > 0) report(Kind.SYNTAX, i, i, "Duplicate " + word);
                lastRank.put(key, Math.max(rank, previous));
            }
        }

        boolean selectListEmpty(int select) {
            int at = select + 1;
            while (keyword(at, "DISTINCT") || keyword(at, "ALL")) at++;
            return !in(at) || clauseStart(at) || punct(at, ')');
        }

        /** FROM inside EXTRACT(), TRIM(), SUBSTRING()... is part of the function's syntax. */
        boolean insideFieldFunction(int block) {
            if (block == 0) return false;
            int open = structure.open().get(block);
            return open > 0 && isWord(open - 1) && (FIELD_FUNCTIONS.contains(up(open - 1)) || Set.of("SUBSTRING", "POSITION").contains(up(open - 1)));
        }

        /** ",," and a comma before a clause or a closing bracket. */
        void separators() {
            for (int i = 0; i < n; i++) {
                if (!punct(i, ',')) continue;
                if (punct(i + 1, ',')) report(Kind.SYNTAX, i + 1, i + 1, "Unexpected ','");
                else if (!in(i + 1) || punct(i + 1, ')') || clauseStart(i + 1)) report(Kind.SYNTAX, i, i, "Expression expected after ','");
                else if (i == 0 || punct(i - 1, '(') || clauseStartOrKeyword(i - 1)) report(Kind.SYNTAX, i, i, "Unexpected ','");
            }
        }

        boolean clauseStartOrKeyword(int i) {
            return s.get(i).type() == LatticeSqlTokens.KEYWORD && Set.of("SELECT", "WHERE", "BY", "SET", "ON", "FROM").contains(up(i));
        }

        /** A condition, comparison or list that stops where something has to follow. */
        void incompleteExpressions() {
            for (int i = 0; i < n; i++) {
                String word = s.get(i).type() == LatticeSqlTokens.KEYWORD ? up(i) : "";
                boolean comparison = s.get(i).type() == LatticeSqlTokens.OPERATOR && COMPARISONS.contains(s.get(i).text(text));
                boolean listClause = word.equals("BY") && (keyword(i - 1, "ORDER") || keyword(i - 1, "GROUP"));
                boolean needsOperand = comparison || listClause || Set.of("WHERE", "HAVING", "AND", "OR").contains(word) || word.equals("ON") && inJoin(i);
                if (!needsOperand) continue;
                boolean doubledLogic = (word.equals("AND") || word.equals("OR")) && (keyword(i + 1, "AND") || keyword(i + 1, "OR"));
                if (endsExpression(i + 1) || doubledLogic) report(Kind.SYNTAX, i, i, "Expression expected after " + (word.isEmpty() ? s.get(i).text(text) : word));
            }
            // FROM, JOIN and a statement's target need a table
            for (int at = 0; at < n; at++) {
                final int i = at;
                if (s.get(i).type() != LatticeSqlTokens.KEYWORD) continue;
                String word = up(i);
                boolean introduces = word.equals("JOIN") || word.equals("FROM") && !keyword(i - 1, "DISTINCT") && !insideFieldFunction(structure.blockOf()[i])
                        || word.equals("UPDATE") && i == 0 || word.equals("INTO") && kind.equals("INSERT");
                if (introduces && (!in(i + 1) || punct(i + 1, ')') || punct(i + 1, ',')
                        || Set.of("WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "ON").stream().anyMatch(clause -> keyword(i + 1, clause)))) {
                    report(Kind.SYNTAX, i, i, "Table name expected after " + word);
                }
            }
            insertWithoutSource();
            updateWithoutSet();
        }

        void insertWithoutSource() {
            if (!kind.equals("INSERT") || n < 3) return;
            for (int i = 0; i < n; i++) {
                if (s.get(i).type() == LatticeSqlTokens.KEYWORD && Set.of("VALUES", "SELECT", "SET", "DEFAULT", "WITH", "TABLE", "VALUE").contains(up(i))) return;
                if (punct(i, '(') && keyword(i + 1, "SELECT")) return;
            }
            report(Kind.SYNTAX, n - 1, n - 1, "VALUES or SELECT expected");
        }

        void updateWithoutSet() {
            if (!kind.equals("UPDATE") || n < 2) return;
            for (int i = 0; i < n; i++) if (keyword(i, "SET")) return;
            report(Kind.SYNTAX, n - 1, n - 1, "SET expected");
        }

        boolean inJoin(int on) {
            for (int i = on - 1; i >= 0; i--) {
                if (structure.blockOf()[i] != structure.blockOf()[on]) continue;
                if (keyword(i, "JOIN")) return true;
                if (clauseStart(i)) return false;
            }
            return false;
        }

        /** "select a b c from t": a name, an alias, then another name. */
        void missingComma() {
            for (int i = 2; i < n; i++) {
                if (!(ident(i) && ident(i - 1) && ident(i - 2))) continue;
                if (punct(i + 1, '.') || punct(i + 1, '(') || punct(i - 1, '.') || punct(i - 2, '.') || punct(i - 3, '.')) continue;
                if (!inSelectList(i) || SqlSafeWords.contains(name(i)) || SqlSafeWords.contains(name(i - 1)) || SqlSafeWords.contains(name(i - 2))) continue;
                report(Kind.SYNTAX, i, i, "Missing ',' before " + s.get(i).text(text));
            }
        }

        boolean inSelectList(int i) {
            int block = structure.blockOf()[i];
            for (int k = i - 1; k >= 0; k--) {
                if (structure.blockOf()[k] != block) continue;
                if (keyword(k, "SELECT")) return true;
                if (clauseStart(k) || keyword(k, "ON") || keyword(k, "SET") || keyword(k, "BY")) return false;
            }
            return false;
        }

        // -- tables, columns and aliases ----------------------------------------------------------------------------------

        void semantic() {
            collectNames();
            boolean multiTable = multiTableStatement();
            List<Ref> refs = resolveRefs();
            duplicateAliases(refs);
            if (multiTable) return;
            columnUses(refs);
            insertColumns(refs);
        }

        /** UPDATE t1, t2 and DELETE ... USING: tables the structure scan does not list, so names cannot be judged. */
        boolean multiTableStatement() {
            for (int i = 0; i < n; i++) if (word(i, "STRAIGHT_JOIN")) return true; // a join the structure scan does not know
            if (kind.equals("DELETE")) for (int i = 0; i < n; i++) if (keyword(i, "USING")) return true;
            if (kind.equals("UPDATE")) for (int i = 0; i < n; i++) { if (keyword(i, "SET")) break; if (punct(i, ',') && structure.blockOf()[i] == 0) return true; }
            return false;
        }

        void collectNames() {
            skip = new boolean[n];
            for (int i = 0; i < n; i++) {
                // alias definitions
                if (ident(i) && (keyword(i - 1, "AS") || operandEnd(i - 1))) aliasNames.add(name(i).toLowerCase(Locale.ROOT));
                if (keyword(i, "NATURAL")) natural = true;
                // CTE names: WITH name [(columns)] AS (  and  , name AS (
                if (ident(i) && (keyword(i - 1, "WITH") || word(i - 1, "RECURSIVE") || punct(i - 1, ',') && cteContext(i))) {
                    int at = i + 1;
                    if (punct(at, '(')) at = closing(at) + 1;
                    if (keyword(at, "AS") || word(at, "AS")) {
                        ctes.add(name(i).toLowerCase(Locale.ROOT));
                        if (punct(i + 1, '(')) skipRange(i + 1, closing(i + 1));
                    }
                }
                // USING (a, b): merged columns
                if (keyword(i, "USING") && punct(i + 1, '(')) {
                    int close = closing(i + 1);
                    for (int k = i + 2; k < close; k++) if (isWord(k)) usingColumns.add(name(k).toLowerCase(Locale.ROOT));
                    skipRange(i + 1, close);
                }
                // alias column lists  AS t(a, b)   and  index hints  USE INDEX (i)  and  COLUMNS (...)
                if (punct(i, '(') && i > 0) {
                    boolean afterAlias = ident(i - 1) && keyword(i - 2, "AS");
                    boolean afterHint = (keyword(i - 1, "INDEX") || keyword(i - 1, "KEY") || word(i - 1, "INDEX") || word(i - 1, "KEY")) && (word(i - 2, "USE") || word(i - 2, "FORCE") || word(i - 2, "IGNORE") || word(i - 2, "JOIN") || word(i - 2, "ORDER") || word(i - 2, "GROUP"));
                    boolean afterColumns = word(i - 1, "COLUMNS");
                    if (afterAlias || afterHint || afterColumns) skipRange(i, closing(i));
                }
                // field names and units
                if (punct(i, '(') && i > 0 && isWord(i - 1) && FIELD_FUNCTIONS.contains(up(i - 1)) && in(i + 1)) skip[i + 1] = true;
                if (word(i, "COLLATE") && in(i + 1)) skip[i + 1] = true;
                if (keyword(i, "OF") && keyword(i - 1, "UPDATE") || word(i, "OF") && (word(i - 1, "UPDATE") || word(i - 1, "SHARE"))) { for (int k = i + 1; k < n && (isWord(k) || punct(k, ',') || punct(k, '.')); k++) skip[k] = true; }
                if (keyword(i, "OVER") && ident(i + 1)) skip[i + 1] = true;
                if (in(i + 1) && ident(i) && (operator(i + 1, "=>") || operator(i + 1, ":="))) skip[i] = true;
            }
            // regions that name things the schema cannot judge
            for (int i = 0; i < n; i++) {
                boolean conflict = word(i, "ON") && (word(i + 1, "CONFLICT") || word(i + 1, "DUPLICATE"));
                if (conflict || word(i, "RETURNING") || word(i, "WINDOW")) { for (int k = i; k < n; k++) skip[k] = true; break; }
            }
            // table references are judged on their own, and an INSERT's column list is checked against its table
            for (TableRef ref : structure.refs()) {
                for (int k = ref.startIndex(); k < Math.min(ref.endIndex(), n); k++) skip[k] = true;
                if (ref.introducer().equals("INTO") && punct(ref.endIndex(), '(') && !keyword(ref.endIndex() + 1, "SELECT")) skipRange(ref.endIndex(), closing(ref.endIndex()));
            }
            // known qualifiers
            for (TableRef ref : structure.refs()) {
                if (ref.exposedName() != null) knownQualifiers.add(ref.exposedName().toLowerCase(Locale.ROOT));
                if (ref.name() != null) knownQualifiers.add(ref.name().toLowerCase(Locale.ROOT));
            }
            knownQualifiers.addAll(ctes);
            knownQualifiers.addAll(aliasNames);
        }

        boolean cteContext(int i) {
            for (int k = i - 1; k >= 0; k--) {
                if (structure.blockOf()[k] != structure.blockOf()[i]) continue;
                if (keyword(k, "WITH") || word(k, "RECURSIVE")) return true;
                if (keyword(k, "SELECT") || keyword(k, "INSERT") || keyword(k, "UPDATE") || keyword(k, "DELETE")) return false;
            }
            return false;
        }

        void skipRange(int from, int to) { for (int k = from; k <= to && k < n; k++) skip[k] = true; }

        int closing(int open) {
            int depth = 0;
            for (int k = open; k < n; k++) {
                if (punct(k, '(')) depth++;
                else if (punct(k, ')') && --depth == 0) return k;
            }
            return n - 1;
        }

        boolean operandEnd(int i) {
            if (!in(i)) return false;
            var t = s.get(i).type();
            if (t == LatticeSqlTokens.IDENTIFIER || t == LatticeSqlTokens.QUOTED_IDENTIFIER || t == LatticeSqlTokens.NUMBER || t == LatticeSqlTokens.STRING || t == LatticeSqlTokens.PARAMETER) return true;
            return punct(i, ')') || t == LatticeSqlTokens.KEYWORD && Set.of("NULL", "TRUE", "FALSE").contains(up(i));
        }

        // -- table references -------------------------------------------------------------------------------------------------

        /** What a table reference resolved to: opaque (columns unknowable), or a real table whose columns may be known. */
        record Ref(TableRef ref, @Nullable ExplorerMetadataSnapshot.Table table, boolean opaque) {
            boolean columnsKnown() { return table != null && !opaque && table.columnsLoaded(); }
        }

        List<Ref> resolveRefs() {
            List<Ref> resolved = new ArrayList<>();
            for (TableRef ref : structure.refs()) {
                boolean opaque = ref.derived() || ref.name() == null;
                String lower = ref.name() == null ? "" : ref.name().toLowerCase(Locale.ROOT);
                if (!opaque && ref.namespace() == null && (ctes.contains(lower) || script.created.contains(lower))) opaque = true;
                if (!opaque && !ref.introducer().equals("INTO") && !ref.introducer().equals("UPDATE") && punct(ref.endIndex(), '(')) opaque = true; // a function that returns rows
                if (!opaque && script.created.contains(lower) || !opaque && script.altered.contains(lower)) opaque = true;
                ExplorerMetadataSnapshot.Table table = null;
                if (!opaque && script.schema != null) {
                    table = lookup(ref);
                    if (table == null) opaque = true;
                }
                resolved.add(new Ref(ref, table, opaque));
            }
            return resolved;
        }

        /** The table, reporting an unknown one when the schema is sure to list it. */
        @Nullable ExplorerMetadataSnapshot.Table lookup(TableRef ref) {
            SqlSchema schema = script.schema;
            String lower = ref.name().toLowerCase(Locale.ROOT);
            if (lower.equals("dual") || lower.startsWith("pg_") || lower.startsWith("sqlite_") || lower.startsWith("sys")) return null;
            ExplorerMetadataSnapshot.Namespace namespace = schema.namespace(ref.namespace());
            if (namespace == null || !namespace.tablesLoaded()) return null;
            if (ref.namespace() == null && script.changesDefaultSchema) return null;
            var table = schema.table(ref.namespace(), ref.name());
            if (table != null) return table;
            if (ambiguousCase(namespace, ref.name())) return null;
            String suggestion = nearest(ref.name(), namespace.tables().stream().map(t -> t.metadata().getName()).toList());
            String where = ref.namespace() == null ? "" : " in " + ref.namespace();
            int startToken = nameToken(ref);
            if (startToken >= 0) {
                report(Kind.UNKNOWN_TABLE, startToken, startToken, "Unknown table '" + ref.name() + "'" + where + (suggestion == null ? "" : ". Did you mean '" + suggestion + "'?"),
                        suggestion == null ? null : requote(suggestion, startToken));
            }
            return null;
        }

        boolean ambiguousCase(ExplorerMetadataSnapshot.Namespace namespace, String name) {
            return namespace.tables().stream().filter(t -> t.metadata().getName().equalsIgnoreCase(name)).count() > 1;
        }

        /** The token of the table's own name (the last part of a dotted name). */
        int nameToken(TableRef ref) {
            int last = -1;
            for (int k = ref.startIndex(); k < ref.endIndex() && k < n; k++) {
                if (ref.alias() != null && k >= aliasStart(ref)) break;
                if (isWord(k)) last = k;
                else if (!punct(k, '.')) break;
            }
            return last;
        }

        int aliasStart(TableRef ref) {
            if (ref.alias() == null) return Integer.MAX_VALUE;
            int k = ref.endIndex() - 1;
            return keyword(k - 1, "AS") ? k - 1 : k;
        }

        String requote(String replacement, int token) {
            return s.get(token).type() == LatticeSqlTokens.QUOTED_IDENTIFIER ? text.charAt(s.get(token).start()) + replacement + text.charAt(s.get(token).start()) : replacement;
        }

        void duplicateAliases(List<Ref> refs) {
            Map<String, TableRef> seen = new HashMap<>();
            for (Ref r : refs) {
                TableRef ref = r.ref();
                if (ref.exposedName() == null || ref.introducer().equals("INTO")) continue;
                String key = structure.blockOf()[ref.startIndex()] + ":" + segment[ref.startIndex()] + ":" + ref.exposedName().toLowerCase(Locale.ROOT);
                if (seen.containsKey(key)) {
                    int at = Math.min(ref.alias() != null ? ref.endIndex() - 1 : nameToken(ref), n - 1);
                    if (at >= 0) report(Kind.ALIAS, at, at, "Duplicate table alias '" + ref.exposedName() + "'");
                } else seen.put(key, ref);
            }
        }

        // -- column uses ---------------------------------------------------------------------------------------------------

        /** Tables visible at a token, innermost query first. Tables a statement inserts into are not part of what its SELECT sees. */
        List<List<Ref>> scopes(int token, List<Ref> refs) {
            List<List<Ref>> levels = new ArrayList<>();
            int seg = segment[token];
            for (int block = structure.blockOf()[token]; block >= 0; block = structure.parent().get(block)) {
                List<Ref> level = new ArrayList<>();
                for (Ref r : refs) {
                    if (r.ref().block() != block || r.ref().introducer().equals("INTO")) continue;
                    if (block == structure.blockOf()[token] && segment[r.ref().startIndex()] != seg) continue;
                    level.add(r);
                }
                if (!level.isEmpty()) levels.add(level);
                if (block == 0) break;
            }
            return levels;
        }

        void columnUses(List<Ref> refs) {
            Set<Integer> setTargets = setTargets();
            for (int i = 0; i < n; i++) {
                if (skip[i] || !ident(i) && !(isQualifierStart(i))) continue;
                if (punct(i - 1, '.') || operator(i - 1, "::") || keyword(i - 1, "AS")) continue;
                if (operandEnd(i - 1)) continue; // an alias
                List<Integer> chain = chain(i);
                int last = chain.get(chain.size() - 1);
                if (punct(last + 1, '(')) continue;
                if (chain.size() == 1 && !ident(i)) continue;
                boolean star = operator(last, "*");
                List<String> parts = new ArrayList<>();
                for (int k : chain) if (!operator(k, "*")) parts.add(name(k));
                boolean asSetTarget = setTargets.contains(i);
                if (parts.size() == 1 && !star) unqualified(i, parts.get(0), refs, asSetTarget);
                else if (parts.size() == 1 && star) qualified(i, i, parts.get(0), null, refs, chain);
                else if (parts.size() == 2) qualified(i, last, parts.get(0), star ? null : parts.get(1), refs, chain);
                else if (parts.size() == 3 && !star) qualified(chain.get(1), last, parts.get(1), parts.get(2), refs, chain);
            }
        }

        boolean isQualifierStart(int i) { return isWord(i) && punct(i + 1, '.') && !punct(i - 1, '.'); }

        List<Integer> chain(int start) {
            List<Integer> parts = new ArrayList<>();
            int k = start;
            parts.add(k);
            while (punct(k + 1, '.') && in(k + 2) && (isWord(k + 2) || operator(k + 2, "*"))) { parts.add(k + 2); k += 2; }
            return parts;
        }

        /** The identifiers on the left of "=" directly after SET: they name the statement's target table's columns. */
        Set<Integer> setTargets() {
            Set<Integer> targets = new HashSet<>();
            if (!kind.equals("UPDATE")) return targets;
            boolean inSet = false;
            for (int i = 0; i < n; i++) {
                if (structure.blockOf()[i] != 0) continue;
                if (keyword(i, "SET")) inSet = true;
                else if (keyword(i, "WHERE") || keyword(i, "FROM")) inSet = false;
                else if (inSet && ident(i) && (keyword(i - 1, "SET") || punct(i - 1, ',')) && operator(i + 1, "=")) targets.add(i);
            }
            return targets;
        }

        void qualified(int fromToken, int lastToken, String qualifier, @Nullable String column, List<Ref> refs, List<Integer> chain) {
            String lowerQualifier = qualifier.toLowerCase(Locale.ROOT);
            if (TRAILING_QUALIFIERS.contains(qualifier.toUpperCase(Locale.ROOT))) return;
            List<List<Ref>> levels = scopes(fromToken, refs);
            Ref found = null;
            for (List<Ref> level : levels) {
                for (Ref r : level) if (r.ref().exposedName() != null && r.ref().exposedName().equalsIgnoreCase(qualifier)) found = found == null ? r : found;
                if (found != null) break;
            }
            int qualifierToken = chain.size() == 3 ? chain.get(1) : chain.get(0);
            if (found == null) {
                if (knownQualifiers.contains(lowerQualifier) || ctes.contains(lowerQualifier)) {
                    // a table that is aliased, named by its own name
                    for (TableRef ref : structure.refs()) {
                        if (ref.alias() != null && ref.name() != null && ref.name().equalsIgnoreCase(qualifier) && !qualifier.equalsIgnoreCase(ref.alias())
                                && noOtherExposes(qualifier)) {
                            report(Kind.ALIAS, qualifierToken, qualifierToken, "'" + qualifier + "' is aliased as '" + ref.alias() + "' here; use the alias", requote(ref.alias(), qualifierToken));
                            return;
                        }
                    }
                    return;
                }
                if (script.schema != null && script.schema.namespace(qualifier) != null) return;
                if (levels.isEmpty() && structure.refs().isEmpty()) return;
                report(Kind.ALIAS, qualifierToken, qualifierToken, "Unknown table or alias '" + qualifier + "'");
                return;
            }
            if (column == null || !found.columnsKnown()) return;
            boolean exists = found.table().columns().stream().anyMatch(c -> c.getName().equalsIgnoreCase(column));
            if (!exists) {
                int token = lastToken;
                String suggestion = nearest(column, found.table().columns().stream().map(ColumnMetadata::getName).toList());
                report(Kind.UNKNOWN_COLUMN, token, token, "Unknown column '" + column + "' in " + found.table().metadata().getName() + (suggestion == null ? "" : ". Did you mean '" + suggestion + "'?"),
                        suggestion == null ? null : requote(suggestion, token));
            }
        }

        boolean noOtherExposes(String qualifier) {
            for (TableRef ref : structure.refs()) if (ref.exposedName() != null && ref.exposedName().equalsIgnoreCase(qualifier)) return false;
            return true;
        }

        void unqualified(int token, String column, List<Ref> refs, boolean setTarget) {
            if (script.schema == null) return;
            String lower = column.toLowerCase(Locale.ROOT);
            boolean quoted = s.get(token).type() == LatticeSqlTokens.QUOTED_IDENTIFIER;
            if (aliasNames.contains(lower) && !quoted || usingColumns.contains(lower) && !quoted) return;
            List<List<Ref>> levels = setTarget ? targetLevel(refs) : scopes(token, refs);
            if (levels.isEmpty()) return;
            for (List<Ref> level : levels) for (Ref r : level) if (!r.columnsKnown()) return; // something in scope hides its columns
            for (List<Ref> level : levels) {
                List<Ref> matches = new ArrayList<>();
                for (Ref r : level) if (r.table().columns().stream().anyMatch(c -> c.getName().equalsIgnoreCase(column))) matches.add(r);
                if (matches.isEmpty()) continue;
                if (matches.size() > 1 && !natural && !setTarget) {
                    report(Kind.AMBIGUOUS_COLUMN, token, token, "Column '" + column + "' is in " + names(matches) + "; qualify it");
                }
                return;
            }
            if (!quoted && (SqlSafeWords.contains(column) || profile.keywords().contains(column.toUpperCase(Locale.ROOT)))) return;
            List<String> candidates = new ArrayList<>();
            for (List<Ref> level : levels) for (Ref r : level) for (ColumnMetadata c : r.table().columns()) candidates.add(c.getName());
            String suggestion = nearest(column, candidates);
            report(Kind.UNKNOWN_COLUMN, token, token, "Unknown column '" + column + "'" + (suggestion == null ? "" : ". Did you mean '" + suggestion + "'?"),
                    suggestion == null ? null : requote(suggestion, token));
        }

        String names(List<Ref> matches) {
            StringJoiner joined = new StringJoiner(" and ");
            for (Ref r : matches) joined.add(r.ref().exposedName() == null ? r.table().metadata().getName() : r.ref().exposedName());
            return joined.toString();
        }

        /** UPDATE's own table: the only one its SET list can name. */
        List<List<Ref>> targetLevel(List<Ref> refs) {
            for (Ref r : refs) if (r.ref().introducer().equals("UPDATE")) return List.of(List.of(r));
            return List.of();
        }

        /** INSERT INTO t (a, b): the names must be columns of t. */
        void insertColumns(List<Ref> refs) {
            if (!kind.equals("INSERT") && !kind.equals("REPLACE")) return;
            for (Ref r : refs) {
                TableRef ref = r.ref();
                if (!ref.introducer().equals("INTO") || !r.columnsKnown() || !punct(ref.endIndex(), '(') || keyword(ref.endIndex() + 1, "SELECT")) continue;
                int close = closing(ref.endIndex());
                for (int k = ref.endIndex() + 1; k < close; k++) {
                    if (!ident(k) || !(punct(k - 1, '(') || punct(k - 1, ','))) continue;
                    String column = name(k);
                    if (r.table().columns().stream().noneMatch(c -> c.getName().equalsIgnoreCase(column))) {
                        String suggestion = nearest(column, r.table().columns().stream().map(ColumnMetadata::getName).toList());
                        report(Kind.UNKNOWN_COLUMN, k, k, "Unknown column '" + column + "' in " + r.table().metadata().getName() + (suggestion == null ? "" : ". Did you mean '" + suggestion + "'?"),
                                suggestion == null ? null : requote(suggestion, k));
                    }
                }
            }
        }

        /** The closest name within a typo's distance, or null. */
        @Nullable String nearest(String wanted, Collection<String> candidates) {
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            int limit = wanted.length() <= 4 ? 1 : 2;
            for (String candidate : candidates) {
                if (candidate.equalsIgnoreCase(wanted)) continue;
                int distance = SqlEditDistance.distance(wanted.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT), limit);
                if (distance <= limit && distance < bestDistance) { best = candidate; bestDistance = distance; }
            }
            return best;
        }
    }
}
