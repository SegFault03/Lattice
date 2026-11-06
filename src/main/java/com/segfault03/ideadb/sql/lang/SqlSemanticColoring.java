package com.segfault03.ideadb.sql.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.doc.SqlReferenceResolver;
import com.segfault03.ideadb.sql.doc.SqlTarget;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Which names of SQL text the connection's schema gives a role (table, view, alias, key or kind of column), by the same
 * resolution as the hover card. Every surface that colors SQL asks here, and takes the colors from {@link SqlSemanticColors},
 * so a name looks the same in a file, in an embedded editor, in a dialog and in a hover card. Each name is resolved against
 * its own statement, so the cost follows the statement, not the script.
 */
public final class SqlSemanticColoring {
    /** Scripts larger than this keep the token colors; resolving every name of a dump is not worth the wait. */
    public static final int TEXT_LIMIT = 300_000;

    private SqlSemanticColoring() {}

    /** The schema to resolve a file's names against: the cached one, or null when there is none or it may not be current (a read is then asked for). */
    public static @Nullable SqlSchema currentSchema(@Nullable ConnectionConfig connection, @Nullable VirtualFile file) {
        if (connection == null) return null;
        SqlSchemaService service = SqlSchemaService.getInstance();
        if (service.isStale(connection.getId())) {
            service.ensureLoaded(connection);
            return null;
        }
        return SqlDialectResolver.schema(connection, file);
    }

    /** A name's range in the text, and the role it has. */
    public record Colored(@NotNull TextRange range, @NotNull Role role) {}

    /** The names of {@code text} that the schema gives a role, in text order. */
    public static @NotNull List<Colored> colored(@NotNull CharSequence text, @NotNull SqlDialectProfile profile, @NotNull SqlSchema schema) {
        List<Colored> found = new ArrayList<>();
        if (text.length() > TEXT_LIMIT) return found;
        var lexer = new LatticeSqlLexer(profile);
        lexer.start(text, 0, text.length(), 0);
        int statementStart = 0;
        List<int[]> names = new ArrayList<>();
        for (IElementType type = lexer.getTokenType(); ; lexer.advance(), type = lexer.getTokenType()) {
            boolean end = type == null;
            if (!end && LatticeSqlTokens.NAMES.contains(type)) names.add(new int[] {lexer.getTokenStart(), lexer.getTokenEnd()});
            boolean semicolon = !end && type == LatticeSqlTokens.PUNCTUATION && text.charAt(lexer.getTokenStart()) == ';';
            if (end || semicolon) {
                int statementEnd = end ? text.length() : lexer.getTokenEnd();
                if (!names.isEmpty()) colorStatement(text.subSequence(statementStart, statementEnd), statementStart, names, profile, schema, found);
                names.clear();
                statementStart = statementEnd;
            }
            if (end) break;
        }
        return found;
    }

    private static void colorStatement(CharSequence statement, int base, List<int[]> names, SqlDialectProfile profile, SqlSchema schema, List<Colored> found) {
        for (int[] name : names) {
            SqlTarget target = SqlReferenceResolver.resolve(statement, name[0] - base, profile, schema);
            Role role = target == null ? null : roleOf(target, schema);
            if (role != null) found.add(new Colored(new TextRange(name[0], name[1]), role));
        }
    }

    private static @Nullable Role roleOf(SqlTarget target, SqlSchema schema) {
        return switch (target) {
            case SqlTarget.Schema ignored -> null;
            case SqlTarget.Table table -> tableRole(schema.table(table.namespace(), table.table()), Role.TABLE);
            case SqlTarget.Alias alias -> tableRole(schema.table(alias.namespace(), alias.table()), Role.ALIAS);
            case SqlTarget.Column column -> {
                ExplorerMetadataSnapshot.Table owner = schema.table(column.namespace(), column.table());
                if (owner == null) yield null;
                for (ColumnMetadata candidate : owner.columns()) {
                    if (candidate.getName().equalsIgnoreCase(column.column())) yield SqlSemanticColors.roleOf(candidate);
                }
                yield null;
            }
        };
    }

    /** A view is a view whatever it is called in the statement; an alias of one keeps the alias color. */
    private static @Nullable Role tableRole(@Nullable ExplorerMetadataSnapshot.Table table, Role plain) {
        if (table == null) return null;
        return plain == Role.TABLE && table.metadata().isView() ? Role.VIEW : plain;
    }
}
