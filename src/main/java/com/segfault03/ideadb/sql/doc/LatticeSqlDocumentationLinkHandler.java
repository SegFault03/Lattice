package com.segfault03.ideadb.sql.doc;

import com.intellij.platform.backend.documentation.DocumentationLinkHandler;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.LinkResolveResult;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Follows the links in Lattice SQL cards: to another card, or to the table in a Lattice editor. */
public final class LatticeSqlDocumentationLinkHandler implements DocumentationLinkHandler {
    @Override
    public @Nullable LinkResolveResult resolveLink(@NotNull DocumentationTarget target, @NotNull String url) {
        if (!(target instanceof SqlDocumentationTarget source)) return null;
        SqlDocLinks.Parsed link = SqlDocLinks.parse(url);
        if (link == null) return null;
        var next = new SqlDocumentationTarget(source.project(), link.connectionId(), link.target());
        if (link.kind().equals("open")) {
            open(source, link);
            return LinkResolveResult.resolvedTarget(source);
        }
        return next.html() == null ? null : LinkResolveResult.resolvedTarget(next);
    }

    private static void open(SqlDocumentationTarget source, SqlDocLinks.Parsed link) {
        ConnectionConfig config = DatabaseSettingsState.getInstance().getConnection(link.connectionId());
        if (config == null || !(link.target() instanceof SqlTarget.Table table)) return;
        var schema = SqlSchemaService.getInstance().schema(config);
        var found = schema == null ? null : schema.table(table.namespace(), table.table());
        if (found != null) DatabaseEditorManager.getInstance(source.project()).openTableData(config, table.namespace(), found.metadata());
    }
}
