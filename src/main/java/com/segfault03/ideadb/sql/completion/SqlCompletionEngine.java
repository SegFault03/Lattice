package com.segfault03.ideadb.sql.completion;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ColumnReference;
import com.intellij.openapi.progress.ProgressManager;
import com.segfault03.ideadb.sql.completion.SqlContext.Position;
import com.segfault03.ideadb.sql.completion.SqlContext.TableRef;
import com.segfault03.ideadb.sql.completion.SqlSuggestion.Kind;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link SqlContext} into suggestions using the dialect's word lists and the schema cache. It is pure:
 * no editor, no database. The editor filters by the typed prefix, so everything that fits the position is returned,
 * most relevant first.
 */
public final class SqlCompletionEngine {
    private static final double JOIN = 100, QUALIFIED_COLUMN = 90, COLUMN = 80, KEYWORD_HERE = 85, TABLE = 70, ALIAS = 60,
            KEYWORD = 50, FUNCTION = 40, TYPE = 40;

    private SqlCompletionEngine() {}

    public static @NotNull List<SqlSuggestion> suggest(@NotNull SqlContext context, @NotNull SqlDialectProfile profile,
                                                       @Nullable SqlSchema schema) {
        if (context.position() == Position.NONE) return List.of();
        List<SqlSuggestion> out = new ArrayList<>();
        if (!context.qualifier().isEmpty()) {
            qualified(context, profile, schema, out);
        } else {
            unqualified(context, profile, schema, out);
        }
        out.sort(Comparator.comparingDouble(SqlSuggestion::priority).reversed());
        return List.copyOf(deduplicate(out));
    }

    // ---- unqualified -------------------------------------------------------------------------------------------------

    private static void unqualified(SqlContext context, SqlDialectProfile profile, @Nullable SqlSchema schema, List<SqlSuggestion> out) {
        Position position = context.position();
        boolean quoted = context.openQuote() != 0;
        switch (position) {
            case TABLE_NAME -> {
                tablesAndNamespaces(null, context, profile, schema, out);
                relatedTables(context, profile, schema, out);
            }
            case SELECT_LIST, CONDITION, ORDER_GROUP -> {
                if (!context.afterOperand()) {
                    scopeColumns(context, profile, schema, out);
                    scopeNames(context, profile, out);
                    joinConditions(context, profile, schema, out);
                }
            }
            case SET_COLUMN, INSERT_COLUMNS -> targetColumns(context, profile, schema, true, out);
            case SET_VALUE -> { if (!context.afterOperand()) targetColumns(context, profile, schema, false, out); }
            case TYPE_NAME -> types(context, profile, out);
            case OTHER -> types(context, profile, out);
            default -> {}
        }
        if (quoted) return;
        for (String phrase : SqlKeywordPhrases.forPosition(context, profile)) {
            out.add(word(Kind.KEYWORD, phrase, context, context.afterOperand() || position == Position.AFTER_TABLE ? KEYWORD_HERE : KEYWORD, null));
        }
        if (position == Position.STATEMENT_START) bumpStatementKeywords(out);
        if (SqlKeywordPhrases.takesFunctions(position, context.afterOperand())) {
            for (String function : profile.functions()) out.add(word(Kind.FUNCTION, function, context, FUNCTION, "()"));
        }
    }

    private static void bumpStatementKeywords(List<SqlSuggestion> out) {
        out.replaceAll(s -> s.kind() == Kind.KEYWORD
                ? new SqlSuggestion(s.kind(), s.label(), s.insertText(), s.typeText(), s.tailText(), KEYWORD_HERE, s.key()) : s);
    }

    private static void types(SqlContext context, SqlDialectProfile profile, List<SqlSuggestion> out) {
        if (context.openQuote() != 0) return;
        for (String type : profile.types()) out.add(word(Kind.TYPE, type, context, TYPE, null));
    }

    private static SqlSuggestion word(Kind kind, String text, SqlContext context, double priority, @Nullable String tail) {
        String shown = context.lowercaseKeywords() ? text.toLowerCase(Locale.ROOT) : text.toUpperCase(Locale.ROOT);
        return new SqlSuggestion(kind, shown, shown, null, tail, priority);
    }

    // ---- tables ------------------------------------------------------------------------------------------------------

    private static void tablesAndNamespaces(@Nullable String namespace, SqlContext context, SqlDialectProfile profile,
                                            @Nullable SqlSchema schema, List<SqlSuggestion> out) {
        if (schema == null) return;
        var resolved = schema.namespace(namespace);
        if (resolved != null) {
            for (var table : resolved.tables()) {
                ProgressManager.checkCanceled();
                String name = table.metadata().getName();
                boolean view = table.metadata().isView();
                out.add(new SqlSuggestion(view ? Kind.VIEW : Kind.TABLE, name, insertion(name, context, profile),
                        view ? "view" : "table", resolved.name(), TABLE));
            }
        }
        if (namespace != null) return;
        for (var other : schema.namespaces()) {
            out.add(new SqlSuggestion(Kind.NAMESPACE, other.name(), insertion(other.name(), context, profile) + ".", "schema", null, ALIAS));
        }
    }

    // ---- columns -----------------------------------------------------------------------------------------------------

    private static void scopeColumns(SqlContext context, SqlDialectProfile profile, @Nullable SqlSchema schema, List<SqlSuggestion> out) {
        if (schema == null) return;
        List<TableRef> refs = visibleTables(context);
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (TableRef ref : refs) {
            ProgressManager.checkCanceled();
            for (ColumnMetadata column : columnsOf(ref, schema)) occurrences.merge(column.getName().toUpperCase(Locale.ROOT), 1, Integer::sum);
        }
        boolean several = refs.size() > 1;
        for (TableRef ref : refs) {
            for (ColumnMetadata column : columnsOf(ref, schema)) {
                boolean ambiguous = several && occurrences.get(column.getName().toUpperCase(Locale.ROOT)) > 1;
                String insert = insertion(column.getName(), context, profile);
                if (ambiguous && ref.exposedName() != null && context.openQuote() == 0) insert = reference(ref.exposedName(), context, profile) + "." + insert;
                out.add(column(column, insert, ref.exposedName(), ambiguous ? QUALIFIED_COLUMN : COLUMN));
            }
        }
    }

    private static void targetColumns(SqlContext context, SqlDialectProfile profile, @Nullable SqlSchema schema, boolean skipUsed, List<SqlSuggestion> out) {
        if (schema == null || context.target() == null) return;
        for (ColumnMetadata column : columnsOf(context.target(), schema)) {
            if (skipUsed && context.usedNames().contains(column.getName().toUpperCase(Locale.ROOT))) continue;
            double priority = column.isGenerated() || column.isAutoIncrement() ? COLUMN - 10 : COLUMN;
            out.add(column(column, insertion(column.getName(), context, profile), context.target().name(), priority));
        }
    }

    private static SqlSuggestion column(ColumnMetadata column, String insert, @Nullable String owner, double priority) {
        boolean primary = column.isPrimaryKey(), foreign = column.isForeignKey() || column.getReference() != null;
        String flags = primary && foreign ? "PK FK " : primary ? "PK " : foreign ? "FK " : "";
        SqlSuggestion.Key key = primary && foreign ? SqlSuggestion.Key.PRIMARY_FOREIGN
                : primary ? SqlSuggestion.Key.PRIMARY : foreign ? SqlSuggestion.Key.FOREIGN : SqlSuggestion.Key.NONE;
        return new SqlSuggestion(Kind.COLUMN, column.getName(), insert, flags + column.getFormattedType(), owner, priority, key);
    }

    /** The tables a column may come from, nearest scope first: the caret's own query, then enclosing ones. */
    private static List<TableRef> visibleTables(SqlContext context) {
        return context.tables().stream().filter(ref -> !ref.derived() && ref.name() != null).toList();
    }

    private static List<ColumnMetadata> columnsOf(TableRef ref, SqlSchema schema) {
        if (ref.name() == null) return List.of();
        var table = schema.table(ref.namespace(), ref.name());
        return table == null ? List.of() : table.columns();
    }

    /** Aliases and table names, offered so the user can start "alias." from the select list. */
    private static void scopeNames(SqlContext context, SqlDialectProfile profile, List<SqlSuggestion> out) {
        for (TableRef ref : context.tables()) {
            String exposed = ref.exposedName();
            if (exposed == null) continue;
            out.add(new SqlSuggestion(Kind.ALIAS, exposed, reference(exposed, context, profile) + ".", ref.derived() ? "subquery" : ref.name(), null, ALIAS));
        }
    }

    // ---- qualified ---------------------------------------------------------------------------------------------------

    private static void qualified(SqlContext context, SqlDialectProfile profile, @Nullable SqlSchema schema, List<SqlSuggestion> out) {
        List<String> qualifier = context.qualifier();
        if (schema == null) return;
        if (context.position() == Position.TABLE_NAME && qualifier.size() == 1) {
            tablesAndNamespaces(qualifier.get(0), context, profile, schema, out);
            return;
        }
        TableRef owner = null;
        if (qualifier.size() == 1) {
            String wanted = qualifier.get(0);
            for (TableRef ref : context.tables()) {
                if (ref.exposedName() != null && ref.exposedName().equalsIgnoreCase(wanted)) owner = ref;
            }
        }
        if (owner == null && qualifier.size() == 1 && context.tables().stream()
                .anyMatch(ref -> ref.alias() != null && qualifier.get(0).equalsIgnoreCase(ref.name()))) {
            return; // the alias replaces the table's own name within its query
        }
        if (owner == null && qualifier.size() <= 2) {
            String namespace = qualifier.size() == 2 ? qualifier.get(0) : null;
            String table = qualifier.get(qualifier.size() - 1);
            if (namespace != null || schema.table(null, table) != null) {
                for (ColumnMetadata column : columnsOfNamed(namespace, table, schema)) {
                    out.add(column(column, insertion(column.getName(), context, profile), null, COLUMN));
                }
                return;
            }
        }
        if (owner != null) {
            for (ColumnMetadata column : columnsOf(owner, schema)) out.add(column(column, insertion(column.getName(), context, profile), null, COLUMN));
            return;
        }
        if (qualifier.size() == 1 && schema.namespace(qualifier.get(0)) != null) {
            tablesAndNamespaces(qualifier.get(0), context, profile, schema, out);
        }
    }

    private static List<ColumnMetadata> columnsOfNamed(@Nullable String namespace, String table, SqlSchema schema) {
        var found = schema.table(namespace, table);
        return found == null ? List.of() : found.columns();
    }

    // ---- joins -------------------------------------------------------------------------------------------------------

    /** "a.customer_id = b.id" for every foreign key between the joined table and an earlier one, in either direction. */
    private static void joinConditions(SqlContext context, SqlDialectProfile profile, @Nullable SqlSchema schema, List<SqlSuggestion> out) {
        TableRef joined = context.joinTarget();
        if (schema == null || context.position() != Position.CONDITION || joined == null || joined.name() == null || joined.exposedName() == null
                || context.openQuote() != 0) return;
        for (TableRef prior : context.joinPrior()) {
            if (prior.name() == null || prior.exposedName() == null) continue;
            for (ColumnMetadata column : columnsOf(joined, schema)) {
                if (points(column.getReference(), prior, schema)) {
                    add(out, context, profile, joined, column.getName(), prior, column.getReference().column());
                }
            }
            for (ColumnMetadata column : columnsOf(prior, schema)) {
                if (points(column.getReference(), joined, schema)) {
                    add(out, context, profile, prior, column.getName(), joined, column.getReference().column());
                }
            }
        }
    }

    private static boolean points(@Nullable ColumnReference reference, TableRef table, SqlSchema schema) {
        if (reference == null || reference.table() == null || table.name() == null) return false;
        if (!reference.table().equalsIgnoreCase(table.name())) return false;
        String wanted = table.namespace() != null ? table.namespace() : schema.defaultNamespace();
        return reference.namespace() == null || wanted == null || reference.namespace().equalsIgnoreCase(wanted);
    }

    private static void add(List<SqlSuggestion> out, SqlContext context, SqlDialectProfile profile, TableRef left, String leftColumn,
                            TableRef right, String rightColumn) {
        String text = reference(left.exposedName(), context, profile) + "." + insertion(leftColumn, context, profile)
                + " = " + reference(right.exposedName(), context, profile) + "." + insertion(rightColumn, context, profile);
        out.add(new SqlSuggestion(Kind.JOIN, text, text, "foreign key", null, JOIN));
    }

    /**
     * After JOIN: every table related to one already in the statement by a foreign key, in either direction, written
     * out with an alias and its condition ("customers c ON c.id = o.customer_id").
     */
    private static void relatedTables(SqlContext context, SqlDialectProfile profile, @Nullable SqlSchema schema, List<SqlSuggestion> out) {
        if (schema == null || context.joinPrior().isEmpty() || context.openQuote() != 0) return;
        Set<String> taken = new HashSet<>();
        for (TableRef ref : context.tables()) if (ref.exposedName() != null) taken.add(ref.exposedName().toLowerCase(Locale.ROOT));
        for (TableRef prior : context.joinPrior()) {
            if (prior.name() == null || prior.exposedName() == null || prior.derived()) continue;
            var priorTable = schema.table(prior.namespace(), prior.name());
            if (priorTable == null) continue;
            String earlier = reference(prior.exposedName(), context, profile);
            for (var namespace : schema.namespaces()) {
                for (var candidate : namespace.tables()) {
                    ProgressManager.checkCanceled();
                    String name = candidate.metadata().getName();
                    String alias = alias(name, taken, profile);
                    String written = (namespace.name().equals(schema.defaultNamespace()) ? "" : insertion(namespace.name(), context, profile) + ".")
                            + insertion(name, context, profile) + " " + alias + " ON " + alias + ".";
                    for (ColumnMetadata column : candidate.columns()) { // the new table holds the foreign key
                        if (!points(column.getReference(), prior, schema)) continue;
                        String text = written + insertion(column.getName(), context, profile) + " = " + earlier + "."
                                + insertion(column.getReference().column(), context, profile);
                        out.add(new SqlSuggestion(Kind.JOIN, text, text, "foreign key", null, JOIN));
                    }
                    for (ColumnMetadata column : priorTable.columns()) { // the earlier table holds it
                        ColumnReference reference = column.getReference();
                        if (reference == null || reference.table() == null || !reference.table().equalsIgnoreCase(name)) continue;
                        if (reference.namespace() != null && !reference.namespace().equalsIgnoreCase(namespace.name())) continue;
                        String text = written + insertion(reference.column(), context, profile) + " = " + earlier + "."
                                + insertion(column.getName(), context, profile);
                        out.add(new SqlSuggestion(Kind.JOIN, text, text, "foreign key", null, JOIN));
                    }
                }
            }
        }
    }

    /**
     * A short alias for a table: the initials of its words ("order_items" gives "oi", "customers" gives "c"), with a
     * number when that is already a name in the statement or would be a keyword.
     */
    static String alias(String table, Set<String> taken, SqlDialectProfile profile) {
        StringBuilder initials = new StringBuilder();
        for (String word : table.split("[^A-Za-z0-9]+")) if (!word.isEmpty()) initials.append(word.charAt(0));
        String base = initials.length() == 0 || !Character.isLetter(initials.charAt(0)) ? "t" : initials.toString();
        String candidate = base;
        for (int n = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)) || profile.keywords().contains(candidate.toUpperCase(Locale.ROOT)); n++) {
            candidate = base + n;
        }
        return candidate;
    }

    // ---- insertion ---------------------------------------------------------------------------------------------------

    /** The name as it should be written: quoted when the dialect needs it, or closing the quote the user already opened. */
    private static String insertion(String name, SqlContext context, SqlDialectProfile profile) {
        char open = context.openQuote();
        if (open != 0) return name.replace(String.valueOf(open), String.valueOf(open) + open) + open;
        return SqlIdentifiers.forInsertion(name, profile);
    }

    /** A table or alias name the statement already uses: repeated as written, closing the user's open quote if there is one. */
    private static String reference(String name, SqlContext context, SqlDialectProfile profile) {
        char open = context.openQuote();
        if (open != 0) return name.replace(String.valueOf(open), String.valueOf(open) + open) + open;
        return SqlIdentifiers.forReference(name, profile);
    }

    private static List<SqlSuggestion> deduplicate(List<SqlSuggestion> sorted) {
        Map<String, SqlSuggestion> byKey = new LinkedHashMap<>();
        for (SqlSuggestion s : sorted) byKey.putIfAbsent(s.kind() + "\u0000" + s.insertText(), s);
        return new ArrayList<>(byKey.values());
    }
}
