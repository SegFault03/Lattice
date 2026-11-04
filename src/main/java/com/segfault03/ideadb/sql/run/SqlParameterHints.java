package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.completion.SqlContext.TableRef;
import com.segfault03.ideadb.sql.completion.SqlStatementAnalyzer;
import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.completion.SqlTokenizer;
import com.segfault03.ideadb.sql.completion.SqlTokens;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What kind of value each parameter of a statement expects, worked out from where it stands: the column it is compared
 * with ({@code col = ?}, {@code ? < col}, {@code col IN (?, ?)}, {@code col BETWEEN ? AND ?}, {@code col LIKE ?}), the
 * column it is assigned to ({@code SET col = ?}, {@code INSERT INTO t (a, b) VALUES (?, ?)}), or a row count
 * ({@code LIMIT ?}, {@code OFFSET ?}, {@code FETCH FIRST ? ROWS}). With the connection's cached schema the column's
 * type gives the expected value; without it only the column's name is known. Hints only advise: nothing is refused for
 * not matching one.
 */
public final class SqlParameterHints {
    private static final Set<String> COMPARISONS = Set.of("=", "<>", "!=", "<", ">", "<=", ">=", "==");
    private static final Set<String> PATTERN_WORDS = Set.of("LIKE", "ILIKE");
    private static final Set<String> COUNT_WORDS = Set.of("LIMIT", "OFFSET", "TOP");

    /**
     * @param column    the column as the statement names it, qualified by its table when the schema knows the table
     * @param sqlType   the column's declared type, when the schema knows it
     * @param suggested the value type to start from, or null when nothing says
     * @param note      what the value is for when that is not a column ("row count", "LIKE pattern")
     */
    public record Hint(@Nullable String column, @Nullable String sqlType, @Nullable SqlParameterValue.Type suggested,
                       @Nullable String note, boolean nullable) {
        static final Hint NONE = new Hint(null, null, null, null, true);

        boolean isEmpty() { return column == null && sqlType == null && suggested == null && note == null; }

        /** One line for the form: "orders.placed_at · TIMESTAMP", "row count", "LIKE pattern · customers.name · VARCHAR(80)". */
        public @NotNull String describe() {
            List<String> parts = new ArrayList<>(3);
            if (note != null) parts.add(note);
            if (column != null) parts.add(column);
            if (sqlType != null) parts.add(nullable ? sqlType : sqlType + " NOT NULL");
            return String.join(" · ", parts);
        }
    }

    private SqlParameterHints() {}

    /** A hint per parameter key; for a key used more than once, the first occurrence that says something. */
    public static @NotNull Map<String, Hint> of(@NotNull CharSequence sql, @NotNull SqlDialectProfile profile,
                                                @NotNull SqlParameters.Result parameters, @Nullable SqlSchema schema) {
        Map<String, Hint> hints = new LinkedHashMap<>();
        for (String key : parameters.keys()) hints.put(key, Hint.NONE);
        if (parameters.occurrences().isEmpty()) return hints;
        List<SqlToken> tokens = SqlTokenizer.tokenize(sql, profile).stream()
                .filter(token -> token.type() != LatticeSqlTokens.LINE_COMMENT && token.type() != LatticeSqlTokens.BLOCK_COMMENT).toList();
        Reader reader = new Reader(tokens, sql, profile, schema);
        for (SqlParameters.Occurrence occurrence : parameters.occurrences()) {
            if (!hints.get(occurrence.key()).isEmpty()) continue;
            int index = reader.indexAt(occurrence.start());
            if (index >= 0) hints.put(occurrence.key(), reader.hint(index));
        }
        return hints;
    }

    /** The value type a column of this JDBC type takes. */
    static @NotNull SqlParameterValue.Type typeOf(@NotNull ColumnMetadata column) {
        return switch (column.getDataType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.FLOAT, Types.DOUBLE,
                 Types.NUMERIC, Types.DECIMAL -> SqlParameterValue.Type.NUMBER;
            case Types.BOOLEAN -> SqlParameterValue.Type.BOOLEAN;
            case Types.BIT -> column.getColumnSize() <= 1 ? SqlParameterValue.Type.BOOLEAN : SqlParameterValue.Type.TEXT;
            case Types.DATE -> SqlParameterValue.Type.DATE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> SqlParameterValue.Type.TIMESTAMP;
            default -> SqlParameterValue.Type.TEXT;
        };
    }

    private static final class Reader extends SqlTokens {
        private final List<SqlToken> tokens;
        private final @Nullable SqlSchema schema;
        private final List<TableRef> refs;

        Reader(List<SqlToken> tokens, CharSequence sql, SqlDialectProfile profile, @Nullable SqlSchema schema) {
            super(tokens, sql);
            this.tokens = tokens;
            this.schema = schema;
            this.refs = SqlStatementAnalyzer.structure(sql, tokens, profile).refs();
        }

        int indexAt(int offset) {
            for (int i = 0; i < tokens.size(); i++) if (tokens.get(i).start() == offset) return i;
            return -1;
        }

        Hint hint(int p) {
            if (in(p - 1) && isWord(p - 1) && COUNT_WORDS.contains(up(p - 1)) ||(word(p - 1, "FIRST") || word(p - 1, "NEXT")) && word(p - 2, "FETCH")
                    || punct(p - 1, ',') && isValue(p - 2) && word(p - 3, "LIMIT")) {
                return new Hint(null, null, SqlParameterValue.Type.NUMBER, "row count", false);
            }
            if (in(p - 1) && PATTERN_WORDS.contains(up(p - 1))) {
                List<String> name = nameEndingAt(word(p - 2, "NOT") ? p - 3 : p - 2);
                Hint column = column(name);
                return new Hint(column.column(), column.sqlType(), SqlParameterValue.Type.TEXT, "LIKE pattern (% any, _ one character)", column.nullable());
            }
            if (in(p - 1) && tokens.get(p - 1).type() == LatticeSqlTokens.OPERATOR && COMPARISONS.contains(up(p - 1))) {
                return column(nameEndingAt(p - 2));
            }
            if (in(p + 1) && tokens.get(p + 1).type() == LatticeSqlTokens.OPERATOR && COMPARISONS.contains(up(p + 1))) {
                return column(nameStartingAt(p + 2));
            }
            if (word(p - 1, "BETWEEN")) return column(nameEndingAt(word(p - 2, "NOT") ? p - 3 : p - 2));
            if (word(p - 1, "AND") && isValue(p - 2) && word(p - 3, "BETWEEN")) return column(nameEndingAt(word(p - 4, "NOT") ? p - 5 : p - 4));
            if (punct(p - 1, '(') || punct(p - 1, ',')) {
                int open = openingParen(p);
                if (open < 0) return Hint.NONE;
                if (word(open - 1, "IN")) return column(nameEndingAt(word(open - 2, "NOT") ? open - 3 : open - 2));
                return insertedColumn(open, p);
            }
            return Hint.NONE;
        }

        /** A parameter, number or string: the first operand of BETWEEN, or the offset of {@code LIMIT offset, count}. */
        private boolean isValue(int i) {
            if (!in(i)) return false;
            var type = tokens.get(i).type();
            return type == LatticeSqlTokens.PARAMETER || type == LatticeSqlTokens.NUMBER || type == LatticeSqlTokens.STRING;
        }

        /** The "(" of the list that holds token {@code p}, or -1. */
        private int openingParen(int p) {
            int depth = 0;
            for (int i = p - 1; i >= 0; i--) {
                if (punct(i, ')')) depth++;
                else if (punct(i, '(')) {
                    if (depth == 0) return i;
                    depth--;
                }
            }
            return -1;
        }

        private int closingParen(int open) {
            int depth = 0;
            for (int i = open; i < size(); i++) {
                if (punct(i, '(')) depth++;
                else if (punct(i, ')') && --depth == 0) return i;
            }
            return -1;
        }

        /** How many top-level commas come between {@code open} and {@code p}: the position of {@code p} in its list. */
        private int position(int open, int p) {
            int depth = 0, commas = 0;
            for (int i = open + 1; i < p; i++) {
                if (punct(i, '(')) depth++;
                else if (punct(i, ')')) depth--;
                else if (depth == 0 && punct(i, ',')) commas++;
            }
            return commas;
        }

        /**
         * {@code INSERT INTO t [(a, b)] VALUES (...), (...)}: the column at the parameter's position in its row, from the
         * column list, else from the table's columns in order.
         */
        private Hint insertedColumn(int open, int p) {
            int at = open - 1;
            while (punct(at, ',') && punct(at - 1, ')')) {
                int previous = openingOf(at - 1);
                if (previous < 0) return Hint.NONE;
                at = previous - 1;
            }
            if (!word(at, "VALUES")) return Hint.NONE;
            int index = position(open, p);
            if (punct(at - 1, ')')) {
                int listOpen = openingOf(at - 1);
                if (listOpen < 0) return Hint.NONE;
                List<String> table = nameEndingAt(listOpen - 1);
                List<String> columns = new ArrayList<>();
                for (int i = listOpen + 1, close = closingParen(listOpen); i < close; i++) if (isNameLike(i)) columns.add(name(i));
                if (index >= columns.size()) return Hint.NONE;
                return inTable(table, columns.get(index));
            }
            List<String> table = nameEndingAt(at - 1);
            ExplorerMetadataSnapshot.Table found = table(table);
            if (found == null || !found.columnsLoaded() || index >= found.columns().size()) return Hint.NONE;
            return describe(found, found.columns().get(index));
        }

        private int openingOf(int close) {
            int depth = 0;
            for (int i = close; i >= 0; i--) {
                if (punct(i, ')')) depth++;
                else if (punct(i, '(') && --depth == 0) return i;
            }
            return -1;
        }

        /** {@code a}, {@code t.a} or {@code s.t.a} ending at token {@code end}; empty when there is no name there. */
        private List<String> nameEndingAt(int end) {
            List<String> parts = new ArrayList<>();
            if (!isName(end)) return parts;
            parts.add(0, name(end));
            int i = end;
            while (punct(i - 1, '.') && isName(i - 2)) {
                parts.add(0, name(i - 2));
                i -= 2;
            }
            return parts;
        }

        private List<String> nameStartingAt(int start) {
            List<String> parts = new ArrayList<>();
            if (!isName(start)) return parts;
            parts.add(name(start));
            int i = start;
            while (punct(i + 1, '.') && isName(i + 2)) {
                parts.add(name(i + 2));
                i += 2;
            }
            // A function call or a further operand is not a plain column.
            return punct(i + 1, '(') ? List.of() : parts;
        }

        private boolean isName(int i) {
            if (!in(i)) return false;
            var type = tokens.get(i).type();
            return type == LatticeSqlTokens.IDENTIFIER || type == LatticeSqlTokens.QUOTED_IDENTIFIER
                    || type == LatticeSqlTokens.KEYWORD && !reserved(i) || type == LatticeSqlTokens.TYPE;
        }

        /** Words that end an operand, so "WHERE = ?" is never read as a column named WHERE. */
        private boolean reserved(int i) {
            return keywordIn(i, Set.of("WHERE", "AND", "OR", "NOT", "ON", "SET", "SELECT", "FROM", "HAVING", "WHEN", "THEN", "ELSE",
                    "CASE", "BETWEEN", "IN", "LIKE", "ILIKE", "IS", "NULL", "VALUES", "BY", "AS"));
        }

        private String name(int i) {
            String text = tokens.get(i).text(text());
            if (tokens.get(i).type() == LatticeSqlTokens.QUOTED_IDENTIFIER && text.length() >= 2) return text.substring(1, text.length() - 1);
            return text;
        }

        /** The hint for a column the statement names, qualified or not, read through the statement's tables. */
        private Hint column(List<String> name) {
            if (name.isEmpty()) return Hint.NONE;
            String column = name.get(name.size() - 1);
            String written = String.join(".", name);
            if (schema == null) return new Hint(written, null, null, null, true);
            if (name.size() >= 2) {
                String qualifier = name.get(name.size() - 2);
                for (TableRef ref : refs) {
                    if (ref.derived() || ref.name() == null) continue;
                    if (qualifier.equalsIgnoreCase(ref.exposedName()) || qualifier.equalsIgnoreCase(ref.name())) {
                        return inTable(ref, column, written);
                    }
                }
                return inTable(name.subList(0, name.size() - 1), column);
            }
            Hint only = null;
            for (TableRef ref : refs) {
                if (ref.derived() || ref.name() == null) continue;
                ExplorerMetadataSnapshot.Table table = schema.table(ref.namespace(), ref.name());
                ColumnMetadata found = table == null ? null : find(table, column);
                if (found == null) continue;
                if (only != null) return new Hint(written, null, null, null, true); // ambiguous: in more than one table
                only = describe(table, found);
            }
            return only != null ? only : new Hint(written, null, null, null, true);
        }

        private Hint inTable(TableRef ref, String column, String written) {
            ExplorerMetadataSnapshot.Table table = schema == null ? null : schema.table(ref.namespace(), ref.name());
            ColumnMetadata found = table == null ? null : find(table, column);
            return found == null ? new Hint(written, null, null, null, true) : describe(table, found);
        }

        private Hint inTable(List<String> table, String column) {
            String written = table.isEmpty() ? column : String.join(".", table) + "." + column;
            ExplorerMetadataSnapshot.Table found = table(table);
            ColumnMetadata metadata = found == null ? null : find(found, column);
            return metadata == null ? new Hint(written, null, null, null, true) : describe(found, metadata);
        }

        private @Nullable ExplorerMetadataSnapshot.Table table(List<String> name) {
            if (schema == null || name.isEmpty()) return null;
            String table = name.get(name.size() - 1);
            String namespace = name.size() >= 2 ? name.get(name.size() - 2) : null;
            return schema.table(namespace, table);
        }

        private static @Nullable ColumnMetadata find(ExplorerMetadataSnapshot.Table table, String name) {
            ColumnMetadata ignoringCase = null;
            int matches = 0;
            for (ColumnMetadata column : table.columns()) {
                if (column.getName().equals(name)) return column;
                if (column.getName().equalsIgnoreCase(name)) {
                    ignoringCase = column;
                    matches++;
                }
            }
            return matches == 1 ? ignoringCase : null;
        }

        private static Hint describe(ExplorerMetadataSnapshot.Table table, ColumnMetadata column) {
            return new Hint(table.metadata().getName() + "." + column.getName(), column.getFormattedType(), typeOf(column), null, column.isNullable());
        }
    }
}
