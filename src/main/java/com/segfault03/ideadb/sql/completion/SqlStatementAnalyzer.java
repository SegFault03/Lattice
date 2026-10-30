package com.segfault03.ideadb.sql.completion;

import com.segfault03.ideadb.sql.completion.SqlContext.Position;
import com.segfault03.ideadb.sql.completion.SqlContext.TableRef;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Works out what the caret is in, from the text alone. It finds the statement around the caret, the tables and aliases
 * visible there (including ones named after the caret, and outer queries' tables for a subquery), the clause the caret
 * is in, and the name being typed with any qualifier before it. Pure: no IDE, no database.
 *
 * <p>This is deliberately a scanner, not a parser. It handles the shapes people actually type, including unfinished
 * statements, and falls back to {@link Position#OTHER} rather than guessing.
 */
public final class SqlStatementAnalyzer {
    private static final Set<String> INTRODUCERS = Set.of("FROM", "JOIN", "UPDATE", "INTO");
    /** Words that end a table reference or can never be one. */
    private static final Set<String> CLAUSE_WORDS = Set.of("SELECT", "WHERE", "GROUP", "ORDER", "HAVING", "SET", "ON", "USING", "JOIN",
            "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "LIMIT", "OFFSET", "UNION", "INTERSECT", "EXCEPT", "VALUES",
            "RETURNING", "FROM", "INTO", "WINDOW", "FETCH", "FOR", "WITH", "AS", "BY", "OUTER", "AND", "OR", "NOT");
    private static final Set<String> CLAUSES = Set.of("SELECT", "FROM", "JOIN", "ON", "WHERE", "HAVING", "BY", "SET", "VALUES", "INTO",
            "UPDATE", "LIMIT", "OFFSET", "FETCH", "UNION", "INTERSECT", "EXCEPT", "WITH", "RETURNING", "TABLE", "TRUNCATE", "USING", "REFERENCES");
    /** FROM inside these function calls is part of their syntax, not a table clause. */
    private static final Set<String> FROM_IN_FUNCTIONS = Set.of("EXTRACT", "TRIM", "SUBSTRING", "OVERLAY", "POSITION");

    private SqlStatementAnalyzer() {}

    public static @NotNull SqlContext analyze(@NotNull CharSequence text, int caret, @NotNull SqlDialectProfile profile) {
        var window = SqlTextWindow.around(text, caret);
        if (window.length() < text.length()) {
            // A large file: look at the statement around the caret only, then put the offsets back.
            SqlContext inside = analyze(text.subSequence(window.start(), window.end()), caret - window.start(), profile);
            return inside.withPrefixStart(inside.prefixStart() + window.start());
        }
        List<SqlToken> all = SqlTokenizer.tokenize(text, profile);
        for (SqlToken token : all) {
            if (token.start() >= caret) break;
            if (caretInsideCommentOrString(text, caret, token)) return none(caret);
        }

        // The word being typed, if the caret is in or at the end of one.
        SqlToken word = null;
        for (SqlToken token : all) {
            if (token.start() < caret && caret <= token.end() && isWord(token)) {
                boolean closedQuote = token.type() == LatticeSqlTokens.QUOTED_IDENTIFIER && caret == token.end() && closedQuoted(text, token);
                if (!closedQuote) { word = token; break; }
            }
        }
        char openQuote = 0;
        String prefix = "";
        int prefixStart = caret;
        if (word != null) {
            if (word.type() == LatticeSqlTokens.QUOTED_IDENTIFIER) {
                openQuote = text.charAt(word.start());
                prefixStart = word.start() + 1;
            } else {
                prefixStart = word.start();
            }
            prefix = text.subSequence(prefixStart, caret).toString();
        }
        int wordStart = word != null ? word.start() : caret;

        // The statement: from after the previous semicolon to the next one, without the word being typed.
        int first = 0, last = all.size();
        for (int i = 0; i < all.size(); i++) {
            SqlToken token = all.get(i);
            if (isPunct(token, text, ';')) {
                if (token.end() <= wordStart) first = i + 1;
                else if (token.start() >= caret) { last = i; break; }
            }
        }
        List<SqlToken> stmt = new ArrayList<>();
        int caretIdx = 0;
        for (int i = first; i < last; i++) {
            SqlToken token = all.get(i);
            if (token == word) continue;
            if (token.end() <= wordStart) caretIdx++;
            stmt.add(token);
        }
        return new Scan(text, stmt, caretIdx, profile, prefix, prefixStart, openQuote, allTokensForCase(all, text)).run();
    }

    // ---- caret in a comment or string -----------------------------------------------------------------------------

    private static boolean caretInsideCommentOrString(CharSequence text, int caret, SqlToken token) {
        if (token.end() < caret) return false;
        String content = token.text(text);
        if (token.type() == LatticeSqlTokens.LINE_COMMENT) return caret <= token.end();
        if (token.type() == LatticeSqlTokens.BLOCK_COMMENT) return caret < token.end() || !(content.length() >= 4 && content.endsWith("*/"));
        if (token.type() == LatticeSqlTokens.STRING) return caret < token.end() || !stringTerminated(content);
        return false;
    }

    private static boolean stringTerminated(String content) {
        if (content.startsWith("$")) {
            int tagEnd = content.indexOf('$', 1);
            if (tagEnd < 0) return false;
            String tag = content.substring(0, tagEnd + 1);
            return content.length() >= 2 * tag.length() && content.endsWith(tag);
        }
        int open = content.indexOf(content.charAt(0) == '\'' || content.charAt(0) == '"' ? content.charAt(0) : '\'');
        char quote = content.charAt(open);
        return content.length() - open >= 2 && content.charAt(content.length() - 1) == quote;
    }

    private static boolean closedQuoted(CharSequence text, SqlToken token) {
        char quote = text.charAt(token.start());
        return token.end() - token.start() >= 2 && text.charAt(token.end() - 1) == quote;
    }

    private static SqlContext none(int caret) {
        return new SqlContext(Position.NONE, "", caret, (char) 0, List.of(), List.of(), null, null, List.of(), false, false, Set.of());
    }

    private static boolean isWord(SqlToken token) {
        var type = token.type();
        return type == LatticeSqlTokens.IDENTIFIER || type == LatticeSqlTokens.KEYWORD || type == LatticeSqlTokens.FUNCTION
                || type == LatticeSqlTokens.TYPE || type == LatticeSqlTokens.QUOTED_IDENTIFIER;
    }

    private static boolean isPunct(SqlToken token, CharSequence text, char c) {
        return token.type() == LatticeSqlTokens.PUNCTUATION && text.charAt(token.start()) == c;
    }

    /** Every keyword in the file, in order, to decide how the user capitalizes. */
    private static List<SqlToken> allTokensForCase(List<SqlToken> all, CharSequence text) {
        List<SqlToken> keywords = new ArrayList<>();
        for (SqlToken token : all) if (token.type() == LatticeSqlTokens.KEYWORD) keywords.add(token);
        return keywords;
    }

    // ---- scanning one statement -----------------------------------------------------------------------------------

    private static final class Scan {
        final CharSequence text;
        final List<SqlToken> stmt;
        final int caretIdx;
        final SqlDialectProfile profile;
        final String prefix;
        final int prefixStart;
        final char openQuote;
        final List<SqlToken> fileKeywords;
        final int n;
        final int[] blockOf;
        final List<Integer> parent = new ArrayList<>(List.of(-1));
        final List<Integer> open = new ArrayList<>(List.of(-1));
        int caretBlock;
        final List<TableRef> refs = new ArrayList<>();

        Scan(CharSequence text, List<SqlToken> stmt, int caretIdx, SqlDialectProfile profile, String prefix, int prefixStart,
             char openQuote, List<SqlToken> fileKeywords) {
            this.text = text; this.stmt = stmt; this.caretIdx = caretIdx; this.profile = profile; this.prefix = prefix;
            this.prefixStart = prefixStart; this.openQuote = openQuote; this.fileKeywords = fileKeywords;
            this.n = stmt.size();
            this.blockOf = new int[n];
        }

        String upper(int i) { return stmt.get(i).text(text).toUpperCase(Locale.ROOT); }
        boolean is(int i, char c) { return i >= 0 && i < n && isPunct(stmt.get(i), text, c); }
        boolean isKeyword(int i, String word) { return i >= 0 && i < n && stmt.get(i).type() == LatticeSqlTokens.KEYWORD && upper(i).equals(word); }
        boolean isNameLike(int i) { return i >= 0 && i < n && isWord(stmt.get(i)); }

        SqlContext run() {
            computeBlocks();
            findReferences();

            List<String> qualifier = qualifier();
            SqlToken previous = caretIdx > 0 ? stmt.get(caretIdx - 1) : null;
            boolean afterOperand = previous != null && isOperand(caretIdx - 1);

            Position position;
            TableRef target = null, joinTarget = null;
            List<TableRef> joinPrior = List.of();
            Set<String> used = Set.of();

            if (previous != null && previous.type() == LatticeSqlTokens.OPERATOR && previous.text(text).equals("::")) {
                position = Position.TYPE_NAME;
            } else if (isKeyword(caretIdx - 1, "AS") && insideCast()) {
                position = Position.TYPE_NAME;
            } else {
                Found found = locate(caretBlock, caretIdx);
                position = found.position;
                target = found.target;
                joinTarget = found.joinTarget;
                joinPrior = found.joinPrior;
                used = found.used;
            }
            return new SqlContext(position, prefix, prefixStart, openQuote, qualifier, visibleTables(), target, joinTarget,
                    joinPrior, afterOperand, lowercaseKeywords(), used);
        }

        // -- blocks: parenthesized groups ---------------------------------------------------------------------------

        void computeBlocks() {
            int current = 0;
            caretBlock = 0;
            for (int i = 0; i < n; i++) {
                if (i == caretIdx) caretBlock = current;
                if (is(i, '(')) {
                    blockOf[i] = current;
                    parent.add(current);
                    open.add(i);
                    current = parent.size() - 1;
                } else if (is(i, ')')) {
                    if (current != 0) current = parent.get(current);
                    blockOf[i] = current;
                } else {
                    blockOf[i] = current;
                }
            }
            if (caretIdx >= n) caretBlock = current;
        }

        // -- table references ---------------------------------------------------------------------------------------

        void findReferences() {
            for (int i = 0; i < n; i++) {
                if (stmt.get(i).type() != LatticeSqlTokens.KEYWORD) continue;
                String word = upper(i);
                if (!INTRODUCERS.contains(word)) continue;
                int block = blockOf[i];
                if (word.equals("FROM") && (isKeyword(i - 1, "DISTINCT") || insideFromFunction(block))) continue;
                int at = i + 1;
                while (true) {
                    int[] end = new int[1];
                    TableRef ref = parseReference(at, block, word, i, end);
                    if (ref == null) break;
                    refs.add(ref);
                    at = end[0];
                    if (word.equals("FROM") && is(at, ',') && blockOf[at] == block) at++;
                    else break;
                }
            }
        }

        boolean insideFromFunction(int block) {
            if (block == 0) return false;
            int o = open.get(block);
            return o > 0 && isNameLike(o - 1) && FROM_IN_FUNCTIONS.contains(upper(o - 1));
        }

        boolean insideCast() {
            if (caretBlock == 0) return false;
            int o = open.get(caretBlock);
            return o > 0 && isKeyword(o - 1, "CAST");
        }

        boolean nameToken(int i, boolean allowWords) {
            if (i >= n) return false;
            var type = stmt.get(i).type();
            if (type == LatticeSqlTokens.IDENTIFIER || type == LatticeSqlTokens.QUOTED_IDENTIFIER) return true;
            return allowWords && isWord(stmt.get(i)) && !CLAUSE_WORDS.contains(upper(i));
        }

        @Nullable TableRef parseReference(int start, int block, String introducer, int introducerIndex, int[] end) {
            if (start >= n || blockOf[start] != block && !is(start, '(')) return null;
            if (is(start, '(')) { // a derived table: a subquery with an alias
                int depth = 0, k = start;
                for (; k < n; k++) {
                    if (is(k, '(')) depth++;
                    else if (is(k, ')') && --depth == 0) break;
                }
                if (k >= n) return null;
                int at = k + 1;
                if (isKeyword(at, "AS")) at++;
                String alias = null;
                if (nameToken(at, true) && blockOf[at] == block) { alias = unquote(at); at++; }
                end[0] = at;
                return new TableRef(null, null, alias, block, introducer, introducerIndex, start, at, true);
            }
            // The first part may not be a clause word (WHERE is not a table); after a dot any word is a name (a.order).
            List<String> parts = new ArrayList<>();
            int k = start;
            while (k < n && blockOf[k] == block && (parts.isEmpty() ? nameToken(k, true) : isWord(stmt.get(k)))) {
                parts.add(unquote(k));
                k++;
                if (is(k, '.') && blockOf[k] == block) { k++; continue; }
                break;
            }
            if (parts.isEmpty()) return null;
            String alias = null;
            if (isKeyword(k, "AS") && blockOf[k] == block) {
                if (nameToken(k + 1, true)) { alias = unquote(k + 1); k += 2; }
            } else if (k < n && blockOf[k] == block) {
                var type = stmt.get(k).type();
                if (type == LatticeSqlTokens.IDENTIFIER || type == LatticeSqlTokens.QUOTED_IDENTIFIER) { alias = unquote(k); k++; }
            }
            end[0] = k;
            return new TableRef(parts.size() >= 2 ? parts.get(parts.size() - 2) : null, parts.get(parts.size() - 1), alias, block,
                    introducer, introducerIndex, start, k, false);
        }

        String unquote(int i) {
            String raw = stmt.get(i).text(text);
            if (stmt.get(i).type() != LatticeSqlTokens.QUOTED_IDENTIFIER || raw.length() < 2) return raw;
            char quote = raw.charAt(0);
            String inner = raw.substring(1, raw.charAt(raw.length() - 1) == quote ? raw.length() - 1 : raw.length());
            return inner.replace("" + quote + quote, "" + quote);
        }

        List<TableRef> visibleTables() {
            Set<Integer> visible = new HashSet<>();
            for (int block = caretBlock; block >= 0; block = parent.get(block)) visible.add(block);
            List<TableRef> found = new ArrayList<>();
            for (TableRef ref : refs) if (visible.contains(ref.block())) found.add(ref);
            return found;
        }

        // -- the qualifier before the caret -------------------------------------------------------------------------

        List<String> qualifier() {
            List<String> parts = new ArrayList<>();
            int p = caretIdx - 1;
            while (is(p, '.') && isNameLike(p - 1)) {
                parts.add(0, unquote(p - 1));
                p -= 2;
            }
            return parts;
        }

        boolean isOperand(int i) {
            var type = stmt.get(i).type();
            if (type == LatticeSqlTokens.IDENTIFIER || type == LatticeSqlTokens.QUOTED_IDENTIFIER || type == LatticeSqlTokens.NUMBER
                    || type == LatticeSqlTokens.STRING || type == LatticeSqlTokens.PARAMETER) return true;
            if (is(i, ')')) return true;
            return type == LatticeSqlTokens.KEYWORD && Set.of("NULL", "TRUE", "FALSE").contains(upper(i));
        }

        boolean lowercaseKeywords() {
            if (!prefix.isEmpty() && openQuote == 0) return prefix.equals(prefix.toLowerCase(Locale.ROOT));
            for (int i = caretIdx - 1; i >= 0; i--) {
                if (stmt.get(i).type() == LatticeSqlTokens.KEYWORD) return isLower(stmt.get(i).text(text));
            }
            if (!fileKeywords.isEmpty()) return isLower(fileKeywords.get(0).text(text));
            return false;
        }

        boolean isLower(String word) { return word.equals(word.toLowerCase(Locale.ROOT)); }

        // -- which clause the caret is in ---------------------------------------------------------------------------

        record Found(Position position, @Nullable TableRef target, @Nullable TableRef joinTarget, List<TableRef> joinPrior, Set<String> used) {
            static Found of(Position position) { return new Found(position, null, null, List.of(), Set.of()); }
        }

        Found locate(int block, int idx) {
            if (block != 0) {
                int o = open.get(block);
                // INSERT INTO t (| ...): the column list of the table just named.
                for (TableRef ref : refs) {
                    if (ref.introducer().equals("INTO") && ref.endIndex() == o && ref.block() == parent.get(block)) {
                        return new Found(Position.INSERT_COLUMNS, ref, null, List.of(), wordsIn(block));
                    }
                }
            }
            int clauseAt = nearestClause(block, idx);
            if (clauseAt < 0) {
                if (block != 0) {
                    int o = open.get(block);
                    // Directly after "(": a subquery may start here, which the enclosing clause decides.
                    Found outer = locate(parent.get(block), o);
                    if (idx == o + 1 && (outer.position == Position.TABLE_NAME || outer.position == Position.AFTER_TABLE)) return Found.of(Position.STATEMENT_START);
                    return outer;
                }
                return Found.of(idx == 0 ? Position.STATEMENT_START : Position.OTHER);
            }
            String clause = upper(clauseAt);
            switch (clause) {
                case "SELECT", "RETURNING":
                    return Found.of(Position.SELECT_LIST);
                case "FROM", "JOIN", "UPDATE", "INTO", "TABLE", "TRUNCATE", "REFERENCES": {
                    // "shop.|" is still the table slot: step back over the qualifier first.
                    int from = idx;
                    while (from - 2 > clauseAt && is(from - 1, '.') && isNameLike(from - 2)) from -= 2;
                    boolean slot = from == clauseAt + 1 || (clause.equals("FROM") && is(from - 1, ',') && blockOf[from - 1] == block)
                            || onlyModifiersBetween(clauseAt, from);
                    TableRef target = clause.equals("UPDATE") || clause.equals("INTO") ? refAfter(clauseAt) : null;
                    return new Found(slot ? Position.TABLE_NAME : Position.AFTER_TABLE, target, null, List.of(), Set.of());
                }
                case "ON": {
                    TableRef join = null;
                    for (TableRef ref : refs) {
                        if (ref.block() == block && ref.introducer().equals("JOIN") && ref.endIndex() <= clauseAt
                                && (join == null || ref.startIndex() > join.startIndex())) join = ref;
                    }
                    if (join == null) return Found.of(Position.CONDITION);
                    List<TableRef> prior = new ArrayList<>();
                    for (TableRef ref : refs) if (ref.block() == block && ref.startIndex() < join.startIndex()) prior.add(ref);
                    return new Found(Position.CONDITION, null, join, prior, Set.of());
                }
                case "WHERE", "HAVING", "USING":
                    return Found.of(clause.equals("USING") && idx == clauseAt + 1 ? Position.OTHER : Position.CONDITION);
                case "BY":
                    return Found.of(Position.ORDER_GROUP);
                case "SET": {
                    TableRef update = null;
                    for (TableRef ref : refs) if (ref.introducer().equals("UPDATE") && ref.block() == block) { update = ref; break; }
                    for (int i = idx - 1; i > clauseAt; i--) {
                        if (blockOf[i] != block) continue;
                        if (is(i, ',')) return new Found(Position.SET_COLUMN, update, null, List.of(), Set.of());
                        if (stmt.get(i).type() == LatticeSqlTokens.OPERATOR && stmt.get(i).text(text).equals("=")) {
                            return new Found(Position.SET_VALUE, update, null, List.of(), Set.of());
                        }
                    }
                    return new Found(Position.SET_COLUMN, update, null, List.of(), Set.of());
                }
                case "VALUES":
                    return Found.of(Position.VALUES);
                case "UNION", "INTERSECT", "EXCEPT":
                    return Found.of(Position.STATEMENT_START);
                default:
                    return Found.of(Position.OTHER);
            }
        }

        /** The index of the closest clause keyword before idx in this block (BY counts only after GROUP, ORDER or PARTITION). */
        int nearestClause(int block, int idx) {
            int floor = block == 0 ? -1 : open.get(block);
            for (int i = idx - 1; i > floor; i--) {
                if (blockOf[i] != block || stmt.get(i).type() != LatticeSqlTokens.KEYWORD) continue;
                String word = upper(i);
                if (!CLAUSES.contains(word)) continue;
                if (word.equals("BY") && !(isKeyword(i - 1, "GROUP") || isKeyword(i - 1, "ORDER") || isKeyword(i - 1, "PARTITION"))) continue;
                return i;
            }
            return -1;
        }

        /** DROP TABLE IF EXISTS |, DELETE FROM ONLY |: modifiers do not fill the table slot. */
        boolean onlyModifiersBetween(int clauseAt, int idx) {
            if (idx <= clauseAt + 1) return false;
            for (int i = clauseAt + 1; i < idx; i++) {
                if (!(stmt.get(i).type() == LatticeSqlTokens.KEYWORD && Set.of("IF", "NOT", "EXISTS", "ONLY").contains(upper(i)))) return false;
            }
            return true;
        }

        @Nullable TableRef refAfter(int introducerIndex) {
            for (TableRef ref : refs) if (ref.introducerIndex() == introducerIndex) return ref;
            return null;
        }

        Set<String> wordsIn(int block) {
            Set<String> words = new HashSet<>();
            for (int i = 0; i < n; i++) {
                if (blockOf[i] != block) continue;
                var type = stmt.get(i).type();
                if (type == LatticeSqlTokens.IDENTIFIER || type == LatticeSqlTokens.QUOTED_IDENTIFIER) words.add(unquote(i).toUpperCase(Locale.ROOT));
            }
            return words;
        }
    }
}
