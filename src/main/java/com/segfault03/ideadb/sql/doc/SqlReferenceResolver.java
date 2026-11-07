package com.segfault03.ideadb.sql.doc;

import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.completion.SqlContext;
import com.segfault03.ideadb.sql.completion.SqlContext.Position;
import com.segfault03.ideadb.sql.completion.SqlContext.TableRef;
import com.segfault03.ideadb.sql.completion.SqlStatementAnalyzer;
import com.segfault03.ideadb.sql.completion.SqlTextWindow;
import com.segfault03.ideadb.sql.completion.SqlTokens;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlLexer;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Decides what the name under a position in SQL text refers to, using the same statement analysis as completion:
 * the analysis runs with the caret just before the word, so the word itself is part of the statement and its
 * qualifier, clause and the statement's tables are known.
 */
public final class SqlReferenceResolver {
    private SqlReferenceResolver() {}

    public static @Nullable SqlTarget resolve(@NotNull CharSequence text, int offset, @NotNull SqlDialectProfile profile, @NotNull SqlSchema schema) {
        var window = SqlTextWindow.around(text, offset);
        if (window.length() < text.length()) return resolve(text.subSequence(window.start(), window.end()), offset - window.start(), profile, schema);
        Word word = wordAt(text, offset, profile);
        if (word == null) return null;
        SqlContext context = SqlStatementAnalyzer.analyze(text, word.start, profile);
        if (context.position() == Position.NONE) return null;
        List<String> qualifier = context.qualifier();
        String name = word.name;
        if (qualifier.isEmpty()) return unqualified(name, word.followedByDot, context, schema);
        if (word.followedByDot) {
            // a.b.| : the word is one more step in a chain, the only one that can continue is schema.table
            return qualifier.size() == 1 && schema.namespace(qualifier.get(0)) != null ? table(schema, qualifier.get(0), name) : null;
        }
        if (context.position() == Position.TABLE_NAME) return qualifier.size() == 1 ? table(schema, qualifier.get(0), name) : null;
        TableRef owner = ownerOf(qualifier, context, schema);
        if (qualifier.size() == 1 && owner == null && hiddenByAlias(qualifier.get(0), context)) return null;
        if (owner != null) return column(schema, owner.namespace(), owner.name(), name);
        if (qualifier.size() == 2) return column(schema, qualifier.get(0), qualifier.get(1), name);
        return qualifier.size() == 1 && schema.table(null, qualifier.get(0)) != null ? column(schema, null, qualifier.get(0), name) : null;
    }

    private static @Nullable SqlTarget unqualified(String name, boolean followedByDot, SqlContext context, SqlSchema schema) {
        if (context.position() == Position.TABLE_NAME) {
            if (followedByDot) return schema.namespace(name) == null ? null : new SqlTarget.Schema(schema.namespace(name).name());
            return table(schema, null, name);
        }
        for (TableRef ref : context.tables()) {
            if (ref.exposedName() == null || !ref.exposedName().equalsIgnoreCase(name)) continue;
            if (ref.derived() || ref.name() == null) return null;
            var found = schema.table(ref.namespace(), ref.name());
            if (found == null) return null;
            String namespace = namespaceOf(schema, ref.namespace());
            return ref.alias() != null
                    ? new SqlTarget.Alias(ref.alias(), namespace, found.metadata().getName())
                    : new SqlTarget.Table(namespace, found.metadata().getName());
        }
        if (followedByDot) {
            var namespace = schema.namespace(name);
            return namespace == null ? null : new SqlTarget.Schema(namespace.name());
        }
        if ((context.position() == Position.SET_COLUMN || context.position() == Position.INSERT_COLUMNS) && context.target() != null && context.target().name() != null) {
            return column(schema, context.target().namespace(), context.target().name(), name);
        }
        for (TableRef ref : context.tables()) {
            if (ref.name() == null) continue;
            SqlTarget found = column(schema, ref.namespace(), ref.name(), name);
            if (found != null) return found;
        }
        return null;
    }

    private static @Nullable TableRef ownerOf(List<String> qualifier, SqlContext context, SqlSchema schema) {
        if (qualifier.size() != 1) return null;
        for (TableRef ref : context.tables()) {
            if (ref.exposedName() != null && ref.exposedName().equalsIgnoreCase(qualifier.get(0)) && !ref.derived() && ref.name() != null) return ref;
        }
        return null;
    }

    private static boolean hiddenByAlias(String qualifier, SqlContext context) {
        return context.tables().stream().anyMatch(ref -> ref.alias() != null && qualifier.equalsIgnoreCase(ref.name()));
    }

    private static @Nullable SqlTarget table(SqlSchema schema, @Nullable String namespace, String name) {
        var found = schema.table(namespace, name);
        return found == null ? null : new SqlTarget.Table(namespaceOf(schema, namespace), found.metadata().getName());
    }

    private static @Nullable SqlTarget column(SqlSchema schema, @Nullable String namespace, String table, String name) {
        ExplorerMetadataSnapshot.Table found = schema.table(namespace, table);
        if (found == null) return null;
        for (ColumnMetadata column : found.columns()) {
            if (column.getName().equalsIgnoreCase(name)) return new SqlTarget.Column(namespaceOf(schema, namespace), found.metadata().getName(), column.getName());
        }
        return null;
    }

    private static String namespaceOf(SqlSchema schema, @Nullable String namespace) {
        var found = schema.namespace(namespace);
        return found == null ? String.valueOf(namespace) : found.name();
    }

    // ---- the word under the position ------------------------------------------------------------------------------------

    private record Word(String name, int start, int end, boolean followedByDot) {}

    private static @Nullable Word wordAt(CharSequence text, int offset, SqlDialectProfile profile) {
        var lexer = new LatticeSqlLexer(profile);
        lexer.start(text, 0, text.length(), 0);
        for (IElementType type = lexer.getTokenType(); type != null; lexer.advance(), type = lexer.getTokenType()) {
            int start = lexer.getTokenStart(), end = lexer.getTokenEnd();
            if (offset < start) return null;
            if (offset >= end) continue;
            if (!SqlTokens.isWord(type)) return null;
            String raw = text.subSequence(start, end).toString();
            String name = type == LatticeSqlTokens.QUOTED_IDENTIFIER ? unquote(raw) : raw;
            int next = end;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
            return new Word(name, start, end, next < text.length() && text.charAt(next) == '.');
        }
        return null;
    }

    private static String unquote(String raw) {
        if (raw.length() < 2) return raw;
        char open = raw.charAt(0);
        char close = raw.charAt(raw.length() - 1);
        if ((open != '"' && open != '`') || close != open) return raw;
        String inner = raw.substring(1, raw.length() - 1);
        return inner.replace(String.valueOf(open) + open, String.valueOf(open));
    }
}
